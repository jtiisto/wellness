// Unit tests for the typed tracker-target model: parse/format, effective-dated
// targetForDate selection, and the apply-from-today target writer.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    SCHEDULE_GENESIS_DATE,
    parseTarget,
    formatTarget,
    targetForDate,
    computeTargetHistoryUpdate,
} from '../../public/js/journal/utils.js';

const TODAY = '2026-07-06';

// ---- parseTarget ---------------------------------------------------------

test('parseTarget: empty / whitespace / null → no target, no error', () => {
    assert.deepEqual(parseTarget('', 'positive'), { target: null, error: null });
    assert.deepEqual(parseTarget('   ', 'positive'), { target: null, error: null });
    assert.deepEqual(parseTarget(null, 'positive'), { target: null, error: null });
});

test('parseTarget: bare number is polarity-defaulted', () => {
    assert.deepEqual(parseTarget('10', 'positive').target, { min: 10 });
    assert.deepEqual(parseTarget('10', undefined).target, { min: 10 });
    assert.deepEqual(parseTarget('10', 'neutral').target, { min: 10 });
    assert.deepEqual(parseTarget('10', 'negative').target, { max: 10 });
});

test('parseTarget: range "A-B"', () => {
    assert.deepEqual(parseTarget('150-170', 'positive').target, { min: 150, max: 170 });
    assert.deepEqual(parseTarget('150 - 170', 'positive').target, { min: 150, max: 170 });
    assert.deepEqual(parseTarget('10-10', 'positive').target, { min: 10, max: 10 });
});

test('parseTarget: decimals + surrounding whitespace', () => {
    assert.deepEqual(parseTarget('1.5', 'positive').target, { min: 1.5 });
    assert.deepEqual(parseTarget('  10  ', 'positive').target, { min: 10 });
});

test('parseTarget: invalid → error, null target', () => {
    assert.equal(parseTarget('170-150', 'positive').target, null);
    assert.ok(parseTarget('170-150', 'positive').error);
    assert.ok(parseTarget('abc', 'positive').error);
    assert.ok(parseTarget('-5', 'positive').error);      // leading minus rejected
    assert.ok(parseTarget('10x', 'positive').error);
});

// ---- formatTarget --------------------------------------------------------

test('formatTarget: bounds + unit', () => {
    assert.equal(formatTarget(null), '');
    assert.equal(formatTarget({}), '');
    assert.equal(formatTarget({ min: 10 }), '≥ 10');
    assert.equal(formatTarget({ min: 10 }, 'g'), '≥ 10 g');
    assert.equal(formatTarget({ max: 2 }), '≤ 2');
    assert.equal(formatTarget({ min: 150, max: 170 }, 'g'), '150–170 g');
    assert.equal(formatTarget({ min: 8, max: 8 }, 'g'), '8 g');   // exact
});

// ---- targetForDate (effective-dated) -------------------------------------

test('targetForDate: absent history → null', () => {
    assert.equal(targetForDate({ id: 't' }, TODAY), null);
    assert.equal(targetForDate({ id: 't', targetHistory: [] }, TODAY), null);
});

test('targetForDate: picks the segment in effect (past vs latest)', () => {
    const t = {
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 120 } },
            { effectiveFrom: '2026-07-01', target: { min: 150 } },
        ],
    };
    assert.deepEqual(targetForDate(t, '2020-01-01'), { min: 120 }); // before change
    assert.deepEqual(targetForDate(t, '2026-07-01'), { min: 150 }); // on boundary
    assert.deepEqual(targetForDate(t, TODAY), { min: 150 });        // after
});

test('targetForDate: a null-target segment reads as no target', () => {
    const t = {
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 120 } },
            { effectiveFrom: '2026-07-01', target: null },   // target removed
        ],
    };
    assert.deepEqual(targetForDate(t, '2020-01-01'), { min: 120 });
    assert.equal(targetForDate(t, TODAY), null);
});

// ---- computeTargetHistoryUpdate (apply-from-today) -----------------------

test('computeTargetHistoryUpdate: no-op when unchanged (same ref)', () => {
    const history = [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 10 } }];
    const t = { targetHistory: history };
    const res = computeTargetHistoryUpdate(t, { min: 10 }, TODAY);
    assert.equal(res.changed, false);
    assert.equal(res.targetHistory, history);
});

test('computeTargetHistoryUpdate: first edit splits genesis (no prior target)', () => {
    const res = computeTargetHistoryUpdate({ id: 't' }, { min: 150, max: 170 }, TODAY);
    assert.equal(res.changed, true);
    assert.deepEqual(res.targetHistory, [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, target: null },   // past: no target
        { effectiveFrom: TODAY, target: { min: 150, max: 170 } },
    ]);
});

test('computeTargetHistoryUpdate: later change appends a segment', () => {
    const t = {
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: null },
            { effectiveFrom: '2026-06-01', target: { min: 120 } },
        ],
    };
    const res = computeTargetHistoryUpdate(t, { min: 150 }, TODAY);
    assert.equal(res.changed, true);
    assert.equal(res.targetHistory.length, 3);
    assert.deepEqual(res.targetHistory[2], { effectiveFrom: TODAY, target: { min: 150 } });
});

test('computeTargetHistoryUpdate: same-day re-edit replaces the latest segment', () => {
    const t = {
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: null },
            { effectiveFrom: TODAY, target: { min: 120 } },
        ],
    };
    const res = computeTargetHistoryUpdate(t, { min: 150 }, TODAY);
    assert.equal(res.changed, true);
    assert.equal(res.targetHistory.length, 2);       // replaced, not appended
    assert.deepEqual(res.targetHistory[1], { effectiveFrom: TODAY, target: { min: 150 } });
});

test('computeTargetHistoryUpdate: clearing a target records a null-target segment', () => {
    const t = {
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 10 } }],
    };
    const res = computeTargetHistoryUpdate(t, null, TODAY);
    assert.equal(res.changed, true);
    assert.deepEqual(res.targetHistory[res.targetHistory.length - 1],
        { effectiveFrom: TODAY, target: null });
});
