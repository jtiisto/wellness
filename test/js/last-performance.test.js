// Unit tests for the pure "previous performance" lookup (coach/last-performance.js).
// Matching is by canonical_slug across ANY past workout, returning the most
// recent prior session that was actually logged with set data.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    findLastPerformance,
    setHasData,
    formatShortDate,
} from '../../public/js/coach/last-performance.js';

// Helpers to build plan/log fixtures keyed by date.
const planWith = (exId, slug) => ({ blocks: [{ exercises: [{ id: exId, canonical_slug: slug }] }] });
const logWith = (exId, sets) => ({ [exId]: { sets } });

test('setHasData: true only when a real metric is present', () => {
    assert.equal(setHasData(null), false);
    assert.equal(setHasData({}), false);
    assert.equal(setHasData({ set_num: 1 }), false);
    assert.equal(setHasData({ completed: true }), false);
    assert.equal(setHasData({ weight: 0 }), true);   // 0 is real data
    assert.equal(setHasData({ reps: 5 }), true);
    assert.equal(setHasData({ rpe: 8 }), true);
    assert.equal(setHasData({ duration_sec: 40 }), true);
});

test('no history -> null', () => {
    assert.equal(findLastPerformance('squat', '2026-06-07', {}, {}), null);
});

test('missing/empty slug -> null', () => {
    const plans = { '2026-06-01': planWith('ex_1', 'squat') };
    const logs = { '2026-06-01': logWith('ex_1', [{ set_num: 1, reps: 5 }]) };
    assert.equal(findLastPerformance('', '2026-06-07', plans, logs), null);
    assert.equal(findLastPerformance(null, '2026-06-07', plans, logs), null);
});

test('one prior in a different workout (different exercise_key) -> that session', () => {
    // The slug is "squat" but the per-session key differs across workouts.
    const plans = { '2026-06-01': planWith('ex_3', 'squat') };
    const logs = { '2026-06-01': logWith('ex_3', [{ set_num: 1, weight: 60, reps: 8, rpe: 8 }]) };
    const r = findLastPerformance('squat', '2026-06-07', plans, logs);
    assert.equal(r.date, '2026-06-01');
    assert.deepEqual(r.sets, [{ set_num: 1, weight: 60, reps: 8, rpe: 8 }]);
});

test('multiple priors -> most recent chosen', () => {
    const plans = {
        '2026-05-20': planWith('ex_1', 'squat'),
        '2026-06-03': planWith('ex_9', 'squat'),
    };
    const logs = {
        '2026-05-20': logWith('ex_1', [{ set_num: 1, weight: 50, reps: 10 }]),
        '2026-06-03': logWith('ex_9', [{ set_num: 1, weight: 65, reps: 6 }]),
    };
    const r = findLastPerformance('squat', '2026-06-07', plans, logs);
    assert.equal(r.date, '2026-06-03');
    assert.equal(r.sets[0].weight, 65);
});

test('planned-but-not-logged date is skipped -> next logged one', () => {
    const plans = {
        '2026-05-28': planWith('ex_1', 'squat'),
        '2026-06-04': planWith('ex_2', 'squat'),
    };
    const logs = {
        // 06-04: planned but only placeholder/empty sets -> not "performed"
        '2026-06-04': logWith('ex_2', [{ set_num: 1 }, { set_num: 2, completed: true }]),
        '2026-05-28': logWith('ex_1', [{ set_num: 1, weight: 40, reps: 12 }]),
    };
    const r = findLastPerformance('squat', '2026-06-07', plans, logs);
    assert.equal(r.date, '2026-05-28');
});

test('refDate excludes same-day and future sessions', () => {
    const plans = {
        '2026-06-07': planWith('ex_1', 'squat'),  // same day
        '2026-06-10': planWith('ex_2', 'squat'),  // future
        '2026-06-02': planWith('ex_3', 'squat'),  // prior
    };
    const logs = {
        '2026-06-07': logWith('ex_1', [{ set_num: 1, weight: 99, reps: 1 }]),
        '2026-06-10': logWith('ex_2', [{ set_num: 1, weight: 88, reps: 1 }]),
        '2026-06-02': logWith('ex_3', [{ set_num: 1, weight: 70, reps: 5 }]),
    };
    const r = findLastPerformance('squat', '2026-06-07', plans, logs);
    assert.equal(r.date, '2026-06-02');
});

test('only sets with data are returned (set_num preserved)', () => {
    const plans = { '2026-06-01': planWith('ex_1', 'squat') };
    const logs = { '2026-06-01': logWith('ex_1', [
        { set_num: 1 },                              // empty -> dropped
        { set_num: 2, weight: 60, reps: 8, rpe: 8 }, // kept
        { set_num: 3, weight: 60, reps: 7 },         // kept
    ]) };
    const r = findLastPerformance('squat', '2026-06-07', plans, logs);
    assert.deepEqual(r.sets.map(s => s.set_num), [2, 3]);
});

test('slug present in plan but no log at all -> skipped', () => {
    const plans = { '2026-06-01': planWith('ex_1', 'squat') };
    const r = findLastPerformance('squat', '2026-06-07', plans, {});
    assert.equal(r, null);
});

test('formatShortDate: YYYY-MM-DD -> "Mon D" without TZ shift', () => {
    assert.equal(formatShortDate('2026-06-01'), 'Jun 1');
    assert.equal(formatShortDate('2026-01-31'), 'Jan 31');
    assert.equal(formatShortDate('2026-12-09'), 'Dec 9');
    assert.equal(formatShortDate(''), '');
    assert.equal(formatShortDate(null), '');
});
