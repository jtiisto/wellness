/**
 * Journal Store - Signals, LocalForage, and Enhanced Sync Logic
 * With per-record versioning and multi-client conflict detection
 */
import { signal, effect, batch } from '@preact/signals';
import localforage from 'localforage';
import { getToday, getUtcNow, generateId } from '../shared/utils.js';
import { compareTimestamps, isWithinLastNDays } from './utils.js';
import { showNotification } from '../shared/notifications.js';
import { log as debugLog } from '../shared/debug-log.js';

// Configure LocalForage
localforage.config({
    name: 'JournalApp',
    storeName: 'journal_data'
});

// Storage keys
const KEYS = {
    METADATA: 'app_metadata',
    CONFIG: 'tracker_config',
    LOGS: 'daily_logs',
    CLIENT_ID: 'client_id',
    EXPANDED_CATEGORIES: 'expanded_categories'
};

// ==================== Signals ====================

// App state
export const currentView = signal('home'); // 'home' | 'config' | 'conflicts'
export const selectedDate = signal(getToday());
export const isLoading = signal(true);
export const isSyncing = signal(false);

// Tracker configuration (with version info)
export const trackerConfig = signal([]);

// Daily logs - keyed by date string (with version info)
export const dailyLogs = signal({});

// Sync metadata
export const syncMetadata = signal({
    clientId: null,
    lastServerSyncTime: null,
    dirtyTrackers: [],  // Array of tracker IDs with local changes
    dirtyEntries: [],   // Array of "date|trackerId" strings with local changes
    dirtyConfig: false  // Legacy flag for backward compatibility
});

// Sync status indicator: 'green' | 'red' | 'yellow' | 'gray'
// yellow = has conflicts to resolve
export const syncStatus = signal('gray');

// Pending conflicts that need user resolution
export const pendingConflicts = signal([]);

// Edit state for config screen
export const editingTracker = signal(null);

// Expanded categories state (categories are collapsed by default)
export const expandedCategories = signal(new Set());

// ==================== Expanded Categories ====================

export function toggleCategoryExpanded(category) {
    const current = new Set(expandedCategories.value);
    if (current.has(category)) {
        current.delete(category);
    } else {
        current.add(category);
    }
    expandedCategories.value = current;
    saveExpandedCategories();
}

async function saveExpandedCategories() {
    await localforage.setItem(KEYS.EXPANDED_CATEGORIES, Array.from(expandedCategories.value));
}

// ==================== Persistence ====================

// Get or create client ID
async function getClientId() {
    let clientId = await localforage.getItem(KEYS.CLIENT_ID);
    if (!clientId) {
        clientId = generateId();
        await localforage.setItem(KEYS.CLIENT_ID, clientId);
    }
    return clientId;
}

// Load all data from LocalForage on startup
export async function initializeStore() {
    try {
        isLoading.value = true;

        const [metadata, config, logs, clientId, expanded] = await Promise.all([
            localforage.getItem(KEYS.METADATA),
            localforage.getItem(KEYS.CONFIG),
            localforage.getItem(KEYS.LOGS),
            getClientId(),
            localforage.getItem(KEYS.EXPANDED_CATEGORIES)
        ]);

        batch(() => {
            syncMetadata.value = {
                clientId,
                lastServerSyncTime: metadata?.lastServerSyncTime || null,
                dirtyTrackers: metadata?.dirtyTrackers || [],
                dirtyEntries: metadata?.dirtyEntries || [],
                dirtyConfig: metadata?.dirtyConfig || false
            };

            trackerConfig.value = config || [];
            dailyLogs.value = logs || {};
            expandedCategories.value = new Set(expanded || []);
        });

        updateSyncStatus();
    } catch (error) {
        console.error('Failed to initialize store:', error);
    } finally {
        isLoading.value = false;
    }
}

