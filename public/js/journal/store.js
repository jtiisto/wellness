/**
 * Journal Store - Signals, LocalForage, and Sync Logic
 *
 * Sync protocol: optimistic concurrency on an opaque server-issued timestamp
 * token (`_baseLastModifiedAt`). The client wall clock is never compared by
 * the server. Rejected uploads carry the current `serverRow` so the client
 * can recover in-cycle without waiting for a delta pull.
 *
 * Generation counters (`dirtyEntryGenerations`, `dirtyTrackerGenerations`)
 * are preserved to detect re-modifications during an in-flight sync — see
 * the edit-during-sync race test at test/e2e_browser/test_sync_race.py.
 */
import { signal, effect, batch } from '@preact/signals';
import localforage from 'localforage';
import { getToday, getUtcNow, generateId } from '../shared/utils.js';
import { isWithinLastNDays } from './utils.js';
import { showNotification } from '../shared/notifications.js';
import { log as debugLog } from '../shared/debug-log.js';
import { SyncScheduler } from '../shared/sync-scheduler.js';

// Dedicated LocalForage instance — avoids collisions with other modules
const storage = localforage.createInstance({
    name: 'JournalApp',
    storeName: 'journal_data',
});

// Storage keys
const KEYS = {
    METADATA: 'app_metadata',
    CONFIG: 'tracker_config',
    LOGS: 'daily_logs',
    CLIENT_ID: 'client_id',
    EXPANDED_CATEGORIES: 'expanded_categories',
    VALUE_UPDATED_TIMES: 'tracker_value_updated_times',
    APP_SCHEMA_VERSION: 'app_schema_version',
};

// LocalForage schema version.
// 1 (or absent) = pre-LWW (per-record integer versioning, conflict resolution)
// 2 = LWW (optimistic concurrency on opaque _baseLastModifiedAt tokens)
// On boot, a mismatch between this constant and the stored value triggers a
// hard reset of LocalForage. Dirty edits from the prior schema cannot be
// uploaded under the new protocol, so the migration refuses to proceed when
// they exist and surfaces an `initError` signal for the UI to render.
const JOURNAL_APP_SCHEMA_VERSION = 2;

// ==================== Signals ====================

// App state
export const currentView = signal('home'); // 'home' | 'config'
export const selectedDate = signal(getToday());
export const isLoading = signal(true);
export const isSyncing = signal(false);

// Tracker configuration
export const trackerConfig = signal([]);

// Daily logs - keyed by date string
export const dailyLogs = signal({});

// Sync metadata
export const syncMetadata = signal({
    clientId: null,
    lastServerSyncTime: null,
    dirtyTrackers: [],  // Array of tracker IDs with local changes
    dirtyEntries: [],   // Array of "date|trackerId" strings with local changes
    dirtyEntryGenerations: {},   // { "date|trackerId": number } — incremented on each modification
    dirtyTrackerGenerations: {}, // { trackerId: number } — incremented on each modification
});

// Sync status indicator: 'green' | 'red' | 'gray'
export const syncStatus = signal('gray');

// Fatal initialization error (e.g. blocked schema migration). When set, the
// app shell renders a recovery message instead of the normal UI.
export const initError = signal(null);

// Edit state for config screen
export const editingTracker = signal(null);

// Expanded categories state (categories are collapsed by default)
export const expandedCategories = signal(new Set());

// Per-entry "last value update" timestamps. Stored client-side only (not
// synced) to solve the accumulator "did I already add this intake?" problem.
// Keyed by "YYYY-MM-DD|trackerId".
export const trackerValueUpdatedTimes = signal({});

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
    await storage.setItem(KEYS.EXPANDED_CATEGORIES, Array.from(expandedCategories.value));
}

// ==================== Persistence ====================

// Get or create client ID
async function getClientId() {
    let clientId = await storage.getItem(KEYS.CLIENT_ID);
    if (!clientId) {
        clientId = generateId();
        await storage.setItem(KEYS.CLIENT_ID, clientId);
    }
    return clientId;
}

