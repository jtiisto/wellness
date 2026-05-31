/**
 * Coach Store - Signals, LocalForage, and Sync Logic
 * Workout plans are server-authoritative, logs use last-write-wins
 */
import { signal, batch } from '@preact/signals';
import localforage from 'localforage';
import { getToday, getUtcNow, generateId, deepClone } from '../shared/utils.js';
import { showNotification } from '../shared/notifications.js';
import { log as debugLog } from '../shared/debug-log.js';
import { SyncScheduler } from '../shared/sync-scheduler.js';
import {
    nextDirtyAfterApply,
    selectLogsToUpload,
    resolveForceSyncLogs,
    pruneOlderThan,
    maxPlanVersion,
    adoptUploadResults,
} from './sync-logic.js';

const API_BASE = '/wellness/api/coach';

// Dedicated LocalForage instance — avoids collisions with other modules
const storage = localforage.createInstance({
    name: 'CoachApp',
    storeName: 'coach_data',
});

// Storage keys
const KEYS = {
    METADATA: 'coach_metadata',
    PLANS: 'workout_plans',
    LOGS: 'workout_logs',
    CLIENT_ID: 'coach_client_id'
};

// ==================== Signals ====================

// App state
export const currentView = signal('workout'); // 'workout' | 'settings'
export const selectedDate = signal(getToday());
export const isLoading = signal(true);
export const isSyncing = signal(false);

// Workout data (keyed by date)
export const workoutPlans = signal({});  // { "2026-02-02": {...plan...} }
export const workoutLogs = signal({});   // { "2026-02-02": {...log...} }

// Sync metadata
export const syncMetadata = signal({
    clientId: null,
    lastServerSyncTime: null,
    dirtyDates: [],  // Array of dates with unsaved logs
    dirtyDateGenerations: {}  // { "YYYY-MM-DD": number } — incremented on each modification
});

// Server-driven sync window boundary (set on each sync response)
export const earliestDate = signal(null);

// UI state
export const syncStatus = signal('gray');  // 'green' | 'red' | 'gray'
export const migraineProtocolActive = signal(false);

// ==================== Client ID ====================

async function getClientId() {
    let clientId = await storage.getItem(KEYS.CLIENT_ID);
    if (!clientId) {
        clientId = generateId();
        await storage.setItem(KEYS.CLIENT_ID, clientId);
    }
    return clientId;
}

// ==================== Initialization ====================

export async function initializeStore() {
    isLoading.value = true;

    try {
        const [metadata, plans, logs, clientId] = await Promise.all([
            storage.getItem(KEYS.METADATA),
            storage.getItem(KEYS.PLANS),
            storage.getItem(KEYS.LOGS),
            getClientId()
        ]);

        batch(() => {
            syncMetadata.value = {
                clientId,
                lastServerSyncTime: metadata?.lastServerSyncTime || null,
                dirtyDates: metadata?.dirtyDates || [],
                dirtyDateGenerations: metadata?.dirtyDateGenerations || {}
            };
            earliestDate.value = metadata?.earliestDate || null;
            workoutPlans.value = plans || {};
            workoutLogs.value = logs || {};
        });

        updateSyncStatus();

        // Start auto-sync scheduler
        if (navigator.onLine) {
            scheduler.requestSync();
        }
        scheduler.start();
    } catch (error) {
        console.error('Failed to initialize store:', error);
        showNotification({
            type: 'error',
            title: 'Initialization Error',
            message: 'Failed to load data from storage'
        });
    } finally {
        isLoading.value = false;
    }
}

// ==================== Data Access ====================

export function getPlanForDate(date) {
    return workoutPlans.value[date] || null;
}

export function getLogForDate(date) {
    return workoutLogs.value[date] || null;
}

// ==================== Log Updates ====================

export function updateLog(date, exerciseId, data) {
    const logs = deepClone(workoutLogs.value);

    if (!logs[date]) {
        logs[date] = {
            session_feedback: {},
            _lastModifiedAt: getUtcNow(),
            _lastModifiedBy: syncMetadata.value.clientId
        };
    }

    logs[date][exerciseId] = {
        ...logs[date][exerciseId],
        ...data
    };
    logs[date]._lastModifiedAt = getUtcNow();
    logs[date]._lastModifiedBy = syncMetadata.value.clientId;

    workoutLogs.value = logs;
    markDateDirty(date);
    saveLogs();
    scheduler.scheduleUpload();
}