// Save metadata to LocalForage
async function saveMetadata() {
    const meta = syncMetadata.value;
    await localforage.setItem(KEYS.METADATA, {
        lastServerSyncTime: meta.lastServerSyncTime,
        dirtyTrackers: meta.dirtyTrackers,
        dirtyEntries: meta.dirtyEntries,
        dirtyConfig: meta.dirtyConfig
    });
}

// Save config to LocalForage
async function saveConfig() {
    await localforage.setItem(KEYS.CONFIG, trackerConfig.value);
}

// Save logs to LocalForage
async function saveLogs() {
    await localforage.setItem(KEYS.LOGS, dailyLogs.value);
}

// ==================== Tracker Config Actions ====================

export function addTracker(tracker) {
    // Add version info to new tracker
    const trackerWithVersion = {
        ...tracker,
        _version: 0,  // Will be set to 1 on first sync
        _baseVersion: 0,
        _lastModifiedAt: getUtcNow(),
        _lastModifiedBy: syncMetadata.value.clientId
    };

    trackerConfig.value = [...trackerConfig.value, trackerWithVersion];
    markTrackerDirty(tracker.id);
    saveConfig();
}

export function updateTracker(trackerId, updates) {
    trackerConfig.value = trackerConfig.value.map(t => {
        if (t.id === trackerId) {
            return {
                ...t,
                ...updates,
                _lastModifiedAt: getUtcNow(),
                _lastModifiedBy: syncMetadata.value.clientId
            };
        }
        return t;
    });
    markTrackerDirty(trackerId);
    saveConfig();
}

export function deleteTracker(trackerId) {
    // Mark as deleted rather than removing, for sync purposes
    trackerConfig.value = trackerConfig.value.map(t => {
        if (t.id === trackerId) {
            return {
                ...t,
                _deleted: true,
                _lastModifiedAt: getUtcNow(),
                _lastModifiedBy: syncMetadata.value.clientId
            };
        }
        return t;
    });
    markTrackerDirty(trackerId);
    saveConfig();
}

// Actually remove deleted trackers after successful sync
function pruneDeletedTrackers() {
    trackerConfig.value = trackerConfig.value.filter(t => !t._deleted);
    saveConfig();
}

// ==================== Daily Log Actions ====================

export function updateEntry(date, trackerId, data) {
    const logs = { ...dailyLogs.value };
    const entryKey = `${date}|${trackerId}`;

    if (!logs[date]) {
        logs[date] = {};
    }

    const existingEntry = logs[date][trackerId] || {};

    logs[date] = {
        ...logs[date],
        [trackerId]: {
            ...existingEntry,
            ...data,
            _lastModifiedAt: getUtcNow(),
            _lastModifiedBy: syncMetadata.value.clientId
        }
    };

    dailyLogs.value = logs;
    markEntryDirty(entryKey);
    saveLogs();
}

export function getEntry(date, trackerId) {
    return dailyLogs.value[date]?.[trackerId] || null;
}

// ==================== Dirty State Management ====================

function markTrackerDirty(trackerId) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyTrackers.includes(trackerId)) {
        meta.dirtyTrackers = [...meta.dirtyTrackers, trackerId];
        meta.dirtyConfig = true;  // Legacy compatibility
        syncMetadata.value = meta;
        saveMetadata();
        updateSyncStatus();
    }
}

function markEntryDirty(entryKey) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyEntries.includes(entryKey)) {
        meta.dirtyEntries = [...meta.dirtyEntries, entryKey];
        syncMetadata.value = meta;
        saveMetadata();
        updateSyncStatus();
    }
}

function clearDirtyState(appliedTrackerIds = [], appliedEntryKeys = []) {
    const meta = { ...syncMetadata.value };

    // Only clear trackers that were successfully applied
    meta.dirtyTrackers = meta.dirtyTrackers.filter(id => !appliedTrackerIds.includes(id));

    // Only clear entries that were successfully applied
    meta.dirtyEntries = meta.dirtyEntries.filter(key => !appliedEntryKeys.includes(key));

    meta.dirtyConfig = meta.dirtyTrackers.length > 0;

    syncMetadata.value = meta;
    saveMetadata();
    updateSyncStatus();
}

