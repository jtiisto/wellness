/**
 * Coach sync-logic — PURE decision functions extracted from store.js.
 *
 * Everything here is a pure `(state, inputs) -> nextState | decision` function:
 * no Preact signals, no LocalForage, no fetch, no Date, no notifications. The
 * store keeps the thin read-signal -> call -> assign -> persist wrappers; this
 * module is unit-tested in isolation (test/js/coach-sync-logic.test.js).
 */
import { clearApplied } from '../shared/dirty-set.js';

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

/** An entry the user deliberately deleted — a local tombstone awaiting upload.
 *  The server hard-deletes the row and the adopted `results` day (which lacks
 *  the key) clears the tombstone. */
export function isDeletedEntry(entry) {
    return !!(entry && typeof entry === 'object' && entry._deleted);
}

/**
 * Delete one exercise entry from a day's log. If the entry was ever synced
 * (it carries a server `_lastModified` stamp) it becomes a tombstone keeping
 * that stamp, so `withBaseTokens` echoes it and the server can arbitrate the
 * delete against concurrent edits. A never-synced entry is simply removed —
 * there is nothing server-side to delete. Pure; returns a new log.
 */
export function withEntryDeleted(log, exerciseId) {
    const entry = log[exerciseId];
    if (!entry || typeof entry !== 'object') return log;
    const next = { ...log };
    if (entry._lastModified) {
        next[exerciseId] = { _deleted: true, _lastModified: entry._lastModified };
    } else {
        delete next[exerciseId];
    }
    return next;
}

export function hasFeedbackContent(log) {
    const fb = log.session_feedback;
    return !!fb && (
        (fb.pain_discomfort && fb.pain_discomfort.trim()) ||
        (fb.general_notes && fb.general_notes.trim())
    );
}

// A log is worth uploading if it carries real exercise data OR non-empty session
// feedback. Feedback-only logs upload safely because the server arbitrates per
// record: a feedback-only payload updates only the feedback record and never
// touches the day's already-logged exercises.
export function logHasUploadableContent(log) {
    return logHasExerciseContent(log) || hasFeedbackContent(log);
}

// A log the server already knows: its day stamp (`_lastModified`) was adopted
// from a previous sync. An EMPTY log in this state expresses deletion — the
// user logged content, synced, then removed it — and must still be uploaded so
// the server clears its copy (each emptied record carries its base token, so
// per-record arbitration protects against clobbering newer remote data).
// Without this, an emptied synced day was skipped forever: dirty flag stuck
// red, the 30s poll re-syncing in a loop, and the server keeping deleted sets.
export function logIsSyncedToServer(log) {
    return !!log._lastModified;
}

// ---- Dirty-date state transitions ---------------------------------------

/**
 * Decide which dirty dates to clear after an upload.
 * Delegates to the shared DirtySet algorithm (shared/dirty-set.js) — one
 * implementation for journal trackers, journal entries, and coach dates.
 *
 * @returns {{dirtyDates: string[], dirtyDateGenerations: Object}}
 */
export function nextDirtyAfterApply(appliedDates, snapshotGens, dirtyDates, dirtyDateGenerations) {
    const next = clearApplied(dirtyDates, dirtyDateGenerations, appliedDates, snapshotGens);
    return { dirtyDates: next.keys, dirtyDateGenerations: next.generations };
}

/**
 * Build the upload set from dirty dates + local logs. A date's log is included
 * when it carries uploadable content OR the server already knows the day
 * (token-bearing empty log = deletion update). `uploadedDates` is exactly the
 * set of dates actually sent — the invariant that keeps "dirty cleared == sent".
 *
 * `unsatisfiableDates` are dirty dates that can NEVER become uploadable: the
 * log is missing entirely (window-pruned), or empty and never synced (nothing
 * to send and nothing server-side to delete). Leaving them dirty wedges the
 * client — permanent red status and a full sync every 30s poll — so the caller
 * drops them from the dirty set (with a debug breadcrumb).
 *
 * @returns {{logsToUpload: Object, uploadedDates: string[], unsatisfiableDates: string[]}}
 */
export function selectLogsToUpload(dirtyDates, localLogs) {
    const logsToUpload = {};
    const unsatisfiableDates = [];
    for (const date of dirtyDates) {
        const log = localLogs[date];
        if (!log) {
            unsatisfiableDates.push(date);  // pruned out of the window
            continue;
        }
        if (logHasUploadableContent(log) || logIsSyncedToServer(log)) {
            logsToUpload[date] = withBaseTokens(log);
        } else {
            unsatisfiableDates.push(date);  // empty + never synced
        }
    }
    return {
        logsToUpload,
        uploadedDates: Object.keys(logsToUpload),
        unsatisfiableDates,
    };
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

// ---- Window pruning + plan version --------------------------------------

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
