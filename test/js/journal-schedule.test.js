// Unit tests for the journal tracker-schedule model (see docs/ARCHITECTURE.md
// "Tracker scheduling"). Covers the effective-dated schedule derivation, the
// legacy frequency/weeklyDay fallback, and the pure apply-from-today write
// helper.
//
// TZ pin: forced to a negative-offset zone so the local-date day-of-week rule
// is exercised for real — a regression to `new Date('YYYY-MM-DD')` (UTC
// parse) would shift weekdays here and fail the derivation tests. node --test
// runs each file in its own process, so this assignment is isolated; the
// derivation reads TZ at call time (utils.js has no top-level Date), so setting
// it after the hoisted imports still takes effect.
process.env.TZ = 'America/Los_Angeles';

import test from 'node:test';
import assert from 'node:assert/strict';
import {
    ALL_DAYS,
    SCHEDULE_GENESIS_DATE,
    POLARITY_VALUES,
    getDayOfWeek,
    getScheduleDaysForDate,
    isExpectedOn,
    shouldShowTracker,
    computeScheduleHistoryUpdate,
    normalizeTrackerSchedule,
} from '../../public/js/journal/utils.js';

// Reference dates (verified against local-component Date under any TZ):
//   2026-07-03 = Friday (5), 2026-07-04 = Saturday (6),
//   2026-07-05 = Sunday (0), 2026-07-06 = Monday (1).
const FRI = '2026-07-03';
const SAT = '2026-07-04';
const SUN = '2026-07-05';
const MON = '2026-07-06';

const sorted = (set) => Array.from(set).sort((a, b) => a - b);

// ---- constants -----------------------------------------------------------

test('ALL_DAYS is the full Sun..Sat week', () => {
    assert.deepEqual(ALL_DAYS, [0, 1, 2, 3, 4, 5, 6]);
});

test('SCHEDULE_GENESIS_DATE sorts below any real YYYY-MM-DD', () => {
    assert.ok(SCHEDULE_GENESIS_DATE < '1970-01-01');
    assert.ok(SCHEDULE_GENESIS_DATE < '2026-07-03');
});

test('POLARITY_VALUES lists the three polarities', () => {
    assert.deepEqual(POLARITY_VALUES, ['positive', 'negative', 'neutral']);
});

// ---- legacy + default derivation (behavior-preserving) -------------------

test('legacy daily → expected every day', () => {
    const t = { frequency: 'daily' };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, MON)), ALL_DAYS);
    assert.equal(isExpectedOn(t, SAT), true);
    assert.equal(isExpectedOn(t, MON), true);
});

test('legacy weekly → expected only on weeklyDay', () => {
    const t = { frequency: 'weekly', weeklyDay: 1 }; // Monday
    assert.deepEqual(sorted(getScheduleDaysForDate(t, MON)), [1]);
    assert.equal(isExpectedOn(t, MON), true);
    assert.equal(isExpectedOn(t, SAT), false);
});

test('absent frequency/schedule → daily default', () => {
    const t = {};
    assert.deepEqual(sorted(getScheduleDaysForDate(t, SAT)), ALL_DAYS);
    assert.equal(isExpectedOn(t, SAT), true);
});

test('empty scheduleHistory array → falls through to daily default', () => {
    const t = { scheduleHistory: [] };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, SAT)), ALL_DAYS);
    assert.equal(isExpectedOn(t, SAT), true);
});

test('shouldShowTracker with no dayLog equals isExpectedOn (pure expectation)', () => {
    const trackers = [
        { frequency: 'daily' },
        { frequency: 'weekly', weeklyDay: 1 },
        { scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] },
    ];
    for (const t of trackers) {
        for (const d of [FRI, SAT, SUN, MON]) {
            assert.equal(shouldShowTracker(t, d), isExpectedOn(t, d));
        }
    }
});

// ---- scheduleHistory segment selection -----------------------------------

test('segment selection: date at/after a segment picks the latest applicable', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: FRI, days: [1, 2, 3, 4, 5, 6] },
        ],
    };
    // On the effectiveFrom boundary (<=) the new segment applies.
    assert.deepEqual(sorted(getScheduleDaysForDate(t, FRI)), [1, 2, 3, 4, 5, 6]);
    // After it, still the latest.
    assert.deepEqual(sorted(getScheduleDaysForDate(t, SAT)), [1, 2, 3, 4, 5, 6]);
});

test('segment selection: date before a change uses the prior (genesis) segment', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: FRI, days: [1, 2, 3, 4, 5, 6] },
        ],
    };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, '2020-01-01')), [1, 2, 3, 4, 5]);
});

test('segment selection: date before all segments falls back to earliest', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: '2026-06-01', days: [1] },
            { effectiveFrom: '2026-07-01', days: [2] },
        ],
    };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, '2026-05-01')), [1]);
});

test('segment selection is order-independent (unsorted history)', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: FRI, days: [1, 2, 3, 4, 5, 6] },
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
        ],
    };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, '2020-01-01')), [1, 2, 3, 4, 5]);
    assert.deepEqual(sorted(getScheduleDaysForDate(t, SAT)), [1, 2, 3, 4, 5, 6]);
});

