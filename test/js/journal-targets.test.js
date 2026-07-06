// Unit tests for the typed tracker-target model: parse/format, effective-dated
// targetForDate selection, and the apply-from-today target writer.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    SCHEDULE_GENESIS_DATE,
    parseTarget,
    formatTarget,
    formatTargetInput,
    targetForDate,
    computeTargetHistoryUpdate,
    targetStatus,
    dayStatus,
    formatTargetProgress,
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

// ---- formatTargetInput (inverse of parseTarget for the config input) -----

test('formatTargetInput: renders re-parseable raw strings', () => {
    assert.equal(formatTargetInput(null), '');
    assert.equal(formatTargetInput({}), '');
    assert.equal(formatTargetInput({ min: 10 }), '10');
    assert.equal(formatTargetInput({ max: 2 }), '2');
    assert.equal(formatTargetInput({ min: 150, max: 170 }), '150-170');
    assert.equal(formatTargetInput({ min: 8, max: 8 }), '8-8');  // exact → range form
});

test('formatTargetInput ↔ parseTarget round-trips (unchanged polarity)', () => {
    const cases = [
        [{ min: 10 }, 'positive'],
        [{ max: 10 }, 'negative'],
        [{ min: 150, max: 170 }, 'positive'],
        [{ min: 8, max: 8 }, 'positive'],
    ];
    for (const [target, polarity] of cases) {
        const round = parseTarget(formatTargetInput(target), polarity).target;
        assert.deepEqual(round, target, `round-trip failed for ${JSON.stringify(target)}`);
    }
});

// ---- targetStatus (pure mirror of adherence.py _target_status) -----------
// The assertion table below is deliberately parallel to
// test/test_journal_adherence.py::TestTargetAdherence so the JS twin and the
// Python source of truth can never drift.

test('targetStatus: no-entry gate applied FIRST for every target kind', () => {
    // negative no-entry → met (avoided); positive/neutral no-entry → missed.
    assert.equal(targetStatus({ max: 2 }, null, false, 'negative'), 'met');
    assert.equal(targetStatus({ min: 10 }, null, false, 'positive'), 'missed');
    assert.equal(targetStatus({ min: 150, max: 170 }, null, false, 'neutral'), 'missed');
    // F1 pin: positive + at-most + no-entry → missed. The gate runs before the
    // bound check that would otherwise read "no value ≤ max" as met.
    assert.equal(targetStatus({ max: 2 }, null, false, 'positive'), 'missed');
    assert.equal(targetStatus({ max: 2 }, null, false, undefined), 'missed');
});

test('targetStatus: entry present with null value → missed', () => {
    assert.equal(targetStatus({ min: 10 }, null, true, 'positive'), 'missed');
    assert.equal(targetStatus({ max: 2 }, null, true, 'negative'), 'missed');
    assert.equal(targetStatus({ min: 1, max: 5 }, null, true, 'neutral'), 'missed');
});

test('targetStatus: at-least (min) met / partial / missed', () => {
    assert.equal(targetStatus({ min: 150 }, 160, true, 'positive'), 'met');
    assert.equal(targetStatus({ min: 150 }, 150, true, 'positive'), 'met');     // boundary
    assert.equal(targetStatus({ min: 150 }, 100, true, 'positive'), 'partial');
    assert.equal(targetStatus({ min: 150 }, 0, true, 'positive'), 'missed');    // 0 → missed, not partial
});

test('targetStatus: at-most (max) met / over — no partial state', () => {
    assert.equal(targetStatus({ max: 2 }, 1, true, 'negative'), 'met');
    assert.equal(targetStatus({ max: 2 }, 2, true, 'negative'), 'met');         // boundary
    assert.equal(targetStatus({ max: 2 }, 3, true, 'negative'), 'missed');      // over
    assert.equal(targetStatus({ max: 2 }, 0, true, 'negative'), 'met');
});

test('targetStatus: range (min,max) met / partial(below) / over(above)', () => {
    assert.equal(targetStatus({ min: 150, max: 170 }, 160, true, 'neutral'), 'met');
    assert.equal(targetStatus({ min: 150, max: 170 }, 150, true, 'neutral'), 'met');
    assert.equal(targetStatus({ min: 150, max: 170 }, 170, true, 'neutral'), 'met');
    assert.equal(targetStatus({ min: 150, max: 170 }, 100, true, 'neutral'), 'partial');
    assert.equal(targetStatus({ min: 150, max: 170 }, 200, true, 'neutral'), 'missed');
});

// ---- dayStatus (tracker-level wrapper) -----------------------------------

