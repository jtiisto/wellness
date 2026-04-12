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
    const meta = { ...syncMetadata.value };
    const appliedSet = new Set(appliedDates);

    meta.dirtyDates = meta.dirtyDates.filter(date => {
        if (!appliedSet.has(date)) return true;  // not applied, keep dirty
        if (snapshotGens && meta.dirtyDateGenerations[date] !== snapshotGens[date]) {
            return true;  // re-modified during sync, keep dirty
        }
        return false;  // applied and not re-modified, clear
    });

    // Clean up generation counters for dates actually cleared
    const gens = { ...meta.dirtyDateGenerations };
    const remaining = new Set(meta.dirtyDates);
    for (const date of appliedDates) {
        if (!remaining.has(date)) delete gens[date];
    }
    meta.dirtyDateGenerations = gens;

    syncMetadata.value = meta;
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
            const logsToUpload = {};
            meta.dirtyDates.forEach(date => {
                const log = workoutLogs.value[date];
                if (!log) {
                    debugLog('coach-sync', 'skip upload: no local data', { date });
                    return;
                }
                // Guard: only upload logs that contain actual exercise data
                const hasContent = Object.entries(log).some(([key, val]) => {
                    if (['_lastModifiedAt', '_lastModifiedBy', 'session_feedback'].includes(key)) return false;
                    if (typeof val !== 'object' || val === null) return false;
                    return val.completed || val.sets?.length > 0 || val.completed_items?.length > 0 || val.duration_min != null;
                });
                if (!hasContent) {
                    debugLog('coach-sync', 'skip upload: no exercise data', { date });
                    return;
                }
                logsToUpload[date] = log;
            });

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

            // Remove rejected dates from dirtyDates — server has newer data
            if (uploadResult.rejectedLogs?.length > 0) {
                debugLog('coach-sync', 'server rejected stale logs', { rejected: uploadResult.rejectedLogs });
                meta.dirtyDates = meta.dirtyDates.filter(d => !uploadResult.rejectedLogs.includes(d));
                for (const d of uploadResult.rejectedLogs) {
                    delete meta.dirtyDateGenerations[d];
                }
            }

            debugLog('coach-sync', 'upload success', { applied: uploadResult.appliedLogs?.length, rejected: uploadResult.rejectedLogs?.length });
            uploadedDates = [...meta.dirtyDates];
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
                let maxVersion = lastKnownPlansVersion;
                for (const plan of Object.values(data.plans)) {
                    if (plan._lastModified && (!maxVersion || plan._lastModified > maxVersion)) {
                        maxVersion = plan._lastModified;
                    }
                }
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
                const prunedPlans = {};
                for (const [date, plan] of Object.entries(workoutPlans.value)) {
                    if (date >= cutoff) prunedPlans[date] = plan;
                }
                workoutPlans.value = prunedPlans;

                const prunedLogs = {};
                for (const [date, log] of Object.entries(workoutLogs.value)) {
                    if (date >= cutoff) prunedLogs[date] = log;
                }
                workoutLogs.value = prunedLogs;
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

        // Phase 2: Compare logs by timestamp
        const uploadLogs = {};
        const mergedLogs = {};
        let uploaded = 0, accepted = 0, skipped = 0;

        const allDates = new Set([
            ...Object.keys(workoutLogs.value),
            ...Object.keys(data.logs)
        ]);

        for (const date of allDates) {
            const localLog = workoutLogs.value[date];
            const serverLog = data.logs[date];

            // Skip local-only logs outside server's sync window
            if (!serverLog && data.earliestDate && date < data.earliestDate) {
                mergedLogs[date] = localLog;
                skipped++;
                continue;
            }

            if (localLog && serverLog) {
                const localTs = localLog._lastModifiedAt || '';
                const serverTs = serverLog._lastModified || '';
                if (localTs > serverTs) {
                    uploadLogs[date] = localLog;
                    mergedLogs[date] = localLog;
                    uploaded++;
                } else if (serverTs > localTs) {
                    mergedLogs[date] = serverLog;
                    accepted++;
                } else {
                    mergedLogs[date] = localLog;
                    skipped++;
                }
            } else if (localLog) {
                uploadLogs[date] = localLog;
                mergedLogs[date] = localLog;
                uploaded++;
            } else {
                mergedLogs[date] = serverLog;
                accepted++;
            }
        }

        // Phase 3: Upload client-winning logs
        const uploadedDates = Object.keys(uploadLogs);
        if (uploadedDates.length > 0) {
            const uploadResponse = await fetch(`${API_BASE}/sync`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ clientId, logs: uploadLogs })
            });
            if (!uploadResponse.ok) throw new Error('Failed to upload logs');
        }

        // Apply merged state locally
        batch(() => {
            // Plans: server-authoritative
            workoutPlans.value = { ...data.plans };

            // Track latest plan version for polling
            let maxVersion = lastKnownPlansVersion;
            for (const plan of Object.values(data.plans)) {
                if (plan._lastModified && (!maxVersion || plan._lastModified > maxVersion)) {
                    maxVersion = plan._lastModified;
                }
            }
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