function updateSyncStatus() {
    const meta = syncMetadata.value;
    const hasConflicts = pendingConflicts.value.length > 0;
    const hasDirtyData = meta.dirtyTrackers.length > 0 || meta.dirtyEntries.length > 0;

    if (hasConflicts) {
        syncStatus.value = 'yellow';
    } else if (hasDirtyData) {
        syncStatus.value = 'red';
    } else if (meta.lastServerSyncTime) {
        syncStatus.value = 'green';
    } else {
        syncStatus.value = 'gray';
    }
}

// ==================== Auto-Merge Logic ====================

function tryAutoMergeEntry(localEntry, serverEntry, baseEntry) {
    // If we don't have base values, we can't auto-merge
    if (!baseEntry) {
        return null;
    }

    const localChangedValue = localEntry.value !== baseEntry.value;
    const localChangedCompleted = localEntry.completed !== baseEntry.completed;
    const serverChangedValue = serverEntry.value !== baseEntry.value;
    const serverChangedCompleted = serverEntry.completed !== baseEntry.completed;

    // Non-overlapping changes can merge
    if (localChangedValue && !serverChangedValue &&
        serverChangedCompleted && !localChangedCompleted) {
        return {
            value: localEntry.value,
            completed: serverEntry.completed,
            _merged: true
        };
    }
    if (localChangedCompleted && !serverChangedCompleted &&
        serverChangedValue && !localChangedValue) {
        return {
            value: serverEntry.value,
            completed: localEntry.completed,
            _merged: true
        };
    }

    // Both changed same field - can't auto-merge
    return null;
}

// ==================== Conflict Detection ====================

function detectLocalConflicts(serverChanges) {
    const conflicts = [];
    const meta = syncMetadata.value;

    // Check tracker conflicts
    for (const serverTracker of (serverChanges.config || [])) {
        const localTracker = trackerConfig.value.find(t => t.id === serverTracker.id);

        if (!localTracker) continue;

        const isLocalDirty = meta.dirtyTrackers.includes(serverTracker.id);
        const localBaseVersion = localTracker._baseVersion || 0;
        const serverVersion = serverTracker._version || 1;

        if (isLocalDirty && serverVersion > localBaseVersion) {
            conflicts.push({
                type: 'tracker',
                id: serverTracker.id,
                local: localTracker,
                server: serverTracker,
                autoResolvable: false
            });
        }
    }

    // Check entry conflicts
    for (const [date, serverEntries] of Object.entries(serverChanges.days || {})) {
        const localDay = dailyLogs.value[date] || {};

        for (const [trackerId, serverEntry] of Object.entries(serverEntries)) {
            const entryKey = `${date}|${trackerId}`;
            const localEntry = localDay[trackerId];

            if (!localEntry) continue;

            const isLocalDirty = meta.dirtyEntries.includes(entryKey);
            const localBaseVersion = localEntry._baseVersion || 0;
            const serverVersion = serverEntry._version || 1;

            if (isLocalDirty && serverVersion > localBaseVersion) {
                // Try auto-merge
                const baseEntry = {
                    value: localEntry._baseValue,
                    completed: localEntry._baseCompleted
                };
                const merged = tryAutoMergeEntry(localEntry, serverEntry, baseEntry);

                conflicts.push({
                    type: 'entry',
                    id: entryKey,
                    date,
                    trackerId,
                    local: localEntry,
                    server: serverEntry,
                    merged,
                    autoResolvable: merged !== null
                });
            }
        }
    }

    return conflicts;
}

// ==================== Sync Logic ====================

const API_BASE = '/api/journal/sync';

