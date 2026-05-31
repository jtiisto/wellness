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
        logsToUpload[date] = withBaseTokens(log);
    }
    return { logsToUpload, uploadedDates: Object.keys(logsToUpload) };
}

/**
 * Echo the last-seen server stamps as base tokens for upload (R3): the day's
 * `_lastModified` becomes the feedback record's `_baseLastModifiedAt`, and each
 * exercise's `_lastModified` becomes that exercise's `_baseLastModifiedAt`. A
 * record with no stamp (a brand-new local date or exercise) omits the token, so
 * the server inserts it (no conflict). Pure; returns a shallow copy.
 */
export function withBaseTokens(log) {
    const out = {};
    for (const [key, val] of Object.entries(log)) {
        if (val && typeof val === 'object' && val._lastModified) {
            out[key] = { ...val, _baseLastModifiedAt: val._lastModified };
        } else {
            out[key] = val;
        }
    }
    if (log._lastModified) out._baseLastModifiedAt = log._lastModified;
    return out;
}

/**
 * Adopt the merged server day returned per uploaded date (R3 `results`). For each
 * date NOT re-modified mid-sync (its generation still matches the pre-sync
 * snapshot), replace the local log with the reconciled `serverRow` — which embeds
 * each record's fresh `_lastModified` token. A re-modified date keeps its local
 * log (it stays dirty and re-uploads next cycle). Replaces R1-2a's
 * applyAcceptedTokens + adoptRejectedServerRows with one mechanism.
 *
 * @returns {Object} next logs
 */
export function adoptUploadResults(localLogs, results, snapshotGens, dirtyDateGenerations) {
    if (!results) return localLogs;
    const next = { ...localLogs };
    for (const [date, serverRow] of Object.entries(results)) {
        if (!serverRow) continue;
        const reModified = snapshotGens && dirtyDateGenerations[date] !== snapshotGens[date];
        if (reModified) {
            // Re-modified mid-sync: keep the local re-edit (it stays dirty and
            // re-uploads), but ADVANCE each record's token to the server's stamp
            // so the next upload echoes a fresh base rather than the stale
            // pre-sync one (which the now-advanced server would reject, losing the
            // re-edit). Content is kept; only `_lastModified` tokens move forward.
            next[date] = advanceRecordTokens(localLogs[date], serverRow);
        } else {
            next[date] = serverRow;  // not re-modified → adopt the merged day wholesale
        }
    }
    return next;
}

/** Copy the server row's `_lastModified` tokens (day + per-record) onto the local
 *  log, keeping all local content. For a re-modified-mid-sync date (see
 *  adoptUploadResults). Pure shallow copy. */
function advanceRecordTokens(localLog, serverRow) {
    const out = { ...localLog };
    if (serverRow._lastModified) out._lastModified = serverRow._lastModified;
    for (const [key, val] of Object.entries(localLog)) {
        const srvRec = serverRow[key];
        if (val && typeof val === 'object' && srvRec && srvRec._lastModified) {
            out[key] = { ...val, _lastModified: srvRec._lastModified };
        }
    }
    return out;
}

/**
 * Force-sync upload payload: stamp the local log's records with the SERVER's
 * current tokens (`serverLog`'s day + per-exercise `_lastModified`) so each
 * forced overwrite passes per-record arbitration. A record the server lacks gets
 * no base (inserted). Distinct from `withBaseTokens` (which echoes the local
 * log's own last-seen tokens for the normal sync path). Pure shallow copy.
 */
export function withServerTokens(localLog, serverLog) {
    const srv = serverLog || {};
    const out = {};
    for (const [key, val] of Object.entries(localLog)) {
        const srvRec = srv[key];
        if (val && typeof val === 'object' && srvRec && srvRec._lastModified) {
            out[key] = { ...val, _baseLastModifiedAt: srvRec._lastModified };
        } else {
            out[key] = val;
        }
    }
    if (srv._lastModified) out._baseLastModifiedAt = srv._lastModified;
    return out;
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
                    // Echo the server's CURRENT per-record tokens so each forced
                    // overwrite passes per-record arbitration (R3). The reconciled
                    // day comes back in `results` and is adopted by the caller.
                    uploadLogs[date] = withServerTokens(localLog, serverLog);
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
                uploadLogs[date] = withServerTokens(localLog, undefined);  // local-only → insert
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