export function updateSessionFeedback(date, feedback) {
    const logs = deepClone(workoutLogs.value);

    if (!logs[date]) {
        logs[date] = {
            session_feedback: {},
            _lastModifiedAt: getUtcNow(),
            _lastModifiedBy: syncMetadata.value.clientId
        };
    }

    logs[date].session_feedback = {
        ...logs[date].session_feedback,
        ...feedback
    };
    logs[date]._lastModifiedAt = getUtcNow();

    workoutLogs.value = logs;
    markDateDirty(date);
    saveLogs();
    scheduler.scheduleUpload();
}

function markDateDirty(date) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyDates.includes(date)) {
        meta.dirtyDates = [...meta.dirtyDates, date];
    }
    // Always increment generation (detects re-modifications during sync)
    const gens = { ...meta.dirtyDateGenerations };
    gens[date] = (gens[date] || 0) + 1;
    meta.dirtyDateGenerations = gens;
    syncMetadata.value = meta;
    saveMetadata();
    updateSyncStatus();
}

function clearAppliedDirtyDates(appliedDates, snapshotGens) {
    const meta = syncMetadata.value;
    const next = nextDirtyAfterApply(
        appliedDates, snapshotGens, meta.dirtyDates, meta.dirtyDateGenerations,
    );
    syncMetadata.value = {
        ...meta,
        dirtyDates: next.dirtyDates,
        dirtyDateGenerations: next.dirtyDateGenerations,
    };
    saveMetadata();
    updateSyncStatus();
}

// ==================== Persistence ====================

async function savePlans() {
    await storage.setItem(KEYS.PLANS, workoutPlans.value);
}

async function saveLogs() {
    await storage.setItem(KEYS.LOGS, workoutLogs.value);
}

async function saveMetadata() {
    await storage.setItem(KEYS.METADATA, {
        lastServerSyncTime: syncMetadata.value.lastServerSyncTime,
        dirtyDates: syncMetadata.value.dirtyDates,
        dirtyDateGenerations: syncMetadata.value.dirtyDateGenerations,
        earliestDate: earliestDate.value
    });
}

// ==================== Sync Status ====================

function updateSyncStatus() {
    if (!navigator.onLine) {
        syncStatus.value = 'gray';
    } else if (syncMetadata.value.dirtyDates.length > 0) {
        syncStatus.value = 'red';
    } else {
        syncStatus.value = 'green';
    }
}

// ==================== Auto-Sync Scheduler ====================

let lastKnownPlansVersion = null;

async function pollCheckFn() {
    // Lightweight check: only trigger full sync if plans version changed or we have dirty data
    if (syncMetadata.value.dirtyDates.length > 0) return true;
    try {
        const resp = await fetch(`${API_BASE}/plans-version`, { cache: 'no-store' });
        if (!resp.ok) return false;
        const { version } = await resp.json();
        if (version && version !== lastKnownPlansVersion) {
            lastKnownPlansVersion = version;
            return true;
        }
        return false;
    } catch {
        return false;
    }
}

export const scheduler = new SyncScheduler({
    name: 'coach',
    syncFn: triggerSync,
    getIsSyncing: () => isSyncing.value,
    getHasDirtyData: () => syncMetadata.value.dirtyDates.length > 0,
    pollCheckFn
});

// Pure content predicates and dirty-state transitions live in ./sync-logic.js
// (unit-tested in test/js/coach-sync-logic.test.js).

// ==================== Sync ====================

