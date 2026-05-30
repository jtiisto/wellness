/**
 * Coach sync-logic — PURE decision functions extracted from store.js.
 *
 * Everything here is a pure `(state, inputs) -> nextState | decision` function:
 * no Preact signals, no LocalForage, no fetch, no Date, no notifications. The
 * store keeps the thin read-signal -> call -> assign -> persist wrappers; this
 * module is unit-tested in isolation (test/js/coach-sync-logic.test.js).
 */

// ---- Content predicates --------------------------------------------------

export function logHasExerciseContent(log) {
    return Object.entries(log).some(([key, val]) => {
        if (['_lastModifiedAt', '_lastModifiedBy', 'session_feedback'].includes(key)) return false;
        if (typeof val !== 'object' || val === null) return false;
        // Completion is derived from data now; an exercise has real content when
        // it carries logged sets, checked items, or a duration.
        return val.sets?.length > 0 || val.completed_items?.length > 0 || val.duration_min != null;
    });
}

export function hasFeedbackContent(log) {
    const fb = log.session_feedback;
    return !!fb && (
        (fb.pain_discomfort && fb.pain_discomfort.trim()) ||
        (fb.general_notes && fb.general_notes.trim())
    );
}

// A log is worth uploading if it carries real exercise data OR non-empty session
// feedback. Truly-empty logs are skipped. Feedback-only logs upload safely: the
// server's content guard authoritatively rejects (via contentRejectedLogs) a
// feedback-only payload that would overwrite an existing logged workout.
export function logHasUploadableContent(log) {
    return logHasExerciseContent(log) || hasFeedbackContent(log);
}

// ---- Dirty-date state transitions ---------------------------------------

/**
 * Decide which dirty dates to clear after an upload.
 * A date clears only if it was applied AND its generation counter still matches
 * the pre-sync snapshot (i.e. it was not re-modified mid-sync). Generation
 * counters for cleared dates are dropped.
 *
 * @returns {{dirtyDates: string[], dirtyDateGenerations: Object}}
 */
export function nextDirtyAfterApply(appliedDates, snapshotGens, dirtyDates, dirtyDateGenerations) {
    const appliedSet = new Set(appliedDates);
    const nextDirty = dirtyDates.filter(date => {
        if (!appliedSet.has(date)) return true;  // not applied, keep dirty
        if (snapshotGens && dirtyDateGenerations[date] !== snapshotGens[date]) {
            return true;  // re-modified during sync, keep dirty
        }
        return false;  // applied and not re-modified, clear
    });

    const nextGens = { ...dirtyDateGenerations };
    const remaining = new Set(nextDirty);
    for (const date of appliedDates) {
        if (!remaining.has(date)) delete nextGens[date];
    }
    return { dirtyDates: nextDirty, dirtyDateGenerations: nextGens };
}

/**
 * Build the upload set from dirty dates + local logs. Includes a date's log iff
 * it exists AND carries uploadable content; truly-empty logs are skipped (and so
 * stay dirty). `uploadedDates` is exactly the set of dates actually sent — the
 * invariant that keeps "dirty cleared == sent".
 *
 * @returns {{logsToUpload: Object, uploadedDates: string[]}}
 */
export function selectLogsToUpload(dirtyDates, localLogs) {
    const logsToUpload = {};
    for (const date of dirtyDates) {
        const log = localLogs[date];
        if (!log) continue;
        if (!logHasUploadableContent(log)) continue;
        // Echo the last server stamp as the optimistic-concurrency base token
        // (R1). A brand-new local date has no `_lastModified`, so the token is
        // omitted and the server inserts (no conflict).
        logsToUpload[date] = log._lastModified
            ? { ...log, _baseLastModifiedAt: log._lastModified }
            : log;
    }
    return { logsToUpload, uploadedDates: Object.keys(logsToUpload) };
}

/** Dates from the structured rejectedLogs (`[{date, serverRow}]`) (R1). */
export function rejectedDates(rejectedLogs) {
    return (rejectedLogs || []).map(r => r.date);
}

/**
 * Advance the per-date base token after an accepted upload. The server stamped
 * every applied date with `serverTime`, so the local log must record that as its
 * `_lastModified` — otherwise the next edit would echo a stale base token and be
 * rejected (R1). Returns a new logs map; only applied dates present locally change.
 *
 * @returns {Object} next logs
 */
export function applyAcceptedTokens(localLogs, appliedDates, serverTime) {
    if (!appliedDates || appliedDates.length === 0 || !serverTime) return localLogs;
    const next = { ...localLogs };
    for (const date of appliedDates) {
        if (next[date]) next[date] = { ...next[date], _lastModified: serverTime };
    }
    return next;
}