test('genesis sentinel covers all past dates; latest schedule drives isExpectedOn', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }, // Mon–Fri
            { effectiveFrom: FRI, days: ALL_DAYS },                          // daily from Fri
        ],
    };
    // Saturday now falls under the daily segment → expected.
    assert.equal(isExpectedOn(t, SAT), true);
});

test('scheduleHistory days are normalized on read (dedup/sort/range-filter)', () => {
    const t = { scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [5, 1, 1, 3, 9, -1] }] };
    assert.deepEqual(sorted(getScheduleDaysForDate(t, MON)), [1, 3, 5]);
});

// ---- write helper (apply-from-today rules) --------------------------------

test('write helper: no-op when new days equal current (legacy daily)', () => {
    const t = { frequency: 'daily' };
    const res = computeScheduleHistoryUpdate(t, ALL_DAYS, FRI);
    assert.equal(res.changed, false);
    assert.equal(res.scheduleHistory, undefined);
});

test('write helper: no-op when new days equal current, ignoring order (existing history)', () => {
    const history = [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, days: ALL_DAYS },
        { effectiveFrom: '2026-06-01', days: [1, 2, 3, 4, 5] },
    ];
    const t = { scheduleHistory: history };
    const res = computeScheduleHistoryUpdate(t, [5, 4, 3, 2, 1], MON); // same set, reordered
    assert.equal(res.changed, false);
    assert.equal(res.scheduleHistory, history); // unchanged reference
});

test('write helper: first edit of a legacy daily tracker splits genesis + today', () => {
    const t = { frequency: 'daily' };
    const res = computeScheduleHistoryUpdate(t, [1, 2, 3, 4, 5], FRI);
    assert.equal(res.changed, true);
    assert.equal(res.scheduleHistory.length, 2);
    assert.deepEqual(res.scheduleHistory[0], { effectiveFrom: SCHEDULE_GENESIS_DATE, days: ALL_DAYS });
    assert.deepEqual(res.scheduleHistory[1], { effectiveFrom: FRI, days: [1, 2, 3, 4, 5] });
});

test('write helper: first edit of a legacy weekly tracker carries the weekly day into genesis', () => {
    const t = { frequency: 'weekly', weeklyDay: 1 };
    const res = computeScheduleHistoryUpdate(t, [1, 2, 3, 4, 5], FRI);
    assert.equal(res.changed, true);
    assert.deepEqual(res.scheduleHistory[0], { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1] });
    assert.deepEqual(res.scheduleHistory[1], { effectiveFrom: FRI, days: [1, 2, 3, 4, 5] });
});

test('write helper: a later change appends a new segment', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: '2026-06-01', days: [1, 2, 3, 4, 5, 6] },
        ],
    };
    const res = computeScheduleHistoryUpdate(t, [1, 2, 3, 4, 5], FRI);
    assert.equal(res.changed, true);
    assert.equal(res.scheduleHistory.length, 3);
    assert.deepEqual(res.scheduleHistory[2], { effectiveFrom: FRI, days: [1, 2, 3, 4, 5] });
});

test('write helper: same-day re-edit replaces the latest segment in place', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: FRI, days: [1, 2, 3, 4, 5, 6] },
        ],
    };
    const res = computeScheduleHistoryUpdate(t, ALL_DAYS, FRI);
    assert.equal(res.changed, true);
    // No append — still two segments, no duplicate effectiveFrom.
    assert.equal(res.scheduleHistory.length, 2);
    const froms = res.scheduleHistory.map(s => s.effectiveFrom);
    assert.equal(new Set(froms).size, froms.length);
    assert.deepEqual(res.scheduleHistory[1], { effectiveFrom: FRI, days: ALL_DAYS });
});

test('write helper: input days are normalized (sorted, deduped, range-filtered)', () => {
    const t = { frequency: 'weekly', weeklyDay: 1 };
    const res = computeScheduleHistoryUpdate(t, [5, 3, 3, 1, 9, -1, '2'], FRI);
    assert.equal(res.changed, true);
    assert.deepEqual(res.scheduleHistory[1].days, [1, 2, 3, 5]);
});

// ---- local-date day-of-week pin -------------------------------------------