export async function triggerSync() {
    // Step 1: Network check
    if (!navigator.onLine) {
        syncStatus.value = 'gray';
        return { success: false, reason: 'offline' };
    }

    isSyncing.value = true;

    try {
        const meta = syncMetadata.value;
        const clientId = meta.clientId;
        debugLog('journal-sync', 'sync start', { clientId, dirtyTrackers: meta.dirtyTrackers.length, dirtyEntries: meta.dirtyEntries.length, lastServerSyncTime: meta.lastServerSyncTime });

        // Step 2: Fetch server changes
        let serverData;
        if (meta.lastServerSyncTime) {
            debugLog('journal-sync', 'delta sync', { since: meta.lastServerSyncTime });
            // Delta sync - only get changes since last sync
            const deltaResponse = await fetch(
                `${API_BASE}/delta?since=${encodeURIComponent(meta.lastServerSyncTime)}&client_id=${clientId}`
            );
            if (!deltaResponse.ok) {
                throw new Error('Failed to fetch server changes');
            }
            serverData = await deltaResponse.json();
        } else {
            debugLog('journal-sync', 'full sync (first time)');
            // Full sync for first time
            const fullResponse = await fetch(`${API_BASE}/full`);
            if (!fullResponse.ok) {
                throw new Error('Failed to fetch server data');
            }
            serverData = await fullResponse.json();
        }

        debugLog('journal-sync', 'server data received', { configCount: serverData.config ? Object.keys(serverData.config).length : 0, daysCount: serverData.days ? Object.keys(serverData.days).length : 0 });

        // Step 3: Detect conflicts locally
        const localConflicts = detectLocalConflicts(serverData);
        const autoResolvable = localConflicts.filter(c => c.autoResolvable);
        const needsUserInput = localConflicts.filter(c => !c.autoResolvable);

        debugLog('journal-sync', 'conflict detection', { autoResolvable: autoResolvable.length, needsUserInput: needsUserInput.length });

        // Step 4: Apply auto-resolvable conflicts
        if (autoResolvable.length > 0) {
            applyAutoMergedConflicts(autoResolvable);
            debugLog('journal-sync', 'auto-merge applied', { count: autoResolvable.length });
            showNotification({
                type: 'info',
                title: 'Data Merged',
                message: `${autoResolvable.length} entries were auto-merged from another device.`,
                duration: 4000
            });
        }

        // Step 5: Handle server data that doesn't conflict
        await applyServerChanges(serverData, localConflicts);

        // Step 6: Check for user-input conflicts
        if (needsUserInput.length > 0) {
            pendingConflicts.value = needsUserInput;
            updateSyncStatus();

            showNotification({
                type: 'warning',
                title: 'Sync Conflict',
                message: `${needsUserInput.length} conflicts need your attention.`,
                action: {
                    label: 'Resolve',
                    handler: () => { currentView.value = 'conflicts'; }
                },
                duration: 0  // Don't auto-dismiss
            });

            return { success: false, reason: 'conflicts', conflicts: needsUserInput };
        }

        // Step 7: Upload local changes
        const hasLocalChanges = meta.dirtyTrackers.length > 0 || meta.dirtyEntries.length > 0;
        if (hasLocalChanges) {
            debugLog('journal-sync', 'upload attempt', { dirtyTrackers: meta.dirtyTrackers.length, dirtyEntries: meta.dirtyEntries.length });
            const uploadResult = await uploadToServer();
            if (!uploadResult.success) {
                debugLog('journal-sync', 'upload failure', { reason: uploadResult.reason });
                return uploadResult;
            }
            debugLog('journal-sync', 'upload success');
        }

        syncStatus.value = 'green';
        return { success: true };

    } catch (error) {
        console.error('Sync failed:', error);
        debugLog('journal-sync', 'sync error', { error: error.message });
        syncStatus.value = 'red';
        showNotification({
            type: 'error',
            title: 'Sync Failed',
            message: error.message,
            duration: 5000
        });
        return { success: false, reason: error.message };
    } finally {
        isSyncing.value = false;
    }
}