/**
 * Adopt the server's current row for each token-rejected date — R1 in-cycle
 * recovery. The local edit lost the optimistic-concurrency check, so replace it
 * with the server's version (which carries the fresh `_lastModified` base token).
 * `rejectedLogs` is the structured `[{date, serverRow}]`; a null serverRow leaves
 * the local log untouched. Returns a new logs map.
 *
 * @returns {Object} next logs
 */
export function adoptRejectedServerRows(localLogs, rejectedLogs) {
    if (!rejectedLogs || rejectedLogs.length === 0) return localLogs;
    const next = { ...localLogs };
    for (const { date, serverRow } of rejectedLogs) {
        if (serverRow) next[date] = serverRow;
    }
    return next;
}

/**
 * Drop server-rejected dates (stale or content-rejected) from dirty state and
 * their generation counters. Same shape for both rejection kinds.
 *
 * @returns {{dirtyDates: string[], dirtyDateGenerations: Object}}
 */
export function nextDirtyAfterReject(dirtyDates, dirtyDateGenerations, rejectedDates) {
    const rejectedSet = new Set(rejectedDates);
    const nextDirty = dirtyDates.filter(d => !rejectedSet.has(d));
    const nextGens = { ...dirtyDateGenerations };
    for (const d of rejectedDates) delete nextGens[d];
    return { dirtyDates: nextDirty, dirtyDateGenerations: nextGens };
}

// ---- Force-sync merge + window pruning -----------------------------------

/**
 * Force-sync per-date resolution (client-side last-write-wins).
 * For each date across local+server logs, decide upload / accept-server / skip:
 *   - local-only & older than the window  -> keep local, don't upload (skip)
 *   - both present, local newer & uploadable -> upload local
 *   - both present, local newer but empty   -> take server (clobber guard)
 *   - both present, server newer            -> take server
 *   - both present, equal timestamp         -> keep local
 *   - local-only & uploadable               -> upload local
 *   - local-only & empty                    -> keep local, don't upload
 *   - server-only                           -> take server
 *
 * @returns {{uploadLogs: Object, mergedLogs: Object, counts: {uploaded:number, accepted:number, skipped:number}}}
 */
export function resolveForceSyncLogs(localLogs, serverLogs, earliestDate) {
    const uploadLogs = {};
    const mergedLogs = {};
    let uploaded = 0, accepted = 0, skipped = 0;
    const allDates = new Set([...Object.keys(localLogs), ...Object.keys(serverLogs)]);

    for (const date of allDates) {
        const localLog = localLogs[date];
        const serverLog = serverLogs[date];

        if (!serverLog && earliestDate && date < earliestDate) {
            mergedLogs[date] = localLog;  // local-only outside window
            skipped++;
            continue;
        }

        if (localLog && serverLog) {
            const localTs = localLog._lastModifiedAt || '';
            const serverTs = serverLog._lastModified || '';
            if (localTs > serverTs) {
                if (logHasUploadableContent(localLog)) {
                    uploadLogs[date] = localLog;
                    mergedLogs[date] = localLog;
                    uploaded++;
                } else {
                    mergedLogs[date] = serverLog;  // newer but empty: don't clobber
                    accepted++;
                }
            } else if (serverTs > localTs) {
                mergedLogs[date] = serverLog;
                accepted++;
            } else {
                mergedLogs[date] = localLog;       // equal: keep local
                skipped++;
            }
        } else if (localLog) {
            if (logHasUploadableContent(localLog)) {
                uploadLogs[date] = localLog;
                mergedLogs[date] = localLog;
                uploaded++;
            } else {
                mergedLogs[date] = localLog;       // local-only empty: keep, no upload
                skipped++;
            }
        } else {
            mergedLogs[date] = serverLog;
            accepted++;
        }
    }

    return { uploadLogs, mergedLogs, counts: { uploaded, accepted, skipped } };
}

/** Keep only entries whose date key is >= cutoff (server sync-window boundary). */
export function pruneOlderThan(dateKeyedMap, cutoff) {
    const out = {};
    for (const [date, value] of Object.entries(dateKeyedMap)) {
        if (date >= cutoff) out[date] = value;
    }
    return out;
}

/** Latest plan `_lastModified` across plans, not below `currentMax`. */
export function maxPlanVersion(plans, currentMax) {
    let max = currentMax;
    for (const plan of Object.values(plans)) {
        if (plan._lastModified && (!max || plan._lastModified > max)) {
            max = plan._lastModified;
        }
    }
    return max;
}