// Internal: actually wipe LocalForage and stamp the new schema version.
// Caller is responsible for handling whatever data loss this implies.
async function _wipeAndStampSchema(fromVersion) {
    debugLog('journal-sync', 'migrating LocalForage to LWW schema', {
        from: fromVersion || '(none)',
        to: JOURNAL_APP_SCHEMA_VERSION,
    });
    try {
        await storage.clear();
        await storage.setItem(KEYS.APP_SCHEMA_VERSION, JOURNAL_APP_SCHEMA_VERSION);
    } catch (err) {
        initError.value = {
            kind: 'migration-failed',
            message: `Storage upgrade failed: ${err?.message || err}. Try reloading; if it persists, clear this site's storage from your browser settings.`,
        };
        throw err;
    }
}

// User-invoked recovery from the migration-blocked state. The unsynced edits
// are discarded; the next sync is a full pull from the server. Reloads the
// page so the boot path runs cleanly from scratch.
export async function discardLocalAndContinue() {
    try {
        await _wipeAndStampSchema('(forced)');
        window.location.reload();
    } catch {
        // _wipeAndStampSchema already set initError; nothing more to do.
    }
}

// Check the LocalForage schema version against the current code's expectation.
// Returns true if initialization should continue, false if it should bail out
// because a migration error was surfaced to the UI via `initError`.
async function checkAndMigrateSchema() {
    let stored;
    try {
        stored = await storage.getItem(KEYS.APP_SCHEMA_VERSION);
    } catch (err) {
        initError.value = {
            kind: 'storage-unavailable',
            message: `Cannot read local storage: ${err?.message || err}. Try reloading the page.`,
        };
        return false;
    }

    if (stored === JOURNAL_APP_SCHEMA_VERSION) {
        return true;
    }

    // Schema mismatch (or first run on a populated pre-version-tracking DB).
    // Inspect the old metadata blob for dirty edits — under the prior schema
    // those edits carry `_baseVersion` tokens the new server no longer
    // understands, so they can't be re-uploaded as-is.
    let oldMeta;
    try {
        oldMeta = await storage.getItem(KEYS.METADATA);
    } catch (err) {
        initError.value = {
            kind: 'storage-unavailable',
            message: `Cannot read local storage: ${err?.message || err}. Try reloading the page.`,
        };
        return false;
    }
    const dirtyTrackers = oldMeta?.dirtyTrackers || [];
    const dirtyEntries = oldMeta?.dirtyEntries || [];

    if (dirtyTrackers.length > 0 || dirtyEntries.length > 0) {
        const total = dirtyTrackers.length + dirtyEntries.length;
        debugLog('journal-sync', 'migration blocked: dirty edits from previous schema', {
            dirtyTrackers: dirtyTrackers.length,
            dirtyEntries: dirtyEntries.length,
        });
        initError.value = {
            kind: 'migration-blocked',
            message: `${total} unsynced change${total === 1 ? '' : 's'} from the previous app version cannot be uploaded under the new sync protocol. Discarding will lose those changes; the next sync will pull a fresh copy from the server.`,
            recoverable: true,
            dirtyCount: total,
        };
        return false;
    }

    // No dirty edits — safe to wipe and proceed. Next sync is a full pull.
    try {
        await _wipeAndStampSchema(stored);
    } catch {
        // initError already set by _wipeAndStampSchema
        return false;
    }
    return true;
}