test('dayStatus: untargeted positive/neutral → met iff completed (checkbox parity)', () => {
    const pos = { id: 't', polarity: 'positive' };
    assert.equal(dayStatus(pos, TODAY, { completed: true }).state, 'met');
    assert.equal(dayStatus(pos, TODAY, { completed: false }).state, 'missed');
    assert.equal(dayStatus(pos, TODAY, undefined).state, 'missed');
    // A value with no checkbox is NOT met — no logged-counts special case.
    assert.equal(dayStatus(pos, TODAY, { value: 42, completed: false }).state, 'missed');
    const neu = { id: 't' };   // unspecified polarity behaves as non-negative
    assert.equal(dayStatus(neu, TODAY, { completed: true }).state, 'met');
    assert.equal(dayStatus(neu, TODAY, undefined).state, 'missed');
});

test('dayStatus: untargeted negative → met iff no entry (avoided)', () => {
    const neg = { id: 't', polarity: 'negative' };
    assert.equal(dayStatus(neg, TODAY, undefined).state, 'met');            // avoided
    assert.equal(dayStatus(neg, TODAY, { completed: true }).state, 'missed');
    assert.equal(dayStatus(neg, TODAY, { value: 1 }).state, 'missed');      // logged = not avoided
});

test('dayStatus: targeted delegates to targetStatus with the in-effect target', () => {
    const t = {
        id: 't', polarity: 'positive',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150 } }],
    };
    const r = dayStatus(t, TODAY, { value: 160 });
    assert.equal(r.state, 'met');
    assert.equal(r.hasTarget, true);
    assert.deepEqual(r.target, { min: 150 });
    assert.equal(dayStatus(t, TODAY, { value: 100 }).state, 'partial');
    assert.equal(dayStatus(t, TODAY, undefined).state, 'missed');           // positive no-entry
});

test('dayStatus: uses the target in effect on the date (effective-dated)', () => {
    const t = {
        id: 't', polarity: 'positive',
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 100 } },
            { effectiveFrom: '2026-07-06', target: { min: 200 } },
        ],
    };
    // value 150: met under the old min:100, partial under the new min:200.
    assert.equal(dayStatus(t, '2026-07-05', { value: 150 }).state, 'met');
    assert.equal(dayStatus(t, '2026-07-06', { value: 150 }).state, 'partial');
});

test('dayStatus: a null-target segment reads as untargeted (checkbox parity)', () => {
    const t = {
        id: 't', polarity: 'positive',
        targetHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 100 } },
            { effectiveFrom: '2026-07-01', target: null },
        ],
    };
    const r = dayStatus(t, TODAY, { value: 999, completed: false });
    assert.equal(r.hasTarget, false);
    assert.equal(r.state, 'missed');                                        // untargeted positive, no checkbox
    assert.equal(dayStatus(t, TODAY, { completed: true }).state, 'met');
});

// ---- formatTargetProgress (row display model) ----------------------------

const _prog = (tracker, entry, unit) =>
    formatTargetProgress(dayStatus(tracker, TODAY, entry), unit);

test('formatTargetProgress: null when there is no target in effect', () => {
    assert.equal(formatTargetProgress(dayStatus({ id: 't' }, TODAY, { value: 5 }), 'g'), null);
});

test('formatTargetProgress: at-least shows "value / ≥ min" + fill ratio', () => {
    const t = {
        id: 't', polarity: 'positive', unit: 'g',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150 } }],
    };
    const met = _prog(t, { value: 160 }, 'g');
    assert.deepEqual(met, { text: '160 / ≥ 150 g', tone: 'met', fillPct: 100 });   // clamped
    const partial = _prog(t, { value: 75 }, 'g');
    assert.deepEqual(partial, { text: '75 / ≥ 150 g', tone: 'partial', fillPct: 50 });
});

test('formatTargetProgress: at-least with no entry shows the goal + empty fill', () => {
    const t = {
        id: 't', polarity: 'positive', unit: 'g',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150 } }],
    };
    assert.deepEqual(_prog(t, undefined, 'g'), { text: '≥ 150 g', tone: 'neutral', fillPct: 0 });
});

test('formatTargetProgress: at-most shows headroom / over (calm), no fill', () => {
    const t = {
        id: 't', polarity: 'negative',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { max: 2 } }],
    };
    assert.deepEqual(_prog(t, { value: 1 }, ''), { text: '1 of ≤ 2 · 1 left', tone: 'met', fillPct: null });
    assert.deepEqual(_prog(t, { value: 3 }, ''), { text: '3 of ≤ 2 · over by 1', tone: 'over', fillPct: null });
    // No entry on a negative tracker → avoided (met), never failure.
    assert.deepEqual(_prog(t, undefined, ''), { text: '≤ 2 · avoided', tone: 'met', fillPct: null });
});

test('formatTargetProgress: range membership (in / below / above)', () => {
    const t = {
        id: 't', polarity: 'neutral', unit: 'g',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150, max: 170 } }],
    };
    assert.deepEqual(_prog(t, { value: 160 }, 'g'), { text: '160 in 150–170 g', tone: 'met', fillPct: null });
    assert.equal(_prog(t, { value: 100 }, 'g').tone, 'partial');    // below
    assert.equal(_prog(t, { value: 200 }, 'g').tone, 'over');       // above
});
