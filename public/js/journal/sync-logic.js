/**
 * Journal sync-logic — PURE decision functions extracted from store.js.
 *
 * No Preact signals, no LocalForage, no fetch, no Date: pure
 * `(state, inputs) -> nextState | decision`. The store keeps the thin
 * read-signal -> call -> assign -> persist wrappers; this module is unit-tested
 * in test/js/journal-sync-logic.test.js.
 *
 * Journal sync is optimistic concurrency on an opaque server-issued
 * `lastModifiedAt` token, echoed on upload as `_baseLastModifiedAt`.
 */

/**
 * Build the upload payload for the current dirty set. Each record carries its
 * `_baseLastModifiedAt` (the last server stamp) as the concurrency token;
 * brand-new records (no `lastModifiedAt`) omit it so the server inserts-if-absent.
 * The server-reserved top-level `lastModifiedAt` is never echoed back.
 *
 * @returns {{payload: Object, dirtyTrackerIds: string[], dirtyEntryKeys: string[]}}
 */
export function computeUploadPayload(meta, trackerConfig, dailyLogs) {
    const payload = {
        clientId: meta.clientId,
        config: [],
        days: {},
    };
    const dirtyTrackerIds = [];
    const dirtyEntryKeys = [];

    for (const trackerId of meta.dirtyTrackers) {
        const tracker = trackerConfig.find(t => t.id === trackerId);
        if (!tracker) continue;
        const item = { ...tracker };
        // Use the last server-stamped timestamp as the opaque concurrency token.
        if (tracker.lastModifiedAt) {
            item._baseLastModifiedAt = tracker.lastModifiedAt;
        }
        // Don't echo the server's stamp back as a top-level field — the server
        // treats `lastModifiedAt` as protocol-reserved.
        delete item.lastModifiedAt;
        payload.config.push(item);
        dirtyTrackerIds.push(trackerId);
    }

    for (const entryKey of meta.dirtyEntries) {
        const [date, trackerId] = entryKey.split('|');
        const entry = dailyLogs[date]?.[trackerId];
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

/**
 * Compute the next dirty state after an upload, for both trackers and entries.
 * An uploaded item clears only if its generation counter still matches the
 * pre-upload snapshot; if it was re-modified mid-sync (generation advanced) it
 * stays dirty so the next sync picks up the new edit. Generation counters for
 * items actually cleared are dropped.
 *
 * @returns {{dirtyTrackers, dirtyEntries, dirtyTrackerGenerations, dirtyEntryGenerations}}
 */
export function computeClearedDirtyState({
    uploadedTrackerIds = [],
    uploadedEntryKeys = [],
    snapshotTrackerGens = null,
    snapshotEntryGens = null,
    dirtyTrackers,
    dirtyEntries,
    dirtyTrackerGenerations,
    dirtyEntryGenerations,
}) {
    const uploadedTrackerSet = new Set(uploadedTrackerIds);
    const nextDirtyTrackers = dirtyTrackers.filter(id => {
        if (!uploadedTrackerSet.has(id)) return true;
        if (snapshotTrackerGens && dirtyTrackerGenerations[id] !== snapshotTrackerGens[id]) {
            return true; // re-modified during sync, keep dirty
        }
        return false;
    });

    const uploadedEntrySet = new Set(uploadedEntryKeys);
    const nextDirtyEntries = dirtyEntries.filter(key => {
        if (!uploadedEntrySet.has(key)) return true;
        if (snapshotEntryGens && dirtyEntryGenerations[key] !== snapshotEntryGens[key]) {
            return true; // re-modified during sync, keep dirty
        }
        return false;
    });

    const trackerGens = { ...dirtyTrackerGenerations };
    const remainingTrackers = new Set(nextDirtyTrackers);
    for (const id of uploadedTrackerIds) {
        if (!remainingTrackers.has(id)) delete trackerGens[id];
    }

    const entryGens = { ...dirtyEntryGenerations };
    const remainingEntries = new Set(nextDirtyEntries);
    for (const key of uploadedEntryKeys) {
        if (!remainingEntries.has(key)) delete entryGens[key];
    }

    return {
        dirtyTrackers: nextDirtyTrackers,
        dirtyEntries: nextDirtyEntries,
        dirtyTrackerGenerations: trackerGens,
        dirtyEntryGenerations: entryGens,
    };
}