// Load all data from LocalForage on startup
export async function initializeStore() {
    try {
        isLoading.value = true;

        const proceed = await checkAndMigrateSchema();
        if (!proceed) {
            return;
        }

        const [metadata, config, logs, clientId, expanded, valueUpdated] = await Promise.all([
            storage.getItem(KEYS.METADATA),
            storage.getItem(KEYS.CONFIG),
            storage.getItem(KEYS.LOGS),
            getClientId(),
            storage.getItem(KEYS.EXPANDED_CATEGORIES),
            storage.getItem(KEYS.VALUE_UPDATED_TIMES),
        ]);

        batch(() => {
            syncMetadata.value = {
                clientId,
                lastServerSyncTime: metadata?.lastServerSyncTime || null,
                dirtyTrackers: metadata?.dirtyTrackers || [],
                dirtyEntries: metadata?.dirtyEntries || [],
                dirtyEntryGenerations: metadata?.dirtyEntryGenerations || {},
                dirtyTrackerGenerations: metadata?.dirtyTrackerGenerations || {},
            };

            trackerConfig.value = config || [];
            dailyLogs.value = logs || {};
            expandedCategories.value = new Set(expanded || []);
            trackerValueUpdatedTimes.value = valueUpdated || {};
        });

        updateSyncStatus();

        if (navigator.onLine) {
            scheduler.requestSync();
        }
        scheduler.start();
    } catch (error) {
        console.error('Failed to initialize store:', error);
    } finally {
        isLoading.value = false;
    }
}

async function saveMetadata() {
    const meta = syncMetadata.value;
    await storage.setItem(KEYS.METADATA, {
        lastServerSyncTime: meta.lastServerSyncTime,
        dirtyTrackers: meta.dirtyTrackers,
        dirtyEntries: meta.dirtyEntries,
        dirtyEntryGenerations: meta.dirtyEntryGenerations,
        dirtyTrackerGenerations: meta.dirtyTrackerGenerations,
    });
}

async function saveConfig() {
    await storage.setItem(KEYS.CONFIG, trackerConfig.value);
}

async function saveLogs() {
    await storage.setItem(KEYS.LOGS, dailyLogs.value);
}

// ==================== Tracker Config Actions ====================

export function addTracker(tracker) {
    // No `_baseLastModifiedAt` on a brand-new tracker: the server treats the
    // absence of the token as "INSERT only if no row exists with this key".
    trackerConfig.value = [...trackerConfig.value, { ...tracker }];
    markTrackerDirty(tracker.id);
    saveConfig();
    scheduler.scheduleUpload();
}

export function updateTracker(trackerId, updates) {
    trackerConfig.value = trackerConfig.value.map(t => {
        if (t.id === trackerId) {
            return { ...t, ...updates };
        }
        return t;
    });
    markTrackerDirty(trackerId);
    saveConfig();
    scheduler.scheduleUpload();
}

export function deleteTracker(trackerId) {
    // Mark as deleted rather than removing, for sync purposes
    trackerConfig.value = trackerConfig.value.map(t => {
        if (t.id === trackerId) {
            return { ...t, _deleted: true };
        }
        return t;
    });
    markTrackerDirty(trackerId);
    saveConfig();
    scheduler.scheduleUpload();
}

// Drop deleted trackers locally — and any entries belonging to them — after a
// successful sync. Called after both upload and download paths.
function pruneDeletedTrackers() {
    const deletedIds = trackerConfig.value
        .filter(t => t._deleted)
        .map(t => t.id);
    if (deletedIds.length === 0) {
        return;
    }
    trackerConfig.value = trackerConfig.value.filter(t => !t._deleted);
    const logs = { ...dailyLogs.value };
    let logsChanged = false;
    for (const date of Object.keys(logs)) {
        const day = { ...logs[date] };
        let dayChanged = false;
        for (const id of deletedIds) {
            if (id in day) {
                delete day[id];
                dayChanged = true;
            }
        }
        if (dayChanged) {
            logs[date] = day;
            logsChanged = true;
        }
    }
    if (logsChanged) {
        dailyLogs.value = logs;
        saveLogs();
    }
    saveConfig();
}