function applyAutoMergedConflicts(conflicts) {
    const logs = { ...dailyLogs.value };

    for (const conflict of conflicts) {
        if (conflict.type === 'entry' && conflict.merged) {
            const { date, trackerId, merged, server } = conflict;

            if (!logs[date]) {
                logs[date] = {};
            }

            logs[date][trackerId] = {
                ...logs[date][trackerId],
                value: merged.value,
                completed: merged.completed,
                _version: server._version,
                _baseVersion: server._version,
                _baseValue: merged.value,
                _baseCompleted: merged.completed,
                _merged: true
            };
        }
    }

    dailyLogs.value = logs;
    saveLogs();
}

async function applyServerChanges(serverData, conflicts) {
    const conflictTrackerIds = new Set(
        conflicts.filter(c => c.type === 'tracker').map(c => c.id)
    );
    const conflictEntryKeys = new Set(
        conflicts.filter(c => c.type === 'entry').map(c => c.id)
    );

    batch(() => {
        // Apply non-conflicting tracker changes
        const serverTrackerIds = new Set((serverData.config || []).map(t => t.id));
        const updatedConfig = [...trackerConfig.value];

        for (const serverTracker of (serverData.config || [])) {
            if (conflictTrackerIds.has(serverTracker.id)) continue;

            const localIndex = updatedConfig.findIndex(t => t.id === serverTracker.id);
            const trackerWithBase = {
                ...serverTracker,
                _baseVersion: serverTracker._version
            };

            if (localIndex >= 0) {
                updatedConfig[localIndex] = trackerWithBase;
            } else {
                updatedConfig.push(trackerWithBase);
            }
        }

        // Handle deleted trackers from server
        if (serverData.deletedTrackers) {
            for (const deletedId of serverData.deletedTrackers) {
                const idx = updatedConfig.findIndex(t => t.id === deletedId);
                if (idx >= 0) {
                    updatedConfig.splice(idx, 1);
                }
            }
        }

        trackerConfig.value = updatedConfig;

        // Apply non-conflicting entry changes
        const logs = { ...dailyLogs.value };

        for (const [date, serverEntries] of Object.entries(serverData.days || {})) {
            if (!logs[date]) {
                logs[date] = {};
            }

            for (const [trackerId, serverEntry] of Object.entries(serverEntries)) {
                const entryKey = `${date}|${trackerId}`;
                if (conflictEntryKeys.has(entryKey)) continue;

                logs[date][trackerId] = {
                    ...serverEntry,
                    _baseVersion: serverEntry._version,
                    _baseValue: serverEntry.value,
                    _baseCompleted: serverEntry.completed
                };
            }
        }

        dailyLogs.value = logs;

        // Update sync time
        syncMetadata.value = {
            ...syncMetadata.value,
            lastServerSyncTime: serverData.serverTime || getUtcNow()
        };
    });

    await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);
}

