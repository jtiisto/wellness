// Unit tests for the pure coach sync-logic (extracted from coach/store.js).
// These pin the dirty-clearing + upload-selection invariants that a recent
// data-loss bug (feedback-only logs cleared-but-not-uploaded) violated.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    logHasExerciseContent,
    hasFeedbackContent,
    logHasUploadableContent,
    nextDirtyAfterApply,
    selectLogsToUpload,
    pruneOlderThan,
    maxPlanVersion,
    withBaseTokens,
    adoptUploadResults,
    isDeletedEntry,
    withEntryDeleted,
    withEntryUpdated,
    logHasPendingDeletions,
} from '../../public/js/coach/sync-logic.js';

// ---- content predicates --------------------------------------------------

test('logHasExerciseContent: true only for real logged data', () => {
    assert.equal(logHasExerciseContent({}), false);
    assert.equal(logHasExerciseContent({ _lastModifiedAt: 'x', _lastModifiedBy: 'y' }), false);
    assert.equal(logHasExerciseContent({ session_feedback: { general_notes: 'hi' } }), false);
    assert.equal(logHasExerciseContent({ ex_1: { sets: [] } }), false);
    assert.equal(logHasExerciseContent({ ex_1: { duration_min: null } }), false);
    assert.equal(logHasExerciseContent({ ex_1: { sets: [{ reps: 5 }] } }), true);
    assert.equal(logHasExerciseContent({ ex_1: { completed_items: ['a'] } }), true);
    assert.equal(logHasExerciseContent({ ex_1: { duration_min: 15 } }), true);
});

test('hasFeedbackContent: true only for non-empty feedback', () => {
    assert.ok(!hasFeedbackContent({}));
    assert.ok(!hasFeedbackContent({ session_feedback: {} }));
    assert.ok(!hasFeedbackContent({ session_feedback: { general_notes: '   ' } }));
    assert.ok(hasFeedbackContent({ session_feedback: { pain_discomfort: 'sore' } }));
    assert.ok(hasFeedbackContent({ session_feedback: { general_notes: 'felt good' } }));
});

test('logHasUploadableContent: exercise data OR feedback, but not empty', () => {
    assert.ok(!logHasUploadableContent({ session_feedback: {} }));
    assert.ok(!logHasUploadableContent({ _lastModifiedAt: 'x' }));
    assert.ok(logHasUploadableContent({ session_feedback: { general_notes: 'note' } })); // feedback-only
    assert.ok(logHasUploadableContent({ ex_1: { sets: [{ reps: 5 }] } }));
});

// ---- nextDirtyAfterApply (the clear-on-upload rule) ----------------------

test('nextDirtyAfterApply: applied + generation unchanged → cleared', () => {
    const r = nextDirtyAfterApply(
        ['2026-05-01', '2026-05-02'],            // appliedDates
        { '2026-05-01': 1, '2026-05-02': 1 },    // snapshotGens
        ['2026-05-01', '2026-05-02'],            // dirtyDates
        { '2026-05-01': 1, '2026-05-02': 1 },    // current gens
    );
    assert.deepEqual(r.dirtyDates, []);
    assert.deepEqual(r.dirtyDateGenerations, {});
});

test('nextDirtyAfterApply: re-modified during sync (gen advanced) stays dirty', () => {
    const r = nextDirtyAfterApply(
        ['2026-05-01', '2026-05-02'],
        { '2026-05-01': 1, '2026-05-02': 1 },    // snapshot
        ['2026-05-01', '2026-05-02'],
        { '2026-05-01': 2, '2026-05-02': 1 },    // 05-01 edited mid-sync
    );
    assert.deepEqual(r.dirtyDates, ['2026-05-01']);
    assert.deepEqual(r.dirtyDateGenerations, { '2026-05-01': 2 });
});

test('nextDirtyAfterApply: a not-applied dirty date is kept', () => {
    const r = nextDirtyAfterApply(
        ['2026-05-01'],                           // only 05-01 applied
        { '2026-05-01': 1 },
        ['2026-05-01', '2026-05-02'],
        { '2026-05-01': 1, '2026-05-02': 1 },
    );
    assert.deepEqual(r.dirtyDates, ['2026-05-02']);
    assert.deepEqual(r.dirtyDateGenerations, { '2026-05-02': 1 });
});

// ---- selectLogsToUpload (the "cleared == sent" invariant) ----------------