// Apply a server-side tracker delete locally: drop from config, drop all
// matching entries from dailyLogs, and purge any dirty state (tracker AND
// entry-level) that would otherwise leave the client stuck "red" forever.
function dropDeletedTrackerIds(deletedIds) {
    if (!deletedIds || deletedIds.length === 0) {
        return;
    }
    const idSet = new Set(deletedIds);

    trackerConfig.value = trackerConfig.value.filter(t => !idSet.has(t.id));

    const logs = { ...dailyLogs.value };
    let changed = false;
    for (const date of Object.keys(logs)) {
        const day = { ...logs[date] };
        let dayChanged = false;
        for (const id of deletedIds) {
            if (id in day) {
                delete day[id];
                dayChanged = true;
            }
        }
        if (dayChanged) {
            logs[date] = day;
            changed = true;
        }
    }
    if (changed) {
        dailyLogs.value = logs;
    }

    // Prune dirty state for the deleted trackers and any of their entries.
    const meta = { ...syncMetadata.value };
    const beforeT = meta.dirtyTrackers.length;
    const beforeE = meta.dirtyEntries.length;

    meta.dirtyTrackers = meta.dirtyTrackers.filter(id => !idSet.has(id));
    meta.dirtyEntries = meta.dirtyEntries.filter(key => {
        const trackerId = key.split('|')[1];
        return !idSet.has(trackerId);
    });

    if (meta.dirtyTrackers.length !== beforeT || meta.dirtyEntries.length !== beforeE) {
        const tGens = { ...meta.dirtyTrackerGenerations };
        for (const id of deletedIds) delete tGens[id];
        meta.dirtyTrackerGenerations = tGens;

        const eGens = { ...meta.dirtyEntryGenerations };
        for (const key of Object.keys(eGens)) {
            const trackerId = key.split('|')[1];
            if (idSet.has(trackerId)) delete eGens[key];
        }
        meta.dirtyEntryGenerations = eGens;

        syncMetadata.value = meta;
    }
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
        },
    };

    dailyLogs.value = logs;
    markEntryDirty(entryKey);
    saveLogs();
    scheduler.scheduleUpload();
}

export function getEntry(date, trackerId) {
    return dailyLogs.value[date]?.[trackerId] || null;
}

// Record a per-entry "value last updated" timestamp. Client-only (LocalForage),
// not synced — solves the accumulator "did I already add this intake?" problem
// without schema churn. See plans/ui-refresh.md 2A.
export function markValueUpdated(date, trackerId) {
    const key = `${date}|${trackerId}`;
    const next = { ...trackerValueUpdatedTimes.value, [key]: getUtcNow() };
    trackerValueUpdatedTimes.value = next;
    storage.setItem(KEYS.VALUE_UPDATED_TIMES, next);
}

// ==================== Dirty State Management ====================

function markTrackerDirty(trackerId) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyTrackers.includes(trackerId)) {
        meta.dirtyTrackers = [...meta.dirtyTrackers, trackerId];
    }
    // Always increment generation (detects re-modifications during sync)
    const gens = { ...meta.dirtyTrackerGenerations };
    gens[trackerId] = (gens[trackerId] || 0) + 1;
    meta.dirtyTrackerGenerations = gens;
    syncMetadata.value = meta;
    saveMetadata();
    updateSyncStatus();
}

function markEntryDirty(entryKey) {
    const meta = { ...syncMetadata.value };
    if (!meta.dirtyEntries.includes(entryKey)) {
        meta.dirtyEntries = [...meta.dirtyEntries, entryKey];
    }
    // Always increment generation (detects re-modifications during sync)
    const gens = { ...meta.dirtyEntryGenerations };
    gens[entryKey] = (gens[entryKey] || 0) + 1;
    meta.dirtyEntryGenerations = gens;
    syncMetadata.value = meta;
    saveMetadata();
    updateSyncStatus();
}