async function triggerSync() {
    if (!navigator.onLine) {
        syncStatus.value = 'gray';
        return { success: false, reason: 'offline' };
    }

    if (isSyncing.value) {
        return { success: false, reason: 'already syncing' };
    }

    isSyncing.value = true;

    try {
        const meta = syncMetadata.value;
        const clientId = meta.clientId;
        debugLog('coach-sync', 'sync start', { clientId, dirtyDates: meta.dirtyDates.length, lastServerSyncTime: meta.lastServerSyncTime });

        // Snapshot generations to detect re-modifications during sync
        const snapshotGens = { ...meta.dirtyDateGenerations };
        let uploadedDates = [];

        // Step 1: Upload dirty logs first
        if (meta.dirtyDates.length > 0) {
            // Pure: include each dirty date whose local log carries uploadable
            // content (exercise data or feedback); truly-empty/missing logs are
            // skipped and so stay dirty.
            const { logsToUpload } = selectLogsToUpload(meta.dirtyDates, workoutLogs.value);

            debugLog('coach-sync', 'upload attempt', { dates: meta.dirtyDates, logCount: Object.keys(logsToUpload).length });

            const uploadResponse = await fetch(`${API_BASE}/sync`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    clientId,
                    logs: logsToUpload
                })
            });

            if (!uploadResponse.ok) {
                debugLog('coach-sync', 'upload failure', { status: uploadResponse.status });
                throw new Error('Failed to upload logs');
            }

            const uploadResult = await uploadResponse.json();

            // R3: the server reconciled each uploaded date per-record and returned
            // the merged day in `results[date]` (carrying fresh per-record tokens).
            // Adopt it for each date not re-modified mid-sync (generation check) —
            // one mechanism covering accepted upserts AND server-wins records.
            const results = uploadResult.results || {};
            workoutLogs.value = adoptUploadResults(
                workoutLogs.value, results, snapshotGens, meta.dirtyDateGenerations,
            );

            debugLog('coach-sync', 'upload success', { dates: Object.keys(results).length });
            // Clear dirty only for the dates actually sent. A date skipped above
            // (empty log) must stay dirty rather than be silently cleared.
            uploadedDates = Object.keys(logsToUpload);
        }

        // Step 2: Download new plans and logs
        const params = new URLSearchParams({ client_id: clientId });
        if (meta.lastServerSyncTime) {
            params.append('last_sync_time', meta.lastServerSyncTime);
        }

        debugLog('coach-sync', 'download attempt', { params: params.toString() });

        const downloadResponse = await fetch(`${API_BASE}/sync?${params}`, { cache: 'no-store' });
        if (!downloadResponse.ok) {
            throw new Error('Failed to download data');
        }

        const data = await downloadResponse.json();
        debugLog('coach-sync', 'download success', { plans: Object.keys(data.plans).length, logs: Object.keys(data.logs).length, serverTime: data.serverTime });

        // Step 3: Apply server data
        batch(() => {
            // Remove plans the server has marked as deleted
            if (data.deletedPlanDates?.length > 0) {
                const plans = { ...workoutPlans.value };
                for (const date of data.deletedPlanDates) {
                    delete plans[date];
                }
                workoutPlans.value = plans;
            }

            // Plans: server is authoritative, overwrite local
            if (Object.keys(data.plans).length > 0) {
                workoutPlans.value = {
                    ...workoutPlans.value,
                    ...data.plans
                };
                // Track the latest plan version for change detection
                const maxVersion = maxPlanVersion(data.plans, lastKnownPlansVersion);
                if (maxVersion) lastKnownPlansVersion = maxVersion;
            }

            // Logs: merge (server data is from other devices)
            // Skip dates that are still dirty (uploaded dates stay dirty until
            // generation check below; re-modified dates stay dirty permanently)
            if (Object.keys(data.logs).length > 0) {
                const currentDirty = new Set(syncMetadata.value.dirtyDates);
                const currentLogs = { ...workoutLogs.value };
                for (const [date, serverLog] of Object.entries(data.logs)) {
                    if (!currentDirty.has(date)) {
                        currentLogs[date] = serverLog;
                    }
                }
                workoutLogs.value = currentLogs;
            }

            // Update sync time and earliest date
            if (data.earliestDate) {
                earliestDate.value = data.earliestDate;
            }
            syncMetadata.value = {
                ...syncMetadata.value,
                lastServerSyncTime: data.serverTime
            };

            // Prune plans and logs older than the server's sync window
            if (earliestDate.value) {
                const cutoff = earliestDate.value;
                workoutPlans.value = pruneOlderThan(workoutPlans.value, cutoff);
                workoutLogs.value = pruneOlderThan(workoutLogs.value, cutoff);
            }
        });

        // Clear dirty state only for uploaded dates whose generation hasn't changed
        if (uploadedDates.length > 0) {
            clearAppliedDirtyDates(uploadedDates, snapshotGens);
        }

        await Promise.all([savePlans(), saveLogs(), saveMetadata()]);
        debugLog('coach-sync', 'server data applied', { plansUpdated: Object.keys(data.plans).length, logsMerged: Object.keys(data.logs).length });

        syncStatus.value = 'green';
        return { success: true };

    } catch (error) {
        console.error('Sync failed:', error);
        debugLog('coach-sync', 'sync error', { error: error.message });
        syncStatus.value = 'red';
        return { success: false, reason: error.message, error };
    } finally {
        isSyncing.value = false;
    }
}

