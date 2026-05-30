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
    nextDirtyAfterReject,
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

test('selectLogsToUpload: sends content + feedback-only, skips empty/missing', () => {
    const logs = {
        d1: { ex_1: { sets: [{ reps: 5 }] } },         // exercise content → sent
        d2: { session_feedback: {} },                   // truly empty → skip
        d3: { session_feedback: { general_notes: 'n' } }, // feedback-only → sent
        // d4 has no local log object at all
    };
    const r = selectLogsToUpload(['d1', 'd2', 'd3', 'd4'], logs);
    assert.deepEqual(Object.keys(r.logsToUpload).sort(), ['d1', 'd3']);
    assert.deepEqual(r.uploadedDates.sort(), ['d1', 'd3']);
    // The skipped/missing dates are NOT reported as uploaded → they stay dirty.
    assert.ok(!r.uploadedDates.includes('d2'));
    assert.ok(!r.uploadedDates.includes('d4'));
});

// ---- nextDirtyAfterReject -------------------------------------------------

test('nextDirtyAfterReject: drops rejected dates and their generations', () => {
    const r = nextDirtyAfterReject(
        ['d1', 'd2', 'd3'],
        { d1: 1, d2: 3, d3: 2 },
        ['d2'],
    );
    assert.deepEqual(r.dirtyDates, ['d1', 'd3']);
    assert.deepEqual(r.dirtyDateGenerations, { d1: 1, d3: 2 });
});
