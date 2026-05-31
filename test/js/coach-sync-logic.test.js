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
    resolveForceSyncLogs,
    pruneOlderThan,
    maxPlanVersion,
    withBaseTokens,
    withServerTokens,
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

test('withServerTokens: stamps local records with the SERVER\'s tokens (force-sync)', () => {
    const local = { ex_1: { sets: [{ reps: 9 }] }, ex_new: { sets: [{ reps: 1 }] } };
    const server = { _lastModified: 'sDay', ex_1: { _lastModified: 'sEx1' } };
    const out = withServerTokens(local, server);
    assert.equal(out._baseLastModifiedAt, 'sDay');
    assert.equal(out.ex_1._baseLastModifiedAt, 'sEx1');     // forces overwrite
    assert.ok(!('_baseLastModifiedAt' in out.ex_new));      // server lacks it → insert
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

// ---- resolveForceSyncLogs (force-sync LWW merge) -------------------------

test('resolveForceSyncLogs: local newer w/ content uploads; local newer but empty does not clobber', () => {
    const local = {
        d1: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
        d2: { _lastModifiedAt: '2026-05-02T00:00:00Z', session_feedback: {} },
    };
    const server = {
        d1: { _lastModified: '2026-05-01T00:00:00Z', ex_1: { sets: [{ reps: 3 }] } },
        d2: { _lastModified: '2026-05-01T00:00:00Z', ex_2: { sets: [{ reps: 8 }] } },
    };
    const { uploadLogs, mergedLogs, counts } = resolveForceSyncLogs(local, server, null);
    assert.deepEqual(Object.keys(uploadLogs), ['d1']);
    assert.equal(mergedLogs.d1, local.d1);    // local wins
    assert.equal(mergedLogs.d2, server.d2);   // empty local does not clobber server
    assert.equal(counts.uploaded, 1);
    assert.equal(counts.accepted, 1);
});

test('resolveForceSyncLogs: server newer accepted; equal timestamp keeps local', () => {
    const local = {
        d1: { _lastModifiedAt: '2026-05-01T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
        d2: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
    };
    const server = {
        d1: { _lastModified: '2026-05-03T00:00:00Z', ex_1: { sets: [{ reps: 9 }] } },
        d2: { _lastModified: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 1 }] } },
    };
    const { mergedLogs, counts } = resolveForceSyncLogs(local, server, null);
    assert.equal(mergedLogs.d1, server.d1);  // server newer
    assert.equal(mergedLogs.d2, local.d2);   // equal -> keep local
    assert.equal(counts.accepted, 1);
    assert.equal(counts.skipped, 1);
});

test('resolveForceSyncLogs: local-only out-of-window kept but not uploaded; server-only accepted', () => {
    const local = {
        '2026-01-01': { _lastModifiedAt: '2026-01-01T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
    };
    const server = {
        '2026-05-01': { _lastModified: '2026-05-01T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
    };
    const { uploadLogs, mergedLogs, counts } = resolveForceSyncLogs(local, server, '2026-03-01');
    assert.deepEqual(Object.keys(uploadLogs), []);
    assert.ok(mergedLogs['2026-01-01']);
    assert.equal(mergedLogs['2026-05-01'], server['2026-05-01']);
    assert.equal(counts.skipped, 1);
    assert.equal(counts.accepted, 1);
});

test('resolveForceSyncLogs: local-only with content uploads; empty local-only kept not uploaded', () => {
    const local = {
        d1: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
        d2: { _lastModifiedAt: '2026-05-02T00:00:00Z', session_feedback: {} },
    };
    const { uploadLogs, counts } = resolveForceSyncLogs(local, {}, null);
    assert.deepEqual(Object.keys(uploadLogs), ['d1']);
    assert.equal(counts.uploaded, 1);
    assert.equal(counts.skipped, 1);
});

// ---- pruneOlderThan / maxPlanVersion -------------------------------------

test('pruneOlderThan: keeps dates >= cutoff', () => {
    const m = { '2026-04-30': 'a', '2026-05-01': 'b', '2026-05-02': 'c' };
    assert.deepEqual(pruneOlderThan(m, '2026-05-01'), { '2026-05-01': 'b', '2026-05-02': 'c' });
});

test('resolveForceSyncLogs: forced upload echoes the server day + per-exercise tokens (R3)', () => {
    const local = { d1: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } } };
    const server = {
        d1: {
            _lastModified: '2026-05-01T00:00:00Z',
            ex_1: { _lastModified: '2026-05-01T00:00:00Z', sets: [{ reps: 3 }] },
        },
    };
    const { uploadLogs } = resolveForceSyncLogs(local, server, null);
    assert.equal(uploadLogs.d1._baseLastModifiedAt, '2026-05-01T00:00:00Z');
    assert.equal(uploadLogs.d1.ex_1._baseLastModifiedAt, '2026-05-01T00:00:00Z');  // forces overwrite
});

test('resolveForceSyncLogs: local-only forced upload omits the base token (insert)', () => {
    const local = { d1: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } } };
    const { uploadLogs } = resolveForceSyncLogs(local, {}, null);
    assert.ok(!('_baseLastModifiedAt' in uploadLogs.d1));
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