// Clear dirty state for items that were successfully uploaded, but ONLY for
// items whose generation counter still matches the pre-upload snapshot.
// If the user edited the same record again during the upload, the generation
// has advanced and we keep it dirty so the next sync picks up the new edit.
function clearDirtyState(uploadedTrackerIds = [], uploadedEntryKeys = [],
                         snapshotTrackerGens = null, snapshotEntryGens = null) {
    const meta = { ...syncMetadata.value };

    const uploadedTrackerSet = new Set(uploadedTrackerIds);
    meta.dirtyTrackers = meta.dirtyTrackers.filter(id => {
        if (!uploadedTrackerSet.has(id)) return true;
        if (snapshotTrackerGens && meta.dirtyTrackerGenerations[id] !== snapshotTrackerGens[id]) {
            return true; // re-modified during sync, keep dirty
        }
        return false;
    });

    const uploadedEntrySet = new Set(uploadedEntryKeys);
    meta.dirtyEntries = meta.dirtyEntries.filter(key => {
        if (!uploadedEntrySet.has(key)) return true;
        if (snapshotEntryGens && meta.dirtyEntryGenerations[key] !== snapshotEntryGens[key]) {
            return true; // re-modified during sync, keep dirty
        }
        return false;
    });

    // Clean up generation counters for items that were actually cleared
    const trackerGens = { ...meta.dirtyTrackerGenerations };
    const remainingTrackers = new Set(meta.dirtyTrackers);
    for (const id of uploadedTrackerIds) {
        if (!remainingTrackers.has(id)) delete trackerGens[id];
    }
    meta.dirtyTrackerGenerations = trackerGens;

    const entryGens = { ...meta.dirtyEntryGenerations };
    const remainingEntries = new Set(meta.dirtyEntries);
    for (const key of uploadedEntryKeys) {
        if (!remainingEntries.has(key)) delete entryGens[key];
    }
    meta.dirtyEntryGenerations = entryGens;

    syncMetadata.value = meta;
    saveMetadata();
    updateSyncStatus();
}

function updateSyncStatus() {
    const meta = syncMetadata.value;
    const hasDirtyData = meta.dirtyTrackers.length > 0 || meta.dirtyEntries.length > 0;

    if (!navigator.onLine) {
        syncStatus.value = 'gray';
    } else if (hasDirtyData) {
        syncStatus.value = 'red';
    } else if (meta.lastServerSyncTime) {
        syncStatus.value = 'green';
    } else {
        syncStatus.value = 'gray';
    }
}

// ==================== Auto-Sync Scheduler ====================

export const scheduler = new SyncScheduler({
    name: 'journal',
    syncFn: triggerSync,
    getIsSyncing: () => isSyncing.value,
    getHasDirtyData: () => syncMetadata.value.dirtyTrackers.length > 0 || syncMetadata.value.dirtyEntries.length > 0,
});

// ==================== Sync Logic ====================

const API_BASE = '/wellness/api/journal/sync';

// Pull deltas from server and apply non-locally-dirty changes.
async function pullServerChanges(clientId, since) {
    const url = since
        ? `${API_BASE}/delta?since=${encodeURIComponent(since)}&client_id=${encodeURIComponent(clientId)}`
        : `${API_BASE}/delta?client_id=${encodeURIComponent(clientId)}`;
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error('Failed to fetch server changes');
    }
    const serverData = await response.json();

    const dirtyTrackerIds = new Set(syncMetadata.value.dirtyTrackers);
    const dirtyEntryKeys = new Set(syncMetadata.value.dirtyEntries);

    batch(() => {
        // Apply tracker config — server is authoritative for non-dirty rows.
        // Server-newer deletes win over locally dirty edits (the user can no
        // longer "save" an edit to a tracker the source-of-truth has deleted).
        const updatedConfig = [...trackerConfig.value];
        for (const serverTracker of (serverData.config || [])) {
            if (dirtyTrackerIds.has(serverTracker.id)) continue;
            const localIndex = updatedConfig.findIndex(t => t.id === serverTracker.id);
            if (localIndex >= 0) {
                updatedConfig[localIndex] = { ...serverTracker };
            } else {
                updatedConfig.push({ ...serverTracker });
            }
        }
        trackerConfig.value = updatedConfig;

        if (serverData.deletedTrackers && serverData.deletedTrackers.length > 0) {
            // dropDeletedTrackerIds also clears dirty state for the deleted
            // trackers and any of their entries so the client doesn't stay
            // stuck "red" indefinitely with un-uploadable dirty rows.
            dropDeletedTrackerIds(serverData.deletedTrackers);
        }

        // Apply entries — skip ones the user has dirty locally.
        const logs = { ...dailyLogs.value };
        for (const [date, serverEntries] of Object.entries(serverData.days || {})) {
            if (!logs[date]) {
                logs[date] = {};
            }
            for (const [trackerId, serverEntry] of Object.entries(serverEntries)) {
                const entryKey = `${date}|${trackerId}`;
                if (dirtyEntryKeys.has(entryKey)) continue;
                logs[date][trackerId] = { ...serverEntry };
            }
        }
        dailyLogs.value = logs;

        syncMetadata.value = {
            ...syncMetadata.value,
            lastServerSyncTime: serverData.serverTime || getUtcNow(),
        };
    });

    await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);
    return serverData;
}