test('selectLogsToUpload: sends content + feedback-only; empty-never-synced and missing are unsatisfiable', () => {
    const logs = {
        d1: { ex_1: { sets: [{ reps: 5 }] } },         // exercise content → sent
        d2: { session_feedback: {} },                   // empty, never synced → unsatisfiable
        d3: { session_feedback: { general_notes: 'n' } }, // feedback-only → sent
        // d4 has no local log object at all (window-pruned) → unsatisfiable
    };
    const r = selectLogsToUpload(['d1', 'd2', 'd3', 'd4'], logs);
    assert.deepEqual(Object.keys(r.logsToUpload).sort(), ['d1', 'd3']);
    assert.deepEqual(r.uploadedDates.sort(), ['d1', 'd3']);
    // Unsatisfiable dates are reported so the caller can drop them from the
    // dirty set instead of leaving the client wedged red forever.
    assert.deepEqual(r.unsatisfiableDates.sort(), ['d2', 'd4']);
});

test('selectLogsToUpload: a token-bearing EMPTY log uploads as a deletion update', () => {
    // The user logged a set, synced (so the day carries server tokens), then
    // deleted the set. The emptied log must still upload so the server clears
    // its copy — per-record base tokens make the overwrite arbitration-safe.
    const logs = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: {},
            ex_1: { _lastModified: 'ex-tok', sets: [] },
        },
    };
    const r = selectLogsToUpload(['d1'], logs);
    assert.deepEqual(r.uploadedDates, ['d1']);
    assert.deepEqual(r.unsatisfiableDates, []);
    assert.equal(r.logsToUpload.d1._baseLastModifiedAt, 'day-tok');
    assert.equal(r.logsToUpload.d1.ex_1._baseLastModifiedAt, 'ex-tok');
    assert.deepEqual(r.logsToUpload.d1.ex_1.sets, []);
});

// ---- R3 per-record token protocol ----------------------------------------

test('withBaseTokens: echoes day + per-exercise _lastModified as _baseLastModifiedAt', () => {
    const log = {
        _lastModified: 'day1',
        session_feedback: { general_notes: 'n' },          // object, no token → unchanged
        ex_1: { _lastModified: 'ex1', sets: [{ reps: 5 }] },
        ex_new: { sets: [{ reps: 3 }] },                    // no token → insert
    };
    const out = withBaseTokens(log);
    assert.equal(out._baseLastModifiedAt, 'day1');
    assert.equal(out.ex_1._baseLastModifiedAt, 'ex1');
    assert.ok(!('_baseLastModifiedAt' in out.ex_new));
    assert.ok(!('_baseLastModifiedAt' in out.session_feedback));
});

test('selectLogsToUpload: attaches per-record base tokens to sent logs', () => {
    const logs = {
        d1: { _lastModified: 'day1', ex_1: { _lastModified: 'ex1', sets: [{ reps: 5 }] } },
    };
    const { logsToUpload } = selectLogsToUpload(['d1'], logs);
    assert.equal(logsToUpload.d1._baseLastModifiedAt, 'day1');
    assert.equal(logsToUpload.d1.ex_1._baseLastModifiedAt, 'ex1');
});

test('adoptUploadResults: adopts merged serverRow for non-re-modified dates', () => {
    const local = { d1: { ex_1: { local: true } } };
    const results = { d1: { ex_1: { server: true, _lastModified: 's1' } } };
    const next = adoptUploadResults(local, results, { d1: 1 }, { d1: 1 });
    assert.deepEqual(next.d1, results.d1);                   // adopted wholesale
    assert.deepEqual(local.d1, { ex_1: { local: true } });  // input not mutated
});

test('adoptUploadResults: re-modified date keeps local content but advances tokens', () => {
    const local = {
        d2: { _lastModified: 'old-day', ex_1: { reps: 32, _lastModified: 'old-ex' } },
    };
    const results = {
        d2: { _lastModified: 'srv-day', ex_1: { reps: 28, _lastModified: 'srv-ex' } },
    };
    // d2 re-modified mid-sync (gen advanced) → keep local reps (32) but take the
    // server's tokens, so the next upload echoes a fresh base (the race-loss fix).
    const next = adoptUploadResults(local, results, { d2: 1 }, { d2: 2 });
    assert.equal(next.d2.ex_1.reps, 32);                  // local re-edit kept
    assert.equal(next.d2.ex_1._lastModified, 'srv-ex');   // token advanced
    assert.equal(next.d2._lastModified, 'srv-day');       // day token advanced
});

// ---- entry deletion (tombstones) ------------------------------------------

