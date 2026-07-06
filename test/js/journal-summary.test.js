// Unit tests for the collapsed-category on-track summary — the schedule- and
// polarity-aware rollup (categorySummary) and its badge formatter
// (formatCategorySummary). The per-tracker semantics mirror dayStatus (covered
// in journal-targets.test.js); these tests focus on the rollup + wording.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    SCHEDULE_GENESIS_DATE,
    categorySummary,
    formatCategorySummary,
} from '../../public/js/journal/utils.js';

const MON = '2026-07-06'; // a Monday (local weekday 1)

// ---- categorySummary -----------------------------------------------------

test('categorySummary: not-expected trackers are excluded (off-schedule ≠ miss)', () => {
    const weekendOnly = {
        id: 'w', polarity: 'positive',
        scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [0, 6] }], // Sun/Sat
    };
    const daily = { id: 'd', polarity: 'positive' };
    const s = categorySummary([weekendOnly, daily], MON, { d: { completed: true } });
    assert.equal(s.expected, 1);   // weekendOnly is not expected on Monday
    assert.equal(s.onTrack, 1);
});

test('categorySummary: mixed polarity — negative-empty counts as on track', () => {
    const trackers = [
        { id: 'p', polarity: 'positive' },   // completed → met
        { id: 'q', polarity: 'positive' },   // no entry → missed
        { id: 'v', polarity: 'negative' },   // no entry → met (avoided)
    ];
    const s = categorySummary(trackers, MON, { p: { completed: true } });
    assert.equal(s.expected, 3);
    assert.equal(s.onTrack, 2);    // p (checked) + v (avoided)
    assert.equal(s.notYet, 1);     // q
    assert.equal(s.allNeutral, false);
});

test('categorySummary: a logged negative tracker is not "on track"', () => {
    const s = categorySummary([{ id: 'v', polarity: 'negative' }], MON, { v: { value: 1 } });
    assert.equal(s.expected, 1);
    assert.equal(s.onTrack, 0);    // logged = not avoided
});

test('categorySummary: targeted tracker uses value-vs-target; partial is bucketed', () => {
    const t = {
        id: 't', polarity: 'positive',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150 } }],
    };
    const met = categorySummary([t], MON, { t: { value: 160 } });
    assert.deepEqual([met.onTrack, met.partial, met.notYet], [1, 0, 0]);
    const partial = categorySummary([t], MON, { t: { value: 100 } });
    assert.deepEqual([partial.onTrack, partial.partial, partial.notYet], [0, 1, 0]);
});

test('categorySummary: allNeutral for neutral/unspecified polarity only', () => {
    const s = categorySummary([
        { id: 'a' },                        // unspecified
        { id: 'b', polarity: 'neutral' },   // neutral
    ], MON, {});
    assert.equal(s.allNeutral, true);
    assert.equal(s.expected, 2);
    assert.equal(s.onTrack, 0);             // neither completed
    assert.equal(s.logged, 0);
});

test('categorySummary: logged counts entries, not checkboxes (neutral value-only)', () => {
    // A neutral measurement with a value entered but the checkbox untouched IS
    // logged — the pure-neutral badge must count it, even though the strict
    // checkbox-parity on-track judgment does not.
    const s = categorySummary([
        { id: 'weight', polarity: 'neutral', type: 'quantifiable' },
    ], MON, { weight: { value: 82.4 } });
    assert.equal(s.expected, 1);
    assert.equal(s.logged, 1);
    assert.equal(s.onTrack, 0);             // checkbox parity: unchecked ≠ met
    assert.deepEqual(
        formatCategorySummary(s),
        { text: 'All logged', tone: 'met' });
});

// ---- formatCategorySummary -----------------------------------------------

test('formatCategorySummary: null when nothing is expected (badge suppressed)', () => {
    assert.equal(formatCategorySummary({ expected: 0, onTrack: 0, logged: 0, allNeutral: true }), null);
    assert.equal(formatCategorySummary(null), null);
});

test('formatCategorySummary: partial → "N of M on track" (neutral tone)', () => {
    assert.deepEqual(
        formatCategorySummary({ expected: 4, onTrack: 3, allNeutral: false }),
        { text: '3 of 4 on track', tone: 'neutral' });
});

test('formatCategorySummary: all met → "All on track" (met tone)', () => {
    assert.deepEqual(
        formatCategorySummary({ expected: 3, onTrack: 3, allNeutral: false }),
        { text: 'All on track', tone: 'met' });
});

test('formatCategorySummary: pure-neutral category counts logged entries', () => {
    assert.deepEqual(
        formatCategorySummary({ expected: 4, onTrack: 0, logged: 2, allNeutral: true }),
        { text: '2 of 4 logged', tone: 'neutral' });
    assert.deepEqual(
        formatCategorySummary({ expected: 2, onTrack: 0, logged: 2, allNeutral: true }),
        { text: 'All logged', tone: 'met' });
});
