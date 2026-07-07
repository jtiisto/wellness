// Unit tests for the config form→trackerData mapping helpers (see
// docs/ARCHITECTURE.md "Tracker scheduling"). Keeps the
// no-op/genesis/append/replace logic and the empty→Daily coercion covered
// without rendering the Preact form.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    ALL_DAYS,
    SCHEDULE_GENESIS_DATE,
    buildTrackerSaveFields,
    formatScheduleSummary,
} from '../../public/js/journal/utils.js';

const TODAY = '2026-07-03';

// ---- buildTrackerSaveFields: schedule (new tracker) ----------------------

test('new tracker at Daily writes no scheduleHistory', () => {
    const fields = buildTrackerSaveFields(null, { days: [...ALL_DAYS], polarity: '' }, TODAY);
    assert.equal('scheduleHistory' in fields, false);
});

test('new tracker with empty selection coerces to Daily (no scheduleHistory)', () => {
    const fields = buildTrackerSaveFields(null, { days: [], polarity: '' }, TODAY);
    assert.equal('scheduleHistory' in fields, false);
});

test('new narrower tracker gets a single genesis segment (no phantom past split)', () => {
    const fields = buildTrackerSaveFields(null, { days: [1, 2, 3, 4, 5], polarity: '' }, TODAY);
    assert.deepEqual(fields.scheduleHistory, [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
    ]);
});

test('new tracker normalizes chosen days (sort/dedupe/range)', () => {
    const fields = buildTrackerSaveFields(null, { days: [5, 1, 1, 9, 3], polarity: '' }, TODAY);
    assert.deepEqual(fields.scheduleHistory[0].days, [1, 3, 5]);
});

// ---- buildTrackerSaveFields: schedule (editing) --------------------------

test('editing to the same day-set writes no scheduleHistory (no-op)', () => {
    const t = { scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    const fields = buildTrackerSaveFields(t, { days: [1, 2, 3, 4, 5], polarity: '' }, TODAY);
    assert.equal('scheduleHistory' in fields, false);
});

test('editing a legacy daily tracker splits genesis + today', () => {
    const t = { frequency: 'daily' };
    const fields = buildTrackerSaveFields(t, { days: [1, 2, 3, 4, 5], polarity: '' }, TODAY);
    assert.deepEqual(fields.scheduleHistory, [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, days: ALL_DAYS },
        { effectiveFrom: TODAY, days: [1, 2, 3, 4, 5] },
    ]);
});

test('editing an existing scheduled tracker on a later day appends a segment', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: '2026-06-01', days: [1, 2, 3, 4, 5, 6] },
        ],
    };
    const fields = buildTrackerSaveFields(t, { days: [1, 2, 3, 4, 5], polarity: '' }, TODAY);
    assert.equal(fields.scheduleHistory.length, 3);
    assert.deepEqual(fields.scheduleHistory[2], { effectiveFrom: TODAY, days: [1, 2, 3, 4, 5] });
});

// ---- buildTrackerSaveFields: polarity ------------------------------------

test('new tracker with a valid polarity writes it', () => {
    const fields = buildTrackerSaveFields(null, { days: [...ALL_DAYS], polarity: 'negative' }, TODAY);
    assert.equal(fields.polarity, 'negative');
});

test('new tracker with unspecified polarity omits the key', () => {
    const fields = buildTrackerSaveFields(null, { days: [...ALL_DAYS], polarity: '' }, TODAY);
    assert.equal('polarity' in fields, false);
});

test('editing to unspecified clears an existing polarity', () => {
    const t = { polarity: 'positive' };
    const fields = buildTrackerSaveFields(t, { days: [...ALL_DAYS], polarity: '' }, TODAY);
    assert.equal('polarity' in fields, true);
    assert.equal(fields.polarity, undefined);
});

test('editing an unspecified tracker to a value writes it', () => {
    const t = {};
    const fields = buildTrackerSaveFields(t, { days: [...ALL_DAYS], polarity: 'neutral' }, TODAY);
    assert.equal(fields.polarity, 'neutral');
});

// ---- formatScheduleSummary -----------------------------------------------

test('formatScheduleSummary: Paused / Daily / Mon–Fri / slash list', () => {
    assert.equal(formatScheduleSummary(ALL_DAYS), 'Daily');
    // An empty day-set is a paused tracker (was 'Daily' before the pause feature).
    assert.equal(formatScheduleSummary([]), 'Paused');
    assert.equal(formatScheduleSummary(new Set()), 'Paused');
    assert.equal(formatScheduleSummary([1, 2, 3, 4, 5]), 'Mon–Fri');
    assert.equal(formatScheduleSummary([1, 3, 5]), 'Mon/Wed/Fri');
    assert.equal(formatScheduleSummary([0, 6]), 'Sun/Sat');
    assert.equal(formatScheduleSummary(new Set([6, 1])), 'Mon/Sat');
});

// ---- buildTrackerSaveFields: target (quantifiable) -----------------------

test('buildTrackerSaveFields: new tracker with a target → single genesis segment', () => {
    const fields = buildTrackerSaveFields(
        null, { days: [...ALL_DAYS], polarity: '', target: { min: 150, max: 170 } }, TODAY);
    assert.deepEqual(fields.targetHistory, [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 150, max: 170 } },
    ]);
});