test('isDeletedEntry: true only for _deleted-marked entry objects', () => {
    assert.equal(isDeletedEntry({ _deleted: true, _lastModified: 't' }), true);
    assert.equal(isDeletedEntry({ duration_min: 45 }), false);
    assert.equal(isDeletedEntry(null), false);
    assert.equal(isDeletedEntry(undefined), false);
    assert.equal(isDeletedEntry('str'), false);
});

test('withEntryDeleted: synced entry becomes a tombstone keeping its server stamp', () => {
    const log = {
        _lastModified: 'day-tok',
        session_feedback: {},
        extra_zone2: { duration_min: 45, avg_hr: 128, _lastModified: 'ex-tok' },
    };
    const next = withEntryDeleted(log, 'extra_zone2');
    assert.deepEqual(next.extra_zone2, { _deleted: true, _lastModified: 'ex-tok' });
    assert.equal(log.extra_zone2.duration_min, 45); // input not mutated
});

test('withEntryDeleted: unstamped entry still becomes a tombstone (no silent resurrection)', () => {
    // The entry may exist server-side (upload response lost) — removing the
    // key outright let the server copy resurrect on the next pull with no
    // record of the delete intent. A stampless tombstone uploads, is rejected
    // (hard cutover), and the adopted server row lets the user re-delete.
    const log = { session_feedback: {}, extra_zone2: { duration_min: 45 } };
    const next = withEntryDeleted(log, 'extra_zone2');
    assert.deepEqual(next.extra_zone2, { _deleted: true });
});

test('withEntryDeleted: missing key is a no-op', () => {
    const log = { session_feedback: {} };
    assert.equal(withEntryDeleted(log, 'nope'), log);
});

test('withEntryUpdated: plain merge over a normal entry', () => {
    const log = { ex_1: { duration_min: 30, _lastModified: 't1' } };
    const next = withEntryUpdated(log, 'ex_1', { avg_hr: 128 });
    assert.deepEqual(next.ex_1, { duration_min: 30, avg_hr: 128, _lastModified: 't1' });
});

test('withEntryUpdated: write over a pending tombstone becomes a marked re-add keeping the stamp', () => {
    // The H1 data-loss bug: spreading over the tombstone kept _deleted: true
    // (and the old stamp), so the server processed the re-added session as a
    // deletion. The re-add drops _deleted, KEEPS the stamp (base token that
    // wins over the still-live server row when the delete never uploaded),
    // and carries _readd so the server's resurrection guard accepts it when
    // the delete DID land.
    const log = {
        _lastModified: 'day-tok',
        extra_zone2: { _deleted: true, _lastModified: 'ex-tok' },
    };
    const next = withEntryUpdated(log, 'extra_zone2', { duration_min: 45, avg_hr: 128 });
    assert.deepEqual(next.extra_zone2, {
        duration_min: 45, avg_hr: 128, _readd: true, _lastModified: 'ex-tok',
    });
    assert.ok(!('_deleted' in next.extra_zone2));
});

test('withEntryUpdated: re-add over an UNSTAMPED tombstone carries no stamp', () => {
    const log = { extra_zone2: { _deleted: true } };
    const next = withEntryUpdated(log, 'extra_zone2', { duration_min: 45 });
    assert.deepEqual(next.extra_zone2, { duration_min: 45, _readd: true });
});

test('logHasPendingDeletions + selectLogsToUpload: a tombstone-only never-synced day uploads', () => {
    const logs = {
        d1: { session_feedback: {}, extra_zone2: { _deleted: true } },
    };
    assert.ok(logHasPendingDeletions(logs.d1));
    const r = selectLogsToUpload(['d1'], logs);
    assert.deepEqual(r.uploadedDates, ['d1']);
    assert.deepEqual(r.unsatisfiableDates, []);
    // No stamp → no base token → the server arbitrates it as a hard-cutover.
    assert.ok(!('_baseLastModifiedAt' in r.logsToUpload.d1.extra_zone2));
});

test('tombstone day uploads and echoes the tombstone base token', () => {
    // The tombstone-only day carries no exercise content, but the day was
    // synced (day _lastModified present) so it rides the deletion-update path.
    const logs = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: {},
            extra_zone2: { _deleted: true, _lastModified: 'ex-tok' },
        },
    };
    const r = selectLogsToUpload(['d1'], logs);
    assert.deepEqual(r.uploadedDates, ['d1']);
    assert.equal(r.logsToUpload.d1.extra_zone2._deleted, true);
    assert.equal(r.logsToUpload.d1.extra_zone2._baseLastModifiedAt, 'ex-tok');
});