// ==================== Force Sync ====================

export async function forceSync() {
    if (!navigator.onLine) {
        return { success: false, error: 'offline' };
    }
    if (isSyncing.value) {
        return { success: false, error: 'sync already in progress' };
    }

    isSyncing.value = true;

    try {
        const clientId = syncMetadata.value.clientId;
        debugLog('coach-sync', 'force sync start', { clientId });

        // Snapshot generations to detect re-modifications during sync
        const snapshotGens = { ...syncMetadata.value.dirtyDateGenerations };

        // Phase 1: Download full server state (no last_sync_time)
        const response = await fetch(`${API_BASE}/sync?client_id=${clientId}`, { cache: 'no-store' });
        if (!response.ok) throw new Error('Failed to download server data');
        const data = await response.json();

        // Phase 2: Resolve local-vs-server per date (client-side reconcile).
        const resolved = resolveForceSyncLogs(
            workoutLogs.value, data.logs, data.earliestDate,
        );
        const { uploadLogs, counts } = resolved;
        let mergedLogs = resolved.mergedLogs;  // reassigned by the R1 token handling below
        const { uploaded, accepted, skipped } = counts;

        // Phase 3: Upload client-winning logs
        const uploadedDates = Object.keys(uploadLogs);
        if (uploadedDates.length > 0) {
            const uploadResponse = await fetch(`${API_BASE}/sync`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ clientId, logs: uploadLogs })
            });
            if (!uploadResponse.ok) throw new Error('Failed to upload logs');

            const uploadResult = await uploadResponse.json();
            // R3: the server returns the reconciled day per uploaded date in
            // `results` (merged per-record — forced overwrites applied, any
            // server-only records preserved, conflicts server-won). Adopt it over
            // the optimistic local merge so mergedLogs reflects the real outcome.
            mergedLogs = adoptUploadResults(
                mergedLogs, uploadResult.results || {}, snapshotGens,
                syncMetadata.value.dirtyDateGenerations,
            );
        }

        // Apply merged state locally
        batch(() => {
            // Plans: server-authoritative
            workoutPlans.value = { ...data.plans };

            // Track latest plan version for polling
            const maxVersion = maxPlanVersion(data.plans, lastKnownPlansVersion);
            if (maxVersion) lastKnownPlansVersion = maxVersion;

            // Logs: merged result
            workoutLogs.value = mergedLogs;

            // Update earliest date from server
            if (data.earliestDate) {
                earliestDate.value = data.earliestDate;
            }

            syncMetadata.value = {
                ...syncMetadata.value,
                lastServerSyncTime: data.serverTime
            };
        });

        // Clear dirty state only for dates whose generation hasn't changed
        const allApplied = [...new Set([...uploadedDates, ...Object.keys(data.logs)])];
        clearAppliedDirtyDates(allApplied, snapshotGens);

        await Promise.all([savePlans(), saveLogs(), saveMetadata()]);
        syncStatus.value = 'green';

        debugLog('coach-sync', 'force sync complete', { uploaded, accepted, skipped });
        scheduler.resetRetry();
        return { success: true, uploaded, accepted, skipped };

    } catch (error) {
        console.error('Force sync failed:', error);
        debugLog('coach-sync', 'force sync error', { error: error.message });
        syncStatus.value = 'red';
        return { success: false, error: error.message };
    } finally {
        isSyncing.value = false;
    }
}
