// Unit tests for the pure trends chart geometry (public/js/trends/chart-logic.js).
// No visual assertions (product decision): these pin data→geometry math only.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    linearScale,
    dayIndex,
    niceTicks,
    seriesToPoints,
    linePath,
    steppedBandRects,
    stackedBarLayout,
    ribbonCells,
    sparklinePoints,
    rollingMean,
    dotSizeScale,
} from '../../public/js/trends/chart-logic.js';

// ---- scales ----------------------------------------------------------------

test('linearScale: maps domain to range linearly', () => {
    const s = linearScale(0, 10, 0, 100);
    assert.equal(s(0), 0);
    assert.equal(s(5), 50);
    assert.equal(s(10), 100);
});

test('linearScale: inverted range (SVG y) works', () => {
    const s = linearScale(0, 10, 200, 0);
    assert.equal(s(0), 200);
    assert.equal(s(10), 0);
});

test('linearScale: degenerate domain maps to range midpoint', () => {
    const s = linearScale(5, 5, 0, 100);
    assert.equal(s(5), 50);
    assert.equal(s(999), 50);
});

test('dayIndex: local-date day offsets, month/year boundaries exact', () => {
    assert.equal(dayIndex('2026-07-07', '2026-07-07'), 0);
    assert.equal(dayIndex('2026-07-07', '2026-07-01'), 6);
    assert.equal(dayIndex('2026-03-01', '2026-02-28'), 1);   // non-leap
    assert.equal(dayIndex('2027-01-01', '2026-12-31'), 1);
    assert.equal(dayIndex('2026-07-01', '2026-07-07'), -6);  // negative ok
});

test('niceTicks: 1/2/5 steps, ticks inside the domain', () => {
    assert.deepEqual(niceTicks(0, 100, 4), [0, 50, 100]);  // step 50 (2.5→5 ceil)
    assert.deepEqual(niceTicks(0, 40, 4), [0, 10, 20, 30, 40]);
    const t = niceTicks(83, 117, 4);
    assert.ok(t.every(v => v >= 83 && v <= 117));
    assert.ok(t.length >= 2);
});

test('niceTicks: degenerate domain returns the single value', () => {
    assert.deepEqual(niceTicks(5, 5, 4), [5]);
});

// ---- series ----------------------------------------------------------------

test('seriesToPoints: skips null accessor values, applies both scales', () => {
    const xs = linearScale(0, 10, 0, 100);
    const ys = linearScale(0, 10, 100, 0);
    const pts = seriesToPoints(
        [{ x: 0, v: 0 }, { x: 5, v: null }, { x: 10, v: 10 }],
        r => r.x, r => r.v, xs, ys,
    );
    assert.equal(pts.length, 2);
    assert.deepEqual([pts[0].x, pts[0].y], [0, 100]);
    assert.deepEqual([pts[1].x, pts[1].y], [100, 0]);
});

test('linePath: M/L path for 2+ points, empty for fewer', () => {
    assert.equal(linePath([]), '');
    assert.equal(linePath([{ x: 1, y: 2 }]), '');
    assert.equal(linePath([{ x: 1, y: 2 }, { x: 3, y: 4.567 }]), 'M 1 2 L 3 4.57');
});

// ---- stepped band ----------------------------------------------------------

test('steppedBandRects: one rect per segment, clipped to the window', () => {
    const xs = linearScale(0, 10, 0, 100);
    const ys = linearScale(0, 200, 200, 0);
    const rects = steppedBandRects(
        [
            { x0: -5, x1: 4, min: 150, max: null },   // clips left, open top
            { x0: 4, x1: 10, min: 100, max: 180 },
        ],
        0, 10, xs, ys, 0, 200,
    );
    assert.equal(rects.length, 2);
    // First: x from 0, open max clamps to the top edge (y=0).
    assert.equal(rects[0].x, 0);
    assert.equal(rects[0].yTop, 0);
    assert.equal(rects[0].yBot, ys(150));
    // Second: min/max both bound.
    assert.equal(rects[1].yTop, ys(180));
    assert.equal(rects[1].yBot, ys(100));
});

