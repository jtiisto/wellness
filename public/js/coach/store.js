/**
 * Coach Store - Signals, LocalForage, and Sync Logic
 * Workout plans are server-authoritative, logs use last-write-wins
 */
import { signal, batch } from '@preact/signals';
import localforage from 'localforage';
import { getToday, getUtcNow, generateId, deepClone } from '../shared/utils.js';
import { showNotification } from '../shared/notifications.js';

const API_BASE = '/api/coach';

// Configure LocalForage
localforage.config({
    name: 'CoachApp',
    storeName: 'coach_data'
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
    dirtyDates: []  // Array of dates with unsaved logs
});

// UI state
export const syncStatus = signal('gray');  // 'green' | 'red' | 'gray'
export const migraineProtocolActive = signal(false);

// ==================== Client ID ====================

async function getClientId() {
    let clientId = await localforage.getItem(KEYS.CLIENT_ID);
    if (!clientId) {
        clientId = generateId();
        await localforage.setItem(KEYS.CLIENT_ID, clientId);
    }
    return clientId;
}

// ==================== Initialization ====================

export async function initializeStore() {
    isLoading.value = true;

    try {
        const [metadata, plans, logs, clientId] = await Promise.all([
            localforage.getItem(KEYS.METADATA),
            localforage.getItem(KEYS.PLANS),
            localforage.getItem(KEYS.LOGS),
            getClientId()
        ]);

        batch(() => {
            syncMetadata.value = {
                clientId,
                lastServerSyncTime: metadata?.lastServerSyncTime || null,
                dirtyDates: metadata?.dirtyDates || []
            };
            workoutPlans.value = plans || {};
            workoutLogs.value = logs || {};
        });

        updateSyncStatus();

        // Try to sync on startup
        if (navigator.onLine) {
            await triggerSync();
        }
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
}

function markDateDirty(date) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyDates.includes(date)) {
        meta.dirtyDates = [...meta.dirtyDates, date];
        syncMetadata.value = meta;
        saveMetadata();
        updateSyncStatus();
    }
}

// ==================== Persistence ====================

async function savePlans() {
    await localforage.setItem(KEYS.PLANS, workoutPlans.value);
}

async function saveLogs() {
    await localforage.setItem(KEYS.LOGS, workoutLogs.value);
}

async function saveMetadata() {
    await localforage.setItem(KEYS.METADATA, {
        lastServerSyncTime: syncMetadata.value.lastServerSyncTime,
        dirtyDates: syncMetadata.value.dirtyDates
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

// Listen for online/offline events
if (typeof window !== 'undefined') {
    window.addEventListener('online', () => {
        updateSyncStatus();
        triggerSync();
    });
    window.addEventListener('offline', () => {
        updateSyncStatus();
    });

    // Re-sync when the app regains focus (e.g., user switches back from
    // another tab/app after plans were updated via MCP on the backend)
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && navigator.onLine) {
            triggerSync();
        }
    });
}

// ==================== Sync ====================

export async function triggerSync() {
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

        // Step 1: Upload dirty logs first
        if (meta.dirtyDates.length > 0) {
            const logsToUpload = {};
            meta.dirtyDates.forEach(date => {
                if (workoutLogs.value[date]) {
                    logsToUpload[date] = workoutLogs.value[date];
                }
            });

            const uploadResponse = await fetch(`${API_BASE}/sync`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    clientId,
                    logs: logsToUpload
                })
            });

            if (!uploadResponse.ok) {
                throw new Error('Failed to upload logs');
            }

            // Clear dirty state after successful upload
            syncMetadata.value = {
                ...meta,
                dirtyDates: []
            };
            await saveMetadata();
        }

        // Step 2: Download new plans and logs
        const params = new URLSearchParams({ client_id: clientId });
        if (meta.lastServerSyncTime) {
            params.append('last_sync_time', meta.lastServerSyncTime);
        }

        const downloadResponse = await fetch(`${API_BASE}/sync?${params}`);
        if (!downloadResponse.ok) {
            throw new Error('Failed to download data');
        }

        const data = await downloadResponse.json();

        // Step 3: Apply server data
        batch(() => {
            // Plans: server is authoritative, overwrite local
            if (Object.keys(data.plans).length > 0) {
                workoutPlans.value = {
                    ...workoutPlans.value,
                    ...data.plans
                };
            }

            // Logs: merge (server data is from other devices)
            if (Object.keys(data.logs).length > 0) {
                const currentLogs = { ...workoutLogs.value };
                for (const [date, serverLog] of Object.entries(data.logs)) {
                    // Only apply if we don't have local changes
                    if (!syncMetadata.value.dirtyDates.includes(date)) {
                        currentLogs[date] = serverLog;
                    }
                }
                workoutLogs.value = currentLogs;
            }

            // Update sync time
            syncMetadata.value = {
                ...syncMetadata.value,
                lastServerSyncTime: data.serverTime
            };
        });

        await Promise.all([savePlans(), saveLogs(), saveMetadata()]);

        syncStatus.value = 'green';
        return { success: true };

    } catch (error) {
        console.error('Sync failed:', error);
        syncStatus.value = 'red';
        showNotification({
            type: 'error',
            title: 'Sync Failed',
            message: error.message
        });
        return { success: false, reason: error.message };
    } finally {
        isSyncing.value = false;
    }
}