test('buildTrackerSaveFields: new tracker with null target writes no targetHistory', () => {
    const fields = buildTrackerSaveFields(
        null, { days: [...ALL_DAYS], polarity: '', target: null }, TODAY);
    assert.equal('targetHistory' in fields, false);
});

test('buildTrackerSaveFields: target undefined leaves targetHistory untouched', () => {
    const t = { targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 10 } }] };
    const fields = buildTrackerSaveFields(t, { days: [...ALL_DAYS], polarity: '' }, TODAY);
    assert.equal('targetHistory' in fields, false);
});

test('buildTrackerSaveFields: editing to a new target updates via the writer', () => {
    const t = { targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 120 } }] };
    const fields = buildTrackerSaveFields(
        t, { days: [...ALL_DAYS], polarity: '', target: { min: 150 } }, TODAY);
    assert.ok(fields.targetHistory);
    assert.deepEqual(fields.targetHistory[fields.targetHistory.length - 1],
        { effectiveFrom: TODAY, target: { min: 150 } });
});

test('buildTrackerSaveFields: editing to the same target is a no-op', () => {
    const t = { targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 120 } }] };
    const fields = buildTrackerSaveFields(
        t, { days: [...ALL_DAYS], polarity: '', target: { min: 120 } }, TODAY);
    assert.equal('targetHistory' in fields, false);
});

test('buildTrackerSaveFields: clearing a target records a null-target segment', () => {
    const t = { targetHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target: { min: 120 } }] };
    const fields = buildTrackerSaveFields(
        t, { days: [...ALL_DAYS], polarity: '', target: null }, TODAY);
    assert.ok(fields.targetHistory);
    assert.deepEqual(fields.targetHistory[fields.targetHistory.length - 1],
        { effectiveFrom: TODAY, target: null });
});

// ---- buildTrackerSaveFields: pause (empty-days schedule) ------------------

test('pause: new tracker created paused gets a single genesis [] segment', () => {
    // `days` is ignored when paused — the empty schedule is what pauses it.
    const fields = buildTrackerSaveFields(
        null, { days: [...ALL_DAYS], polarity: '', paused: true }, TODAY);
    assert.deepEqual(fields.scheduleHistory, [
        { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [] },
    ]);
});

test('pause: editing a scheduled tracker appends a today [] segment', () => {
    const t = { scheduleHistory: [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] }] };
    const fields = buildTrackerSaveFields(t, { days: [1, 2, 3, 4, 5], polarity: '', paused: true }, TODAY);
    assert.equal(fields.scheduleHistory.length, 2);
    assert.deepEqual(fields.scheduleHistory[fields.scheduleHistory.length - 1],
        { effectiveFrom: TODAY, days: [] });
});

test('unpause: editing a paused tracker appends the picked days', () => {
    // Paused on 2026-06-01; unpaused today to Mon–Fri.
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [...ALL_DAYS] },
            { effectiveFrom: '2026-06-01', days: [] },
        ],
    };
    const fields = buildTrackerSaveFields(
        t, { days: [1, 2, 3, 4, 5], polarity: '', paused: false }, TODAY);
    assert.deepEqual(fields.scheduleHistory[fields.scheduleHistory.length - 1],
        { effectiveFrom: TODAY, days: [1, 2, 3, 4, 5] });
});

test('same-day pause then unpause replaces the today segment (no duplicate effectiveFrom)', () => {
    // Paused today, then unpaused today in the same edit session.
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [1, 2, 3, 4, 5] },
            { effectiveFrom: TODAY, days: [] },
        ],
    };
    const fields = buildTrackerSaveFields(
        t, { days: [1, 2, 3, 4, 5], polarity: '', paused: false }, TODAY);
    assert.equal(fields.scheduleHistory.length, 2);
    const froms = fields.scheduleHistory.map(s => s.effectiveFrom);
    assert.equal(new Set(froms).size, froms.length);
    assert.deepEqual(fields.scheduleHistory[1], { effectiveFrom: TODAY, days: [1, 2, 3, 4, 5] });
});

test('regression: a NON-paused empty selection still coerces to Daily (no schedule written)', () => {
    // The footgun-guard is intact — only paused:true bypasses it.
    assert.equal(
        'scheduleHistory' in buildTrackerSaveFields(null, { days: [], polarity: '', paused: false }, TODAY),
        false);
    // Editing to empty (unpaused) is a no-op against a Daily tracker.
    const daily = { frequency: 'daily' };
    assert.equal(
        'scheduleHistory' in buildTrackerSaveFields(daily, { days: [], polarity: '', paused: false }, TODAY),
        false);
});

test('pause supersedes pending FUTURE segments (clock-skew) — all effectiveFrom <= today', () => {
    const t = {
        scheduleHistory: [
            { effectiveFrom: SCHEDULE_GENESIS_DATE, days: [...ALL_DAYS] },
            { effectiveFrom: '2026-07-10', days: [3] }, // future vs TODAY (07-03)
        ],
    };
    const fields = buildTrackerSaveFields(t, { days: [...ALL_DAYS], polarity: '', paused: true }, TODAY);
    assert.ok(fields.scheduleHistory.every(seg => seg.effectiveFrom <= TODAY));
    assert.deepEqual(fields.scheduleHistory[fields.scheduleHistory.length - 1],
        { effectiveFrom: TODAY, days: [] });
});
