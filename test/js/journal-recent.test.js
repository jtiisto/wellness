// Unit tests for recentDayStates — the 7-day "recent texture" dot row: the
// single-day dayStatus predicate repeated over the last n local days, with
// off-schedule days marked 'off'. Per-day semantics live in journal-targets.test.js.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    SCHEDULE_GENESIS_DATE,
    recentDayStates,
} from '../../public/js/journal/utils.js';

// A Monday. The 7-day window ending here is (oldest→newest):
// 06-30 Tue, 07-01 Wed, 07-02 Thu, 07-03 Fri, 07-04 Sat, 07-05 Sun, 07-06 Mon.
const END = '2026-07-06';

test('recentDayStates: returns n days oldest→newest ending endDate', () => {
    const r = recentDayStates({ id: 't' }, END, {}, 7);
    assert.equal(r.length, 7);
    assert.deepEqual(r.map(x => x.date), [
        '2026-06-30', '2026-07-01', '2026-07-02', '2026-07-03',
        '2026-07-04', '2026-07-05', '2026-07-06',
    ]);
});

test('recentDayStates: off-schedule days are "off" (weekends for a Mon–Fri tracker)', () => {
    const t = {
        id: 't', polarity: 'positive',
        scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }],
    };
    const byDate = Object.fromEntries(recentDayStates(t, END, {}, 7).map(x => [x.date, x.state]));
    assert.equal(byDate['2026-07-04'], 'off');    // Saturday
    assert.equal(byDate['2026-07-05'], 'off');    // Sunday
    assert.equal(byDate['2026-07-06'], 'missed'); // Monday, expected, no entry, positive
    assert.equal(byDate['2026-07-01'], 'missed'); // Wednesday, expected, no entry
});

test('recentDayStates: negative tracker with no entries is "met" (avoided) all week', () => {
    const r = recentDayStates({ id: 'v', polarity: 'negative' }, END, {}, 7);
    assert.ok(r.every(x => x.state === 'met'), JSON.stringify(r));
});

test('recentDayStates: positive tracker with no entries anywhere is all "missed"', () => {
    const r = recentDayStates({ id: 't', polarity: 'positive' }, END, {}, 7);
    assert.ok(r.every(x => x.state === 'missed'), JSON.stringify(r));
});

test('recentDayStates: targeted tracker mixes met/partial/missed by value', () => {
    const t = {
        id: 't', polarity: 'positive',
        targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150 } }],
    };
    const logs = {
        '2026-07-05': { t: { value: 160 } }, // met
        '2026-07-06': { t: { value: 100 } }, // partial
    };
    const byDate = Object.fromEntries(recentDayStates(t, END, logs, 7).map(x => [x.date, x.state]));
    assert.equal(byDate['2026-07-05'], 'met');
    assert.equal(byDate['2026-07-06'], 'partial');
    assert.equal(byDate['2026-07-04'], 'missed'); // no entry, positive
});

test('recentDayStates: honors a custom window length and tolerates missing logs', () => {
    const r = recentDayStates({ id: 't', polarity: 'negative' }, END, undefined, 3);
    assert.equal(r.length, 3);
    assert.deepEqual(r.map(x => x.date), ['2026-07-04', '2026-07-05', '2026-07-06']);
    assert.ok(r.every(x => x.state === 'met')); // negative no-entry = met
});

test('recentDayStates: untargeted-neutral tracker uses noted/quiet, not met/missed', () => {
    const t = { id: 'h', polarity: 'neutral' }; // e.g. "Headache" observation, daily
    const logs = {
        '2026-07-06': { h: { completed: true } }, // entry → noted
        '2026-07-02': { h: { value: 1 } },        // entry (value) → noted
    };
    const byDate = Object.fromEntries(recentDayStates(t, END, logs, 7).map(x => [x.date, x.state]));
    assert.equal(byDate['2026-07-06'], 'noted');
    assert.equal(byDate['2026-07-02'], 'noted');
    assert.equal(byDate['2026-07-01'], 'quiet'); // expected, no entry
    const states = Object.values(byDate);
    assert.ok(!states.includes('met') && !states.includes('missed'), JSON.stringify(byDate));
});

test('recentDayStates: a neutral observation still marks off-schedule days "off"', () => {
    const t = {
        id: 'h', polarity: 'neutral',
        scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }],
    };
    const byDate = Object.fromEntries(recentDayStates(t, END, {}, 7).map(x => [x.date, x.state]));
    assert.equal(byDate['2026-07-04'], 'off');   // Saturday
    assert.equal(byDate['2026-07-05'], 'off');   // Sunday
    assert.equal(byDate['2026-07-06'], 'quiet'); // Monday, expected neutral, no entry
});

test('recentDayStates: days before earliestKnownDate render "off", not missed/quiet (pruned logs)', () => {
    // Browsing back: the window ending at END reaches days the store has
    // pruned — absence there is "unknown", not a fabricated miss.
    const t = { id: 't', polarity: 'positive' };
    const byDate = Object.fromEntries(
        recentDayStates(t, END, {}, 7, '2026-07-03').map(x => [x.date, x.state]));
    assert.equal(byDate['2026-06-30'], 'off');
    assert.equal(byDate['2026-07-01'], 'off');
    assert.equal(byDate['2026-07-02'], 'off');
    assert.equal(byDate['2026-07-03'], 'missed');  // inside the window: judged
    assert.equal(byDate['2026-07-06'], 'missed');
});

test('recentDayStates: no earliestKnownDate keeps the old judge-everything behavior', () => {
    const r = recentDayStates({ id: 't', polarity: 'positive' }, END, {}, 7);
    assert.ok(r.every(x => x.state === 'missed'));
});
