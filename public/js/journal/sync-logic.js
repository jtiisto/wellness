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

/**
 * Stamp the server-issued `lastModifiedAt` from an accepted upload back onto
 * the matching local trackers and entries, so the next edit's upload uses the
 * correct base token. Entries not present locally are skipped (the row was
 * deleted between upload and apply). Returns the SAME references when a section
 * has nothing to apply, so the store can assign unconditionally without a
 * spurious signal write.
 *
 * @returns {{trackerConfig: Array, dailyLogs: Object}}
 */
export function computeAcceptedApply(acceptedTrackers, acceptedEntries, trackerConfig, dailyLogs) {
    let nextConfig = trackerConfig;
    if (acceptedTrackers.length > 0) {
        const stampById = new Map(acceptedTrackers.map(a => [a.id, a.lastModifiedAt]));
        nextConfig = trackerConfig.map(t => {
            const stamp = stampById.get(t.id);
            return stamp ? { ...t, lastModifiedAt: stamp } : t;
        });
    }

    let nextLogs = dailyLogs;
    if (acceptedEntries.length > 0) {
        const logs = { ...dailyLogs };
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
        nextLogs = logs;
    }

    return { trackerConfig: nextConfig, dailyLogs: nextLogs };
}

/**
 * Apply the `serverRow` from rejected uploads so the client recovers in-cycle
 * without a follow-up delta pull. A non-deleted serverRow upserts into config;
 * a soft-deleted serverRow is NOT upserted — its id is collected into
 * `trackerIdsToDelete` so the caller can route it through the full delete
 * cleanup (drop tracker + entries + dirty state). Rejected entries are
 * overwritten with the server's value/completed/lastModifiedAt. Returns the
 * SAME references when a section has nothing to apply.
 *
 * @returns {{trackerConfig: Array, dailyLogs: Object, trackerIdsToDelete: string[]}}
 */
export function computeRejectedApply(rejectedTrackers, rejectedEntries, trackerConfig, dailyLogs) {
    const trackerIdsToDelete = [];

    let nextConfig = trackerConfig;
    if (rejectedTrackers.length > 0) {
        const updated = [...trackerConfig];
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
        nextConfig = updated;
    }

    let nextLogs = dailyLogs;
    if (rejectedEntries.length > 0) {
        const logs = { ...dailyLogs };
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
        nextLogs = logs;
    }

    return { trackerConfig: nextConfig, dailyLogs: nextLogs, trackerIdsToDelete };
}

/**
 * Apply a server-side tracker delete locally: drop the trackers from config,
 * drop all their entries from dailyLogs, and purge any dirty state (tracker AND
 * entry-level, plus generation counters) that would otherwise leave the client
 * stuck "red" forever. The dirty purge — and the returned `dirtyChanged` flag —
 * only fire when a dirty tracker/entry actually matched a deleted id; entry
 * dirty keys are matched by `date|trackerId`. `logsChanged` reports whether any
 * day lost an entry. The store assigns `dailyLogs`/`meta` only when their flag
 * is set, mirroring the original conditional reassignment.
 *
 * @param {string[]} deletedIds
 * @param {{dirtyTrackers, dirtyEntries, dirtyTrackerGenerations, dirtyEntryGenerations}} meta
 * @returns {{trackerConfig, dailyLogs, meta, logsChanged: boolean, dirtyChanged: boolean}}
 */
export function computeDropDeletedTrackers(deletedIds, trackerConfig, dailyLogs, meta) {
    const idSet = new Set(deletedIds);

    const nextConfig = trackerConfig.filter(t => !idSet.has(t.id));

    const logs = { ...dailyLogs };
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

    // Prune dirty state for the deleted trackers and any of their entries.
    const nextMeta = { ...meta };
    const beforeT = nextMeta.dirtyTrackers.length;
    const beforeE = nextMeta.dirtyEntries.length;

    nextMeta.dirtyTrackers = nextMeta.dirtyTrackers.filter(id => !idSet.has(id));
    nextMeta.dirtyEntries = nextMeta.dirtyEntries.filter(key => {
        const trackerId = key.split('|')[1];
        return !idSet.has(trackerId);
    });

    let dirtyChanged = false;
    if (nextMeta.dirtyTrackers.length !== beforeT || nextMeta.dirtyEntries.length !== beforeE) {
        dirtyChanged = true;
        const tGens = { ...nextMeta.dirtyTrackerGenerations };
        for (const id of deletedIds) delete tGens[id];
        nextMeta.dirtyTrackerGenerations = tGens;

        const eGens = { ...nextMeta.dirtyEntryGenerations };
        for (const key of Object.keys(eGens)) {
            const trackerId = key.split('|')[1];
            if (idSet.has(trackerId)) delete eGens[key];
        }
        nextMeta.dirtyEntryGenerations = eGens;
    }

    return { trackerConfig: nextConfig, dailyLogs: logs, meta: nextMeta, logsChanged, dirtyChanged };
}

/**
 * Drop locally-soft-deleted trackers (`_deleted`) — and any entries belonging
 * to them — after a successful sync. Derives the deleted-id set from the config
 * itself. Returns null when there is nothing to prune (no `_deleted` trackers),
 * so the store can early-return without touching signals or persisting.
 * `logsChanged` reports whether any day lost an entry; the store persists logs
 * only then, but always persists config (a tracker was removed).
 *
 * @returns {{trackerConfig: Array, dailyLogs: Object, logsChanged: boolean} | null}
 */
export function computePruneDeletedTrackers(trackerConfig, dailyLogs) {
    const deletedIds = trackerConfig
        .filter(t => t._deleted)
        .map(t => t.id);
    if (deletedIds.length === 0) {
        return null;
    }

    const nextConfig = trackerConfig.filter(t => !t._deleted);
    const logs = { ...dailyLogs };
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

    return { trackerConfig: nextConfig, dailyLogs: logs, logsChanged };
}
