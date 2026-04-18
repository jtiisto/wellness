import { signal } from '@preact/signals';
import localforage from 'localforage';
import { showNotification } from '../shared/notifications.js';

// Re-export for components that import from store
export { showNotification };

// Offline cache
const cache = localforage.createInstance({
    name: 'AnalysisApp',
    storeName: 'analysis_cache',
});
const CACHE_HISTORY = 'report_history';
const CACHE_REPORT_PREFIX = 'report_';

// Navigation: 'queries' | 'loading' | 'report' | 'history'
export const currentView = signal('queries');
export const activeReportId = signal(null);
export const queries = signal([]);
export const activeReport = signal(null);
export const reportHistory = signal([]);
export const isLoading = signal(true);

// API helper
async function api(path, options = {}) {
    const res = await fetch(`/wellness/api/analysis${path}`, {
        headers: { 'Content-Type': 'application/json' },
        ...options
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({ detail: res.statusText }));
        throw new Error(err.detail);
    }
    return res.json();
}

// Geolocation + reverse geocoding
export async function getUserLocation() {
    try {
        const pos = await new Promise((resolve, reject) => {
            navigator.geolocation.getCurrentPosition(resolve, reject, {
                timeout: 8000, maximumAge: 300000
            });
        });
        const { latitude, longitude } = pos.coords;
        const res = await fetch(
            `https://nominatim.openstreetmap.org/reverse?lat=${latitude}&lon=${longitude}&format=json&zoom=10`,
            { headers: { 'Accept': 'application/json' } }
        );
        if (!res.ok) return null;
        const data = await res.json();
        const city = data.address?.city || data.address?.town || data.address?.village || data.address?.county;
        const state = data.address?.state;
        if (city && state) return `${city}, ${state}`;
        return null;
    } catch {
        return null;
    }
}

// Actions
export async function loadQueries() {
    queries.value = await api('/queries');
}

export async function submitQuery(queryId, location = null) {
    try {
        const body = { query_id: queryId };
        if (location) body.location = location;
        const result = await api('/reports', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        activeReportId.value = result.id;
        activeReport.value = { id: result.id, status: 'pending' };
        currentView.value = 'loading';
        startPolling(result.id);
    } catch (err) {
        if (err instanceof TypeError && err.message.includes('fetch')) {
            showNotification({ type: 'error', title: 'Offline', message: 'Server unreachable — new queries unavailable offline.' });
        } else {
            showNotification({ type: 'error', title: 'Error', message: err.message });
        }
    }
}

export async function loadReport(reportId) {
    try {
        const report = await api(`/reports/${reportId}`);
        activeReport.value = report;
        // Cache completed reports for offline viewing
        if (report.status === 'completed' || report.status === 'failed') {
            await cache.setItem(CACHE_REPORT_PREFIX + reportId, report).catch(() => {});
        }
    } catch (err) {
        // Fall back to cached report
        const cached = await cache.getItem(CACHE_REPORT_PREFIX + reportId).catch(() => null);
        if (cached) {
            activeReport.value = cached;
        } else {
            showNotification({ type: 'error', title: 'Offline', message: 'Report not available offline.' });
            return;
        }
    }
    activeReportId.value = reportId;
    currentView.value = 'report';
}

export async function loadHistory() {
    try {
        const history = await api('/reports');
        reportHistory.value = history;
        await cache.setItem(CACHE_HISTORY, history).catch(() => {});
    } catch (err) {
        // Fall back to cached history
        const cached = await cache.getItem(CACHE_HISTORY).catch(() => null);
        if (cached) {
            reportHistory.value = cached;
        }
    }
}

export async function deleteReport(reportId) {
    try {
        await api(`/reports/${reportId}`, { method: 'DELETE' });
        reportHistory.value = reportHistory.value.filter(r => r.id !== reportId);
        await cache.setItem(CACHE_HISTORY, reportHistory.value).catch(() => {});
        await cache.removeItem(CACHE_REPORT_PREFIX + reportId).catch(() => {});
        showNotification({ type: 'success', title: 'Deleted', message: 'Report deleted.' });
    } catch (err) {
        if (err instanceof TypeError && err.message.includes('fetch')) {
            showNotification({ type: 'error', title: 'Offline', message: 'Cannot delete reports while offline.' });
        } else {
            showNotification({ type: 'error', title: 'Error', message: err.message });
        }
    }
}

export async function checkPending() {
    const pending = await api('/reports/pending');
    if (pending.length > 0) {
        const report = pending[0];
        activeReportId.value = report.id;
        activeReport.value = report;
        if (report.status === 'completed') {
            currentView.value = 'report';
        } else {
            currentView.value = 'loading';
            startPolling(report.id);
        }
        return true;
    }
    return false;
}

// Polling (3s interval)
let pollTimer = null;

function startPolling(reportId) {
    stopPolling();
    pollTimer = setInterval(async () => {
        try {
            const report = await api(`/reports/${reportId}`);
            activeReport.value = report;
            if (report.status === 'completed' || report.status === 'failed') {
                stopPolling();
                currentView.value = 'report';
            }
        } catch (err) {
            // Ignore polling errors silently
        }
    }, 3000);
}

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

export function navigateTo(view) {
    stopPolling();
    currentView.value = view;
    if (view === 'history') loadHistory();
}

// Abandon the in-flight report client-side: stop polling, clear the active
// report, and return to the query list. The server-side task keeps running
// and the finished report will surface in history later — this is just a UI
// escape hatch so the user isn't stuck on the progress screen.
export function cancelActiveReport() {
    stopPolling();
    activeReport.value = null;
    activeReportId.value = null;
    currentView.value = 'queries';
}

// Init: load queries, check for pending reports
export async function initializeStore() {
    isLoading.value = true;
    try {
        await loadQueries();
        const hasPending = await checkPending();
        if (!hasPending) currentView.value = 'queries';
    } catch (err) {
        // Server unreachable — degrade gracefully
        if (err instanceof TypeError && err.message.includes('fetch')) {
            // Load cached history for offline viewing
            const cached = await cache.getItem(CACHE_HISTORY).catch(() => null);
            if (cached) {
                reportHistory.value = cached;
                currentView.value = 'history';
            } else {
                currentView.value = 'queries';
            }
        } else {
            showNotification({ type: 'error', title: 'Load Error', message: err.message });
            currentView.value = 'queries';
        }
    }
    isLoading.value = false;
}
