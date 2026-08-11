/**
 * Coach sync-logic — PURE decision functions extracted from store.js.
 *
 * Everything here is a pure `(state, inputs) -> nextState | decision` function:
 * no Preact signals, no LocalForage, no fetch, no Date, no notifications. The
 * store keeps the thin read-signal -> call -> assign -> persist wrappers; this
 * module is unit-tested in isolation (test/js/coach-sync-logic.test.js).
 */
import { clearApplied } from '../shared/dirty-set.js';

const SESSION_FEEDBACK_KEY = 'session_feedback';
const BASE_TOKEN_KEY = '_baseLastModifiedAt';
// Day-level keys that are not exercise entries.
const NON_EXERCISE_KEYS = ['_lastModifiedAt', '_lastModifiedBy', SESSION_FEEDBACK_KEY];

// ---- Content predicates --------------------------------------------------

export function logHasExerciseContent(log) {
    return Object.entries(log).some(([key, val]) => {
        if (NON_EXERCISE_KEYS.includes(key)) return false;
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

/** A day carrying at least one pending local tombstone. Such a day must
 *  upload even when it has no other content and was never synced — the
 *  delete intent has to reach the server. */
export function logHasPendingDeletions(log) {
    return Object.values(log).some(val => isDeletedEntry(val));
}

/**
 * Merge a content write into one exercise entry. Normally a shallow merge over
 * the existing entry — EXCEPT when the existing entry is a pending delete
 * tombstone: writing over a tombstone is a deliberate RE-ADD. The `_deleted`
 * flag is dropped (a plain spread would keep it and the server would process
 * the re-added session as a deletion, silently discarding it), the tombstone's
 * server stamp is KEPT, and the entry is marked `_readd: true`:
 *   - delete not yet uploaded → the server row still exists; the kept stamp
 *     is the base token that lets the re-add win as a normal update (a
 *     token-less create would be rejected as a hard cutover and the OLD
 *     session would come back).
 *   - delete already accepted server-side → the row is gone and a server
 *     tombstone exists; `_readd` tells the resurrection guard this client SAW
 *     the delete (it authored it), so the insert is accepted and the
 *     tombstone cleared — while stale edits without the marker stay rejected.
 * `_readd` is transient: the server never stores or echoes it, so adopting
 * any sync result clears it. Pure; returns a new log.
 */
export function withEntryUpdated(log, exerciseId, data) {
    const prev = log[exerciseId];
    if (!isDeletedEntry(prev)) {
        return { ...log, [exerciseId]: { ...prev, ...data } };
    }
    const readd = { ...data, _readd: true };
    if (prev._lastModified) readd._lastModified = prev._lastModified;
    return { ...log, [exerciseId]: readd };
}

/**
 * Delete one exercise entry from a day's log: the entry becomes a `_deleted`
 * tombstone. A synced entry keeps its server `_lastModified` stamp so
 * `withBaseTokens` echoes it and the server arbitrates the delete against
 * concurrent edits. An UNSTAMPED entry (uploaded but the response was lost,
 * or never uploaded) still becomes a tombstone — without a base token the
 * server rejects the delete against an existing row (hard cutover) and
 * returns the row, which the client adopts; the user deletes once more with
 * the fresh stamp and converges. Removing the key instead (the old behavior)
 * silently resurrected the server's copy on the next pull with no local
 * record of the delete intent. Pure; returns a new log.
 */
export function withEntryDeleted(log, exerciseId) {
    const entry = log[exerciseId];
    if (!entry || typeof entry !== 'object') return log;
    const next = { ...log };
    next[exerciseId] = entry._lastModified
        ? { _deleted: true, _lastModified: entry._lastModified }
        : { _deleted: true };
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
        if (logHasUploadableContent(log) || logIsSyncedToServer(log) || logHasPendingDeletions(log)) {
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
 * each record's fresh `_lastModified` token. A re-modified date stays dirty and
 * re-uploads next cycle, and is reconciled one record at a time — see
 * advanceRecordTokens, where "the user re-edited this" is told apart from "the
 * server ruled on this". Replaces R1-2a's applyAcceptedTokens +
 * adoptRejectedServerRows with one mechanism.
 *
 * Two nullabilities are load-bearing and different:
 *  - `snapshotGens` null disables re-modification detection entirely, so every
 *    result is adopted wholesale. An EMPTY object is not the same thing: against
 *    it any date with a live generation compares unequal and counts as
 *    re-modified.
 *  - `uploadedLogs` null (the `logsToUpload` map actually sent) means nothing is
 *    known to have been uploaded, so no record of a re-modified date may have a
 *    verdict applied: every one is treated as touched mid-sync and keeps its
 *    local content.
 *
 * @returns {Object} next logs
 */
export function adoptUploadResults(localLogs, results, snapshotGens, dirtyDateGenerations, uploadedLogs = null) {
    if (!results) return localLogs;
    const next = { ...localLogs };
    for (const [date, serverRow] of Object.entries(results)) {
        if (!serverRow) continue;
        const reModified = snapshotGens && dirtyDateGenerations[date] !== snapshotGens[date];
        if (reModified) {
            // The generation says the DAY moved, not which record did, so the
            // verdict is applied per record — see advanceRecordTokens.
            next[date] = advanceRecordTokens(localLogs[date] || {}, serverRow, uploadedLogs?.[date]);
        } else {
            next[date] = serverRow;  // not re-modified → adopt the merged day wholesale
        }
    }
    return next;
}

/**
 * Apply the server's arbitration to a date the user re-modified mid-sync,
 * record by record.
 *
 * Re-modification is a property of the DATE — one generation counter for the
 * whole day — so it says nothing about WHICH record the user touched. Deciding
 * per record is the whole of this function, and getting it wrong destroys data:
 *
 *  - A record that was uploaded and is STILL IDENTICAL to what went on the wire
 *    has been arbitrated, and the server row is its verdict, so adopt it. Merely
 *    advancing such a record's token to the winning stamp left stale local
 *    content sitting behind a fresh base: next cycle it passed the server's
 *    `stored <= base` test and overwrote the other client's newer edit, silently
 *    and on every device.
 *  - A record that genuinely differs from the uploaded copy IS the mid-sync
 *    re-edit. Keep it (the date stays dirty and re-uploads) and ADVANCE its
 *    token, or the retry would echo the stale pre-sync base that the
 *    now-advanced server rejects — losing the re-edit.
 *
 * Two records do not follow the plain rule:
 *  - `session_feedback` is arbitrated on the DAY token (the server row carries no
 *    per-record stamp for it), so its verdict and that token move together. The
 *    day token advances either way: with the verdict when the feedback settled,
 *    ahead of the re-edit when it did not.
 *  - A tombstone's token is NEVER advanced. Either its verdict applies — the
 *    server row carrying the key means the delete was REJECTED (a newer remote
 *    edit won), so adopt the surviving record; the key being absent means it was
 *    ACCEPTED, so drop the tombstone — or it was created mid-sync, has not been
 *    arbitrated at all, and waits verbatim with its original base for the next
 *    cycle.
 *
 * Pure; returns a new log.
 */
function advanceRecordTokens(localLog, serverRow, uploadedLog = null) {
    const out = { ...localLog };
    if (wasArbitrated(localLog, uploadedLog, SESSION_FEEDBACK_KEY)) {
        applyVerdict(out, serverRow, SESSION_FEEDBACK_KEY);
    }
    if (serverRow._lastModified) out._lastModified = serverRow._lastModified;

    for (const [key, val] of Object.entries(localLog)) {
        if (NON_EXERCISE_KEYS.includes(key) || !val || typeof val !== 'object' || Array.isArray(val)) continue;
        if (wasArbitrated(localLog, uploadedLog, key)) {
            applyVerdict(out, serverRow, key);
            continue;
        }
        if (isDeletedEntry(val)) continue;  // unarbitrated tombstone: base untouched
        const srvRec = serverRow[key];
        if (srvRec && srvRec._lastModified) {
            out[key] = { ...val, _lastModified: srvRec._lastModified };
        }
    }
    return out;
}

/** Was this record part of the upload and untouched since it was built? The
 *  uploaded copy differs from the stored one by exactly the echoed base token,
 *  so stripping that makes the two comparable.
 *
 *  A null `uploadedLog` means nothing is known to have been sent, and a record
 *  absent from it was created after the payload was built. Neither has been
 *  arbitrated, so neither may have a verdict applied. */
function wasArbitrated(localLog, uploadedLog, key) {
    const uploaded = uploadedLog?.[key];
    if (!uploaded || typeof uploaded !== 'object') return false;
    const local = localLog[key];
    if (!local || typeof local !== 'object') return false;
    const sent = { ...uploaded };
    delete sent[BASE_TOKEN_KEY];
    return jsonEqual(sent, local);
}

/** Take the server's outcome for one record: its reconciled row, or — when the
 *  merged day does not carry the key at all — its removal. */
function applyVerdict(out, serverRow, key) {
    const record = serverRow[key];
    if (record && typeof record === 'object') out[key] = record;
    else delete out[key];
}

/** Structural equality over JSON values (logs are JSON round-tripped on every
 *  local write, so there is nothing else to compare): key order is irrelevant,
 *  arrays compare element-wise. */
function jsonEqual(a, b) {
    if (a === b) return true;
    if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    if (Array.isArray(a)) {
        return a.length === b.length && a.every((item, i) => jsonEqual(item, b[i]));
    }
    const keys = Object.keys(a);
    if (keys.length !== Object.keys(b).length) return false;
    return keys.every(k => Object.prototype.hasOwnProperty.call(b, k) && jsonEqual(a[k], b[k]));
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