// Build the upload payload for the dirty set. Each record carries its
// `_baseLastModifiedAt` (the server stamp from the last accept), which the
// server compares against its stored timestamp.
function buildUploadPayload() {
    const meta = syncMetadata.value;
    const payload = {
        clientId: meta.clientId,
        config: [],
        days: {},
    };
    const dirtyTrackerIds = [];
    const dirtyEntryKeys = [];

    for (const trackerId of meta.dirtyTrackers) {
        const tracker = trackerConfig.value.find(t => t.id === trackerId);
        if (!tracker) continue;
        const item = { ...tracker };
        // Use the last server-stamped timestamp as the opaque concurrency token
        if (tracker.lastModifiedAt) {
            item._baseLastModifiedAt = tracker.lastModifiedAt;
        }
        // Don't echo the server's stamp back as a top-level field — the
        // server treats `lastModifiedAt` as protocol-reserved.
        delete item.lastModifiedAt;
        payload.config.push(item);
        dirtyTrackerIds.push(trackerId);
    }

    for (const entryKey of meta.dirtyEntries) {
        const [date, trackerId] = entryKey.split('|');
        const entry = dailyLogs.value[date]?.[trackerId];
        if (!entry) continue;
        if (!payload.days[date]) payload.days[date] = {};
        const data = {
            value: entry.value,
            completed: entry.completed,
        };
        if (entry.lastModifiedAt) {
            data._baseLastModifiedAt = entry.lastModifiedAt;
        }
        payload.days[date][trackerId] = data;
        dirtyEntryKeys.push(entryKey);
    }

    return { payload, dirtyTrackerIds, dirtyEntryKeys };
}

// Apply the server-stamped timestamps from a successful upload back onto the
// local rows so the next edit's upload uses the correct base token.
function applyAccepted(acceptedTrackers, acceptedEntries) {
    batch(() => {
        if (acceptedTrackers.length > 0) {
            const stampById = new Map(acceptedTrackers.map(a => [a.id, a.lastModifiedAt]));
            trackerConfig.value = trackerConfig.value.map(t => {
                const stamp = stampById.get(t.id);
                return stamp ? { ...t, lastModifiedAt: stamp } : t;
            });
        }
        if (acceptedEntries.length > 0) {
            const logs = { ...dailyLogs.value };
            for (const acc of acceptedEntries) {
                if (!logs[acc.date]?.[acc.trackerId]) continue;
                logs[acc.date] = {
                    ...logs[acc.date],
                    [acc.trackerId]: {
                        ...logs[acc.date][acc.trackerId],
                        lastModifiedAt: acc.lastModifiedAt,
                    },
                };
            }
            dailyLogs.value = logs;
        }
    });
}