test('adoptUploadResults: adopting the serverRow (without the key) clears the tombstone', () => {
    const local = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: {},
            extra_zone2: { _deleted: true, _lastModified: 'ex-tok' },
        },
    };
    // Accepted delete: the reconciled server day simply lacks the key.
    const results = { d1: { session_feedback: {}, _lastModified: 'srv-day' } };
    const next = adoptUploadResults(local, results, { d1: 1 }, { d1: 1 });
    assert.ok(!('extra_zone2' in next.d1));
    assert.equal(next.d1._lastModified, 'srv-day');
});

test('adoptUploadResults re-modified: REJECTED delete adopts the surviving server record (never advances the tombstone token)', () => {
    // The F4 bug: advancing the tombstone's token to the server stamp made the
    // next retry's base match, turning the rejected delete into an accepted
    // one — destroying the other client's newer edit. An uploaded tombstone
    // has been arbitrated: serverRow carrying the key IS the verdict.
    const uploaded = {
        d1: { extra_zone2: { _deleted: true, _lastModified: 't1', _baseLastModifiedAt: 't1' } },
    };
    const local = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: { general_notes: 'mid-sync edit' },
            extra_zone2: { _deleted: true, _lastModified: 't1' },
        },
    };
    const results = {
        d1: {
            session_feedback: {},
            _lastModified: 'srv-day',
            extra_zone2: { duration_min: 60, _lastModified: 't2' },  // remote edit won
        },
    };
    const next = adoptUploadResults(local, results, { d1: 1 }, { d1: 2 }, uploaded);
    assert.deepEqual(next.d1.extra_zone2, { duration_min: 60, _lastModified: 't2' });
    assert.equal(next.d1.session_feedback.general_notes, 'mid-sync edit'); // re-edit kept
});

test('adoptUploadResults re-modified: ACCEPTED delete drops the tombstone', () => {
    const uploaded = {
        d1: { extra_zone2: { _deleted: true, _lastModified: 't1', _baseLastModifiedAt: 't1' } },
    };
    const local = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: { general_notes: 'mid-sync edit' },
            extra_zone2: { _deleted: true, _lastModified: 't1' },
        },
    };
    const results = { d1: { session_feedback: {}, _lastModified: 'srv-day' } };  // key gone
    const next = adoptUploadResults(local, results, { d1: 1 }, { d1: 2 }, uploaded);
    assert.ok(!('extra_zone2' in next.d1));
    assert.equal(next.d1._lastModified, 'srv-day');
});

test('adoptUploadResults re-modified: a tombstone created MID-SYNC is kept, token untouched', () => {
    // Not in the upload → not yet arbitrated. It must survive with its
    // original base so the next cycle arbitrates it properly.
    const uploaded = { d1: { ex_other: { reps: 5, _baseLastModifiedAt: 'o1' } } };
    const local = {
        d1: {
            _lastModified: 'day-tok',
            session_feedback: {},
            extra_zone2: { _deleted: true, _lastModified: 't1' },  // deleted mid-sync
        },
    };
    const results = {
        d1: {
            session_feedback: {},
            _lastModified: 'srv-day',
            extra_zone2: { duration_min: 45, _lastModified: 't1' },
        },
    };
    const next = adoptUploadResults(local, results, { d1: 1 }, { d1: 2 }, uploaded);
    assert.deepEqual(next.d1.extra_zone2, { _deleted: true, _lastModified: 't1' });
});

// ---- pruneOlderThan / maxPlanVersion -------------------------------------

test('pruneOlderThan: keeps dates >= cutoff', () => {
    const m = { '2026-04-30': 'a', '2026-05-01': 'b', '2026-05-02': 'c' };
    assert.deepEqual(pruneOlderThan(m, '2026-05-01'), { '2026-05-01': 'b', '2026-05-02': 'c' });
});

test('maxPlanVersion: latest _lastModified, never below currentMax', () => {
    const plans = {
        d1: { _lastModified: '2026-05-01T00:00:00Z' },
        d2: { _lastModified: '2026-05-03T00:00:00Z' },
    };
    assert.equal(maxPlanVersion(plans, null), '2026-05-03T00:00:00Z');
    assert.equal(maxPlanVersion(plans, '2026-05-09T00:00:00Z'), '2026-05-09T00:00:00Z');
    assert.equal(maxPlanVersion({}, '2026-05-01T00:00:00Z'), '2026-05-01T00:00:00Z');
});