async function uploadToServer() {
    const meta = syncMetadata.value;

    // Construct payload with version info
    const payload = {
        clientId: meta.clientId,
        config: [],
        days: {}
    };

    // Include dirty trackers
    const appliedTrackerIds = [];
    for (const trackerId of meta.dirtyTrackers) {
        const tracker = trackerConfig.value.find(t => t.id === trackerId);
        if (tracker) {
            payload.config.push({
                ...tracker,
                _baseVersion: tracker._baseVersion || tracker._version || 0
            });
            appliedTrackerIds.push(trackerId);
        }
    }

    // Include dirty entries
    const appliedEntryKeys = [];
    for (const entryKey of meta.dirtyEntries) {
        const [date, trackerId] = entryKey.split('|');
        const entry = dailyLogs.value[date]?.[trackerId];

        if (entry) {
            if (!payload.days[date]) {
                payload.days[date] = {};
            }
            payload.days[date][trackerId] = {
                value: entry.value,
                completed: entry.completed,
                _baseVersion: entry._baseVersion || entry._version || 0
            };
            appliedEntryKeys.push(entryKey);
        }
    }

    const response = await fetch(`${API_BASE}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    if (!response.ok) {
        throw new Error('Failed to upload data');
    }

    const result = await response.json();

    // Handle server-side conflicts
    if (!result.success && result.conflicts && result.conflicts.length > 0) {
        const serverConflicts = result.conflicts.map(c => {
            if (c.entityType === 'tracker') {
                const localTracker = trackerConfig.value.find(t => t.id === c.entityId);
                return {
                    type: 'tracker',
                    id: c.entityId,
                    local: localTracker,
                    server: c.serverData,
                    autoResolvable: false
                };
            } else {
                const [date, trackerId] = c.entityId.split('|');
                const localEntry = dailyLogs.value[date]?.[trackerId];
                return {
                    type: 'entry',
                    id: c.entityId,
                    date,
                    trackerId,
                    local: localEntry,
                    server: c.serverData,
                    autoResolvable: false
                };
            }
        });

        pendingConflicts.value = [...pendingConflicts.value, ...serverConflicts];
        updateSyncStatus();

        showNotification({
            type: 'warning',
            title: 'Upload Conflict',
            message: `${serverConflicts.length} changes conflict with server data.`,
            action: {
                label: 'Resolve',
                handler: () => { currentView.value = 'conflicts'; }
            },
            duration: 0
        });

        return { success: false, reason: 'server_conflicts', conflicts: serverConflicts };
    }

    // Update local versions with server-assigned versions
    if (result.appliedConfig) {
        const updatedConfig = trackerConfig.value.map(t => {
            const applied = result.appliedConfig.find(a => a.id === t.id);
            if (applied) {
                return {
                    ...t,
                    _version: applied._version,
                    _baseVersion: applied._version
                };
            }
            return t;
        });
        trackerConfig.value = updatedConfig;
    }

    if (result.appliedDays) {
        const logs = { ...dailyLogs.value };
        for (const [date, entries] of Object.entries(result.appliedDays)) {
            if (!logs[date]) continue;
            for (const [trackerId, appliedEntry] of Object.entries(entries)) {
                if (logs[date][trackerId]) {
                    logs[date][trackerId] = {
                        ...logs[date][trackerId],
                        _version: appliedEntry._version,
                        _baseVersion: appliedEntry._version,
                        _baseValue: logs[date][trackerId].value,
                        _baseCompleted: logs[date][trackerId].completed
                    };
                }
            }
        }
        dailyLogs.value = logs;
    }

    // Clear dirty state for applied items
    clearDirtyState(appliedTrackerIds, appliedEntryKeys);

    // Update sync time
    syncMetadata.value = {
        ...syncMetadata.value,
        lastServerSyncTime: result.lastModified || getUtcNow()
    };

    // Prune old data and deleted trackers
    pruneOldLogs();
    pruneDeletedTrackers();

    await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);

    return { success: true };
}

// ==================== Conflict Resolution ====================

export async function resolveConflict(conflict, resolution) {
    const meta = syncMetadata.value;

    try {
        if (resolution === 'server') {
            // Apply server data locally
            if (conflict.type === 'tracker') {
                trackerConfig.value = trackerConfig.value.map(t => {
                    if (t.id === conflict.id) {
                        return {
                            ...conflict.server,
                            _baseVersion: conflict.server._version
                        };
                    }
                    return t;
                });

                // Remove from dirty list
                syncMetadata.value = {
                    ...syncMetadata.value,
                    dirtyTrackers: meta.dirtyTrackers.filter(id => id !== conflict.id)
                };

            } else if (conflict.type === 'entry') {
                const logs = { ...dailyLogs.value };
                if (!logs[conflict.date]) {
                    logs[conflict.date] = {};
                }
                logs[conflict.date][conflict.trackerId] = {
                    ...conflict.server,
                    _baseVersion: conflict.server._version,
                    _baseValue: conflict.server.value,
                    _baseCompleted: conflict.server.completed
                };
                dailyLogs.value = logs;

                // Remove from dirty list
                syncMetadata.value = {
                    ...syncMetadata.value,
                    dirtyEntries: meta.dirtyEntries.filter(key => key !== conflict.id)
                };
            }

        } else if (resolution === 'client') {
            // Push client data to server with force
            const response = await fetch(`${API_BASE}/resolve-conflict`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    entity_type: conflict.type,
                    entity_id: conflict.type === 'entry' ? conflict.id : conflict.local.id,
                    resolution: 'client',
                    client_id: meta.clientId,
                    client_data: conflict.local
                })
            });

            if (!response.ok) {
                throw new Error('Failed to resolve conflict on server');
            }

            // Update local version
            if (conflict.type === 'tracker') {
                trackerConfig.value = trackerConfig.value.map(t => {
                    if (t.id === conflict.id) {
                        return {
                            ...t,
                            _version: (conflict.server._version || 0) + 1,
                            _baseVersion: (conflict.server._version || 0) + 1
                        };
                    }
                    return t;
                });

                syncMetadata.value = {
                    ...syncMetadata.value,
                    dirtyTrackers: meta.dirtyTrackers.filter(id => id !== conflict.id)
                };

            } else if (conflict.type === 'entry') {
                const logs = { ...dailyLogs.value };
                if (logs[conflict.date]?.[conflict.trackerId]) {
                    logs[conflict.date][conflict.trackerId] = {
                        ...logs[conflict.date][conflict.trackerId],
                        _version: (conflict.server._version || 0) + 1,
                        _baseVersion: (conflict.server._version || 0) + 1,
                        _baseValue: logs[conflict.date][conflict.trackerId].value,
                        _baseCompleted: logs[conflict.date][conflict.trackerId].completed
                    };
                }
                dailyLogs.value = logs;

                syncMetadata.value = {
                    ...syncMetadata.value,
                    dirtyEntries: meta.dirtyEntries.filter(key => key !== conflict.id)
                };
            }
        }

        // Remove from pending conflicts
        pendingConflicts.value = pendingConflicts.value.filter(c => c.id !== conflict.id);

        await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);
        updateSyncStatus();

        showNotification({
            type: 'success',
            title: 'Conflict Resolved',
            message: resolution === 'server' ? 'Using server version.' : 'Your version was kept.',
            duration: 3000
        });

        // If no more conflicts, return to home
        if (pendingConflicts.value.length === 0) {
            currentView.value = 'home';
            // Re-trigger sync to complete any pending operations
            await triggerSync();
        }

        return { success: true };

    } catch (error) {
        console.error('Failed to resolve conflict:', error);
        showNotification({
            type: 'error',
            title: 'Resolution Failed',
            message: error.message,
            duration: 5000
        });
        return { success: false, error: error.message };
    }
}

export async function resolveAllConflicts(resolution) {
    const conflicts = [...pendingConflicts.value];

    for (const conflict of conflicts) {
        await resolveConflict(conflict, resolution);
    }
}

function pruneOldLogs() {
    const logs = { ...dailyLogs.value };
    Object.keys(logs).forEach(date => {
        if (!isWithinLastNDays(date, 7)) {
            delete logs[date];
        }
    });
    dailyLogs.value = logs;
    saveLogs();
}

// ==================== Computed Helpers ====================

export function isDayEditable(dateStr) {
    const today = getToday();
    if (dateStr === today) {
        return true;
    }
    // If we have pending conflicts, only today is editable
    if (pendingConflicts.value.length > 0) {
        return false;
    }
    // If config is dirty, only today is editable (legacy behavior)
    return !syncMetadata.value.dirtyConfig;
}

export function hasConflicts() {
    return pendingConflicts.value.length > 0;
}

// Legacy support for dirtyDays
export function getDirtyDays() {
    const meta = syncMetadata.value;
    const days = new Set();
    for (const entryKey of meta.dirtyEntries) {
        const [date] = entryKey.split('|');
        days.add(date);
    }
    return Array.from(days);
}