test('steppedBandRects: zero-width segments are dropped (gap = no rect)', () => {
    const xs = linearScale(0, 10, 0, 100);
    const ys = linearScale(0, 10, 100, 0);
    const rects = steppedBandRects([{ x0: 12, x1: 15, min: 1, max: 2 }], 0, 10, xs, ys, 0, 100);
    assert.deepEqual(rects, []);
});

// ---- stacked bars ----------------------------------------------------------

test('stackedBarLayout: segment heights stack to the total, zero segs omitted', () => {
    const xs = linearScale(-0.5, 1.5, 0, 100);
    const ys = linearScale(0, 100, 200, 0);
    const layout = stackedBarLayout(
        [
            { week_start: 'w1', values: { a: 40, b: 60 } },
            { week_start: 'w2', values: { a: 0, b: 30 } },
        ],
        ['a', 'b'], xs, ys, 20,
    );
    const bar1 = layout[0];
    assert.equal(bar1.segs.length, 2);
    const total = bar1.segs.reduce((acc, s) => acc + s.h, 0);
    assert.equal(Math.round(total), Math.round(ys(0) - ys(100)));
    // Bottom-up stacking: 'a' sits below 'b' (larger y = lower on screen).
    assert.ok(bar1.segs[0].y > bar1.segs[1].y);
    // Zero value omitted entirely.
    assert.deepEqual(layout[1].segs.map(s => s.key), ['b']);
});

// ---- ribbon ----------------------------------------------------------------

test('ribbonCells: fractions per week; paused/zero-scheduled weeks are muted', () => {
    const xs = linearScale(0, 2, 0, 90);
    const cells = ribbonCells(
        [
            { week_start: 'w1', scheduled_days: 5, met: 4, partial_days: 1, missed: 0, paused: false },
            { week_start: 'w2', scheduled_days: 0, met: 0, partial_days: 0, missed: 0, paused: true },
        ],
        xs, 20,
    );
    assert.equal(cells[0].met, 0.8);
    assert.equal(cells[0].partial, 0.2);
    assert.equal(cells[0].missed, 0);
    assert.equal(cells[0].muted, false);
    assert.equal(cells[1].muted, true);
    assert.equal(cells[1].met, 0);
});

// ---- sparkline / rolling mean / dots ---------------------------------------

test('sparklinePoints: polyline string; skips nulls; empty for <2 values', () => {
    assert.equal(sparklinePoints([5], 100, 20), '');
    const pts = sparklinePoints([0, null, 10], 100, 20);
    assert.equal(pts.split(' ').length, 2);
    assert.ok(pts.startsWith('0,20'));      // min at the bottom
    assert.ok(pts.endsWith('100,0'));       // max at the top
});

test('rollingMean: date-aware trailing window, gaps use present values only', () => {
    const series = [
        { date: '2026-07-01', value: 10 },
        { date: '2026-07-02', value: 20 },
        { date: '2026-07-09', value: 30 },  // 7-day gap: window excludes the first two
    ];
    const means = rollingMean(series, 7);
    assert.equal(means[0].value, 10);
    assert.equal(means[1].value, 15);
    assert.equal(means[2].value, 30);
});

test('rollingMean: null values contribute nothing', () => {
    const means = rollingMean(
        [{ date: '2026-07-01', value: 10 }, { date: '2026-07-02', value: null }], 7,
    );
    assert.equal(means[1].value, 10);
});

test('dotSizeScale: sqrt-area scaling between minR and maxR', () => {
    const s = dotSizeScale(2, 8, [10, 40]);
    assert.equal(s(10), 2);
    assert.equal(s(40), 8);
    const mid = s(25);
    assert.ok(mid > 2 && mid < 8);
    // Constant values → midpoint radius.
    assert.equal(dotSizeScale(2, 8, [5, 5])(5), 5);
});