// Apply the `serverRow` from rejected uploads so the client recovers in-cycle
// without needing a follow-up delta pull. If the server's row is itself a
// soft-deleted tracker, route through the full delete cleanup so we drop the
// tracker, its entries, and any associated dirty state.
function applyRejected(rejectedTrackers, rejectedEntries) {
    const trackerIdsToDelete = [];
    batch(() => {
        if (rejectedTrackers.length > 0) {
            const updated = [...trackerConfig.value];
            for (const rej of rejectedTrackers) {
                if (!rej.serverRow) continue;
                if (rej.serverRow.deleted) {
                    trackerIdsToDelete.push(rej.id);
                    continue;
                }
                const idx = updated.findIndex(t => t.id === rej.id);
                if (idx >= 0) {
                    updated[idx] = { ...rej.serverRow };
                } else {
                    updated.push({ ...rej.serverRow });
                }
            }
            trackerConfig.value = updated;
        }
        if (rejectedEntries.length > 0) {
            const logs = { ...dailyLogs.value };
            for (const rej of rejectedEntries) {
                if (!rej.serverRow) continue;
                if (!logs[rej.date]) logs[rej.date] = {};
                logs[rej.date] = {
                    ...logs[rej.date],
                    [rej.trackerId]: {
                        value: rej.serverRow.value,
                        completed: rej.serverRow.completed,
                        lastModifiedAt: rej.serverRow.lastModifiedAt,
                    },
                };
            }
            dailyLogs.value = logs;
        }
    });
    if (trackerIdsToDelete.length > 0) {
        dropDeletedTrackerIds(trackerIdsToDelete);
    }
}

