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
    resolveForceSyncLogs,
    pruneOlderThan,
    maxPlanVersion,
    rejectedDates,
    applyAcceptedTokens,
    adoptRejectedServerRows,
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

// ---- R1 token protocol ---------------------------------------------------

test('selectLogsToUpload: echoes _lastModified as _baseLastModifiedAt; new date omits it', () => {
    const logs = {
        d1: { _lastModified: '2026-05-01T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } },
        d2: { ex_1: { sets: [{ reps: 3 }] } },  // brand-new local date, no server stamp
    };
    const { logsToUpload } = selectLogsToUpload(['d1', 'd2'], logs);
    assert.equal(logsToUpload.d1._baseLastModifiedAt, '2026-05-01T00:00:00Z');
    assert.ok(!('_baseLastModifiedAt' in logsToUpload.d2));  // insert-if-absent
});

test('rejectedDates: extracts dates from the structured rejectedLogs', () => {
    assert.deepEqual(
        rejectedDates([{ date: 'd1', serverRow: {} }, { date: 'd2', serverRow: null }]),
        ['d1', 'd2'],
    );
    assert.deepEqual(rejectedDates(undefined), []);
});

test('applyAcceptedTokens: advances _lastModified to serverTime for applied dates only', () => {
    const logs = {
        d1: { _lastModified: 'old', ex_1: {} },
        d2: { _lastModified: 'keep', ex_1: {} },
    };
    const next = applyAcceptedTokens(logs, ['d1'], 'srv-now');
    assert.equal(next.d1._lastModified, 'srv-now');  // advanced
    assert.equal(next.d2._lastModified, 'keep');     // untouched
    assert.equal(logs.d1._lastModified, 'old');      // input not mutated
});

test('applyAcceptedTokens: no applied dates or no serverTime is a no-op', () => {
    const logs = { d1: { _lastModified: 'old' } };
    assert.equal(applyAcceptedTokens(logs, [], 'srv'), logs);
    assert.equal(applyAcceptedTokens(logs, ['d1'], null), logs);
});

test('adoptRejectedServerRows: replaces local with serverRow; null serverRow left intact', () => {
    const logs = { d1: { local: true }, d2: { local: true } };
    const next = adoptRejectedServerRows(logs, [
        { date: 'd1', serverRow: { server: true, _lastModified: 's1' } },
        { date: 'd2', serverRow: null },
    ]);
    assert.deepEqual(next.d1, { server: true, _lastModified: 's1' });  // adopted
    assert.deepEqual(next.d2, { local: true });                        // untouched
    assert.deepEqual(logs.d1, { local: true });                        // input not mutated
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

test('resolveForceSyncLogs: forced upload echoes the server stamp as the base token (R1)', () => {
    const local = { d1: { _lastModifiedAt: '2026-05-02T00:00:00Z', ex_1: { sets: [{ reps: 5 }] } } };
    const server = { d1: { _lastModified: '2026-05-01T00:00:00Z', ex_1: { sets: [{ reps: 3 }] } } };
    const { uploadLogs } = resolveForceSyncLogs(local, server, null);
    assert.equal(uploadLogs.d1._baseLastModifiedAt, '2026-05-01T00:00:00Z');
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
