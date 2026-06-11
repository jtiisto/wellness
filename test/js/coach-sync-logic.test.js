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