export async function triggerSync() {
    if (!navigator.onLine) {
        syncStatus.value = 'gray';
        return { success: false, reason: 'offline' };
    }

    isSyncing.value = true;

    try {
        const meta = syncMetadata.value;
        const clientId = meta.clientId;
        debugLog('journal-sync', 'sync start', {
            clientId,
            dirtyTrackers: meta.dirtyTrackers.length,
            dirtyEntries: meta.dirtyEntries.length,
            lastServerSyncTime: meta.lastServerSyncTime,
        });

        // Snapshot generations BEFORE any network call. If the user edits a
        // record during the upload, its counter advances and we keep that
        // record dirty even though the server accepted the old value.
        const snapshotTrackerGens = { ...meta.dirtyTrackerGenerations };
        const snapshotEntryGens = { ...meta.dirtyEntryGenerations };

        // 1. Pull server changes
        await pullServerChanges(clientId, meta.lastServerSyncTime);

        // 2. Upload local changes (rebuild payload after pull — the pull may
        //    have updated `lastModifiedAt` tokens on non-dirty rows, but
        //    dirty rows are preserved as-is)
        const currentMeta = syncMetadata.value;
        const hasLocalChanges =
            currentMeta.dirtyTrackers.length > 0 || currentMeta.dirtyEntries.length > 0;
        if (!hasLocalChanges) {
            debugLog('journal-sync', 'sync complete (no upload)');
            updateSyncStatus();
            return { success: true };
        }

        const { payload, dirtyTrackerIds, dirtyEntryKeys } = buildUploadPayload();
        debugLog('journal-sync', 'upload attempt', {
            trackerCount: payload.config.length,
            entryCount: Object.values(payload.days).reduce((s, d) => s + Object.keys(d).length, 0),
        });

        const response = await fetch(`${API_BASE}/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        if (!response.ok) {
            throw new Error('Failed to upload data');
        }
        const result = await response.json();

        applyAccepted(result.acceptedTrackers || [], result.acceptedEntries || []);
        applyRejected(result.rejectedTrackers || [], result.rejectedEntries || []);

        // Build the set of records we either accepted or rejected — both are
        // resolved (rejected ones got their serverRow applied locally).
        const resolvedTrackerIds = [
            ...(result.acceptedTrackers || []).map(a => a.id),
            ...(result.rejectedTrackers || []).map(r => r.id),
        ];
        const resolvedEntryKeys = [
            ...(result.acceptedEntries || []).map(a => `${a.date}|${a.trackerId}`),
            ...(result.rejectedEntries || []).map(r => `${r.date}|${r.trackerId}`),
        ];

        clearDirtyState(resolvedTrackerIds, resolvedEntryKeys,
                        snapshotTrackerGens, snapshotEntryGens);

        // Sync time advances to the server's response timestamp
        syncMetadata.value = {
            ...syncMetadata.value,
            lastServerSyncTime: result.serverTime || getUtcNow(),
        };

        pruneOldLogs();
        pruneDeletedTrackers();

        await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);

        const rejectedCount =
            (result.rejectedTrackers?.length || 0) + (result.rejectedEntries?.length || 0);
        if (rejectedCount > 0) {
            debugLog('journal-sync', 'upload had rejections (recovered in-cycle)', { rejectedCount });
        }

        updateSyncStatus();
        return { success: true };

    } catch (error) {
        console.error('Sync failed:', error);
        debugLog('journal-sync', 'sync error', { error: error.message });
        syncStatus.value = 'red';
        return { success: false, reason: error.message, error };
    } finally {
        isSyncing.value = false;
    }
}

function pruneOldLogs() {
    const logs = { ...dailyLogs.value };
    let changed = false;
    Object.keys(logs).forEach(date => {
        if (!isWithinLastNDays(date, 7)) {
            delete logs[date];
            changed = true;
        }
    });
    if (changed) {
        dailyLogs.value = logs;
        saveLogs();
    }
}

// ==================== Computed Helpers ====================

export function isDayEditable(dateStr) {
    const today = getToday();
    if (dateStr === today) {
        return true;
    }
    // If trackers have unsynced changes, only today is editable
    return syncMetadata.value.dirtyTrackers.length === 0;
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
        const meta = syncMetadata.value;
        const clientId = meta.clientId;
        const snapshotTrackerGens = { ...meta.dirtyTrackerGenerations };
        const snapshotEntryGens = { ...meta.dirtyEntryGenerations };
        debugLog('journal-sync', 'force sync start', { clientId });

        // Phase 1: Full pull (no `since`) — server is authoritative for
        // non-dirty rows; dirty rows are preserved as-is.
        await pullServerChanges(clientId, null);

        // Phase 2: Upload everything currently dirty using the protocol's
        // optimistic-concurrency tokens. Server is the only arbiter — there
        // is no client-side timestamp comparison anywhere.
        const currentMeta = syncMetadata.value;
        const hasLocalChanges =
            currentMeta.dirtyTrackers.length > 0 || currentMeta.dirtyEntries.length > 0;
        let uploaded = 0;
        let rejectedCount = 0;
        if (hasLocalChanges) {
            const { payload } = buildUploadPayload();
            const response = await fetch(`${API_BASE}/update`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            if (!response.ok) throw new Error('Failed to upload data');
            const result = await response.json();

            applyAccepted(result.acceptedTrackers || [], result.acceptedEntries || []);
            applyRejected(result.rejectedTrackers || [], result.rejectedEntries || []);

            uploaded =
                (result.acceptedTrackers?.length || 0) + (result.acceptedEntries?.length || 0);
            rejectedCount =
                (result.rejectedTrackers?.length || 0) + (result.rejectedEntries?.length || 0);

            const resolvedTrackerIds = [
                ...(result.acceptedTrackers || []).map(a => a.id),
                ...(result.rejectedTrackers || []).map(r => r.id),
            ];
            const resolvedEntryKeys = [
                ...(result.acceptedEntries || []).map(a => `${a.date}|${a.trackerId}`),
                ...(result.rejectedEntries || []).map(r => `${r.date}|${r.trackerId}`),
            ];
            clearDirtyState(resolvedTrackerIds, resolvedEntryKeys,
                            snapshotTrackerGens, snapshotEntryGens);

            syncMetadata.value = {
                ...syncMetadata.value,
                lastServerSyncTime: result.serverTime || getUtcNow(),
            };
        }

        pruneDeletedTrackers();
        await Promise.all([saveConfig(), saveLogs(), saveMetadata()]);
        updateSyncStatus();

        debugLog('journal-sync', 'force sync complete', { uploaded, rejected: rejectedCount });
        scheduler.resetRetry();
        return { success: true, uploaded, accepted: uploaded, conflicts: rejectedCount };

    } catch (error) {
        console.error('Force sync failed:', error);
        debugLog('journal-sync', 'force sync error', { error: error.message });
        syncStatus.value = 'red';
        return { success: false, error: error.message };
    } finally {
        isSyncing.value = false;
    }
}