test('local-date weekday under a negative-offset TZ: Mon–Fri hidden on Saturday', () => {
    // Guards against a regression to UTC date parsing, which under TZ=LA would
    // read 2026-07-04 (Sat) as Friday and wrongly show the tracker.
    assert.equal(getDayOfWeek(SAT), 6, 'sanity: SAT resolves to Saturday');
    assert.equal(getDayOfWeek(FRI), 5, 'sanity: FRI resolves to Friday');

    const viaHistory = { scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    assert.equal(isExpectedOn(viaHistory, SAT), false);
    assert.equal(isExpectedOn(viaHistory, FRI), true);
    assert.equal(shouldShowTracker(viaHistory, SAT), false);

    const viaLegacy = { frequency: 'weekly', weeklyDay: 1 }; // Monday
    assert.equal(isExpectedOn(viaLegacy, MON), true);
    assert.equal(isExpectedOn(viaLegacy, SAT), false);
});

// ---- shouldShowTracker: entry-exists visibility override ------------------

test('shouldShowTracker: on-schedule tracker is always visible', () => {
    const t = { id: 'x', scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    assert.equal(shouldShowTracker(t, FRI), true);
    assert.equal(shouldShowTracker(t, FRI, {}), true);
});

test('shouldShowTracker: off-schedule tracker with an entry that day is visible', () => {
    const t = { id: 'x', scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    // Saturday is off-schedule, but a record exists in the day's log.
    assert.equal(shouldShowTracker(t, SAT, { x: { completed: true } }), true);
    // Even completed:false counts — unchecking must not hide the row mid-edit.
    assert.equal(shouldShowTracker(t, SAT, { x: { completed: false } }), true);
});

test('shouldShowTracker: off-schedule tracker with no entry is hidden', () => {
    const t = { id: 'x', scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    assert.equal(shouldShowTracker(t, SAT, {}), false);
    assert.equal(shouldShowTracker(t, SAT, { other: { completed: true } }), false);
});

test('shouldShowTracker: legacy weekly tracker with an off-day entry becomes visible', () => {
    // The one intentional behavior change — previously always hidden off its day.
    const t = { id: 'w', frequency: 'weekly', weeklyDay: 1 }; // Monday
    assert.equal(shouldShowTracker(t, SAT), false);                    // no log → hidden
    assert.equal(shouldShowTracker(t, SAT, { w: { value: 3 } }), true); // has entry → visible
});

test('shouldShowTracker: omitted dayLog reduces to pure expectation', () => {
    const t = { id: 'x', scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    assert.equal(shouldShowTracker(t, FRI), true);
    assert.equal(shouldShowTracker(t, SAT), false);
});

// ---- normalizeTrackerSchedule (legacy → canonical) -----------------------

test('normalizeTrackerSchedule: legacy weekly → single genesis segment; strips legacy', () => {
    const t = { id: 't', name: 'X', frequency: 'weekly', weeklyDay: 1 };
    const n = normalizeTrackerSchedule(t);
    assert.notEqual(n, t);
    assert.deepEqual(n.scheduleHistory, [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1] }]);
    assert.ok(!('frequency' in n));
    assert.ok(!('weeklyDay' in n));
    assert.equal(n.name, 'X');
});

test('normalizeTrackerSchedule: legacy daily → strips frequency, no scheduleHistory', () => {
    const t = { id: 't', frequency: 'daily' };
    const n = normalizeTrackerSchedule(t);
    assert.notEqual(n, t);
    assert.ok(!('frequency' in n));
    assert.ok(!('scheduleHistory' in n));
});

test('normalizeTrackerSchedule: no legacy fields → same reference (unchanged)', () => {
    const t = { id: 't', scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1] }] };
    assert.equal(normalizeTrackerSchedule(t), t);
});

test('normalizeTrackerSchedule: existing scheduleHistory preserved, legacy stripped', () => {
    const hist = [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [2, 4] }];
    const t = { id: 't', frequency: 'weekly', weeklyDay: 1, scheduleHistory: hist };
    const n = normalizeTrackerSchedule(t);
    assert.deepEqual(n.scheduleHistory, hist);   // canonical wins, weeklyDay ignored
    assert.ok(!('frequency' in n));
    assert.ok(!('weeklyDay' in n));
});

test('normalizeTrackerSchedule: is idempotent', () => {
    const t = { id: 't', frequency: 'weekly', weeklyDay: 3 };
    const once = normalizeTrackerSchedule(t);
    const twice = normalizeTrackerSchedule(once);
    assert.equal(twice, once);                    // second pass is a no-op (same ref)
});

test('normalizeTrackerSchedule: invalid weeklyDay → daily (no scheduleHistory)', () => {
    const t = { id: 't', frequency: 'weekly', weeklyDay: 9 };
    const n = normalizeTrackerSchedule(t);
    assert.ok(!('scheduleHistory' in n));
    assert.ok(!('frequency' in n));
});

// Representation change, NOT a schedule change: derived visibility must be
// provably identical before and after normalization for every date.
const FULL_WEEK = [
    '2026-07-03', '2026-07-04', '2026-07-05', '2026-07-06',
    '2026-07-07', '2026-07-08', '2026-07-09',
];

test('normalizeTrackerSchedule: visibility identical pre/post for legacy weekly', () => {
    const legacy = { id: 't', frequency: 'weekly', weeklyDay: 1 };
    const normalized = normalizeTrackerSchedule(legacy);
    for (const d of FULL_WEEK) {
        assert.equal(shouldShowTracker(normalized, d), shouldShowTracker(legacy, d),
            `visibility diverged on ${d}`);
    }
});

test('normalizeTrackerSchedule: visibility identical pre/post for legacy daily', () => {
    const legacy = { id: 't', frequency: 'daily' };
    const normalized = normalizeTrackerSchedule(legacy);
    for (const d of FULL_WEEK) {
        assert.equal(shouldShowTracker(normalized, d), shouldShowTracker(legacy, d),
            `visibility diverged on ${d}`);
    }
});
