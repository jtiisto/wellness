// Unit tests for the collapsed-category on-track summary — the actionable/
// observation rollup (categorySummary) and its badge formatter
// (formatCategorySummary). Untargeted neutral trackers are observations, not
// goals: excluded from the on-track fraction, counted as `observed`. Per-tracker
// semantics mirror dayStatus (covered in journal-targets.test.js).
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
    assert.equal(s.actionable, 1);   // weekendOnly is not expected on Monday
    assert.equal(s.onTrack, 1);
});

test('categorySummary: mixed polarity — negative-empty counts as on track', () => {
    const trackers = [
        { id: 'p', polarity: 'positive' },   // completed → met
        { id: 'q', polarity: 'positive' },   // no entry → missed
        { id: 'v', polarity: 'negative' },   // no entry → met (avoided)
    ];
    const s = categorySummary(trackers, MON, { p: { completed: true } });
    assert.equal(s.actionable, 3);
    assert.equal(s.onTrack, 2);      // p (checked) + v (avoided)
    assert.equal(s.notYet, 1);       // q
    assert.equal(s.observed, 0);
});

test('categorySummary: a logged negative tracker is not "on track"', () => {
    const s = categorySummary([{ id: 'v', polarity: 'negative' }], MON, { v: { value: 1 } });
    assert.equal(s.actionable, 1);
    assert.equal(s.onTrack, 0);      // logged = not avoided
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

test('categorySummary: untargeted neutral observations are excluded from the fraction', () => {
    const trackers = [
        { id: 'p1', polarity: 'positive' },       // completed → met
        { id: 'p2', polarity: 'positive' },       // no entry → missed
        { id: 'headache', polarity: 'neutral' },  // observation — excluded
    ];
    const s = categorySummary(trackers, MON, { p1: { completed: true }, headache: { value: 1 } });
    assert.equal(s.actionable, 2);   // denominator is 2 (headache excluded), not 3
    assert.equal(s.onTrack, 1);
    assert.equal(s.observed, 1);     // headache logged (activity)
    assert.deepEqual(formatCategorySummary(s), { text: '1 of 2 on track', tone: 'neutral' });

    // Whether the observation is logged does not change the on-track fraction.
    const sNoObs = categorySummary(trackers, MON, { p1: { completed: true } });
    assert.equal(sNoObs.actionable, 2);
    assert.equal(sNoObs.onTrack, 1);
    assert.equal(sNoObs.observed, 0);
    assert.deepEqual(formatCategorySummary(sNoObs), { text: '1 of 2 on track', tone: 'neutral' });
});

test('categorySummary: a pure-observation category reads "K logged" (no denominator)', () => {
    const trackers = [
        { id: 'headache', polarity: 'neutral' },
        { id: 'mood-note', polarity: 'neutral' },
    ];
    const s = categorySummary(trackers, MON, { headache: { value: 1 } });
    assert.equal(s.actionable, 0);
    assert.equal(s.observed, 1);
    assert.deepEqual(formatCategorySummary(s), { text: '1 logged', tone: 'neutral' });
});

test('categorySummary: a targeted neutral is actionable (on track, not logged)', () => {
    const t = {
        id: 'weight', polarity: 'neutral',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150, max: 170 } }],
    };
    const s = categorySummary([t], MON, { weight: { value: 160 } });
    assert.equal(s.actionable, 1);
    assert.equal(s.onTrack, 1);
    assert.equal(s.observed, 0);
    assert.deepEqual(formatCategorySummary(s), { text: 'All on track', tone: 'met' });
});

// ---- formatCategorySummary -----------------------------------------------

test('formatCategorySummary: null when nothing actionable and nothing observed', () => {
    assert.equal(formatCategorySummary(null), null);
    assert.equal(formatCategorySummary({ actionable: 0, onTrack: 0, observed: 0 }), null);
});

test('formatCategorySummary: on-track fraction (partial → neutral, all met → met)', () => {
    assert.deepEqual(formatCategorySummary({ actionable: 4, onTrack: 3, observed: 2 }),
        { text: '3 of 4 on track', tone: 'neutral' });
    assert.deepEqual(formatCategorySummary({ actionable: 3, onTrack: 3, observed: 0 }),
        { text: 'All on track', tone: 'met' });
});

test('formatCategorySummary: observation activity is dropped when actionable > 0', () => {
    // A mixed category still shows only the on-track fraction (compact preview).
    assert.deepEqual(formatCategorySummary({ actionable: 2, onTrack: 2, observed: 5 }),
        { text: 'All on track', tone: 'met' });
});

test('formatCategorySummary: pure observations → "K logged", no "All", neutral tone', () => {
    assert.deepEqual(formatCategorySummary({ actionable: 0, onTrack: 0, observed: 3 }),
        { text: '3 logged', tone: 'neutral' });
    assert.deepEqual(formatCategorySummary({ actionable: 0, onTrack: 0, observed: 1 }),
        { text: '1 logged', tone: 'neutral' });
});
