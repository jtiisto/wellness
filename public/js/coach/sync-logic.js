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
        logsToUpload[date] = log;
    }
    return { logsToUpload, uploadedDates: Object.keys(logsToUpload) };
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
