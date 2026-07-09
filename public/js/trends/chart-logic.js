/**
 * Trends chart-logic — PURE data→geometry functions.
 *
 * The purity contract mirrors coach/sync-logic.js: no preact, no DOM, no
 * fetch, no Date.now — every function is `(data, config) -> geometry` and is
 * unit-tested in isolation (test/js/trends-chart-logic.test.js). The SVG
 * components in components/ are thin consumers of these.
 *
 * Coordinate convention: x grows right, y grows DOWN (SVG); y-scales are
 * therefore built with an inverted range (r0 > r1) by the caller.
 */

// ---- Scales ---------------------------------------------------------------

/**
 * Linear scale mapping domain [d0,d1] → range [r0,r1]. A degenerate domain
 * (d0 === d1) maps every value to the range midpoint — a single-point series
 * renders centered rather than dividing by zero.
 */
export function linearScale(d0, d1, r0, r1) {
    if (d0 === d1) {
        const mid = (r0 + r1) / 2;
        return () => mid;
    }
    const k = (r1 - r0) / (d1 - d0);
    return (v) => r0 + (v - d0) * k;
}

/**
 * A record's value as a finite number, or null when absent/non-numeric.
 * Twin of the journal layers' _coerce_numeric/coerceNumericValue: a tracker
 * converted from type 'note' keeps free-text entry values (the column is
 * REAL-affinity, text survives), and one string in a series must not
 * NaN-poison every scale/mean/path it touches (review F1). Booleans are
 * explicitly not values.
 */
export function coerceNumeric(value) {
    if (typeof value === 'number') return Number.isFinite(value) ? value : null;
    if (typeof value === 'string' && value.trim() !== '') {
        const n = Number(value);
        return Number.isFinite(n) ? n : null;
    }
    return null;
}

/**
 * Day offset of a local YYYY-MM-DD from an origin date string. Pure string →
 * integer math via Date.UTC on the parsed parts (no timezone involvement —
 * both dates go through the same UTC projection, so the difference is exact
 * calendar days).
 */
export function dayIndex(dateStr, originStr) {
    const [y1, m1, d1] = dateStr.split('-').map(Number);
    const [y0, m0, d0] = originStr.split('-').map(Number);
    return Math.round((Date.UTC(y1, m1 - 1, d1) - Date.UTC(y0, m0 - 1, d0)) / 86400000);
}

/**
 * "Nice" axis tick values covering [min,max]: steps of 1/2/5×10^n chosen to
 * land near targetCount ticks. Returns at least the two endpoints' ticks.
 */
export function niceTicks(min, max, targetCount = 4) {
    if (!(max > min)) return [min];
    const rawStep = (max - min) / Math.max(1, targetCount);
    const mag = Math.pow(10, Math.floor(Math.log10(rawStep)));
    const norm = rawStep / mag;
    const step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
    const ticks = [];
    for (let t = Math.ceil(min / step) * step; t <= max + step / 1e6; t += step) {
        ticks.push(Number(t.toFixed(10)));
    }
    return ticks.length ? ticks : [min];
}

// ---- Series geometry ------------------------------------------------------

/**
 * Map a series of records to plot points. `accessor(record)` returns the y
 * value or null/undefined to skip the record (e.g. a null RPE).
 * xScale receives dayIndex-style x inputs produced by `xValue(record)`.
 */
export function seriesToPoints(records, xValue, accessor, xScale, yScale) {
    const points = [];
    for (const r of records) {
        const v = accessor(r);
        if (v == null) continue;
        points.push({ x: xScale(xValue(r)), y: yScale(v), raw: r });
    }
    return points;
}

/** SVG path ("M x y L x y ...") through points; '' for fewer than 2 points
 *  (a lone dot is the component's job, not the line's). */
export function linePath(points) {
    if (points.length < 2) return '';
    return points
        .map((p, i) => `${i === 0 ? 'M' : 'L'} ${round2(p.x)} ${round2(p.y)}`)
        .join(' ');
}

/**
 * Stepped target-band rectangles for effective-dated segments
 * [{start, end, min, max}] (dates as day-index x inputs via xValue). A null
 * min clamps to yBottom, a null max clamps to yTop (the plot edge). Segments
 * produce one rect each — gaps between segments simply have no rect.
 * Returns [{x, w, yTop, yBot}].
 */
export function steppedBandRects(segments, xStart, xEnd, xScale, yScale, yTopEdge, yBotEdge) {
    const rects = [];
    for (const seg of segments) {
        const x0 = xScale(Math.max(seg.x0, xStart));
        const x1 = xScale(Math.min(seg.x1, xEnd));
        if (!(x1 > x0)) continue;
        const yTop = seg.max != null ? yScale(seg.max) : yTopEdge;
        const yBot = seg.min != null ? yScale(seg.min) : yBotEdge;
        rects.push({
            x: round2(x0), w: round2(x1 - x0),
            yTop: round2(Math.min(yTop, yBot)), yBot: round2(Math.max(yTop, yBot)),
        });
    }
    return rects;
}

/**
 * Stacked weekly bars. `weeks` = [{week_start, values: {key: number}}],
 * `keys` = stacking order bottom-up. Returns
 * [{weekStart, segs: [{key, x, y, w, h}]}] with zero-height segs omitted.
 */
export function stackedBarLayout(weeks, keys, xScale, yScale, barWidth) {
    const zeroY = yScale(0);
    return weeks.map((wk, i) => {
        let acc = 0;
        const segs = [];
        for (const key of keys) {
            const v = wk.values[key] || 0;
            if (v <= 0) continue;
            const y0 = yScale(acc);
            const y1 = yScale(acc + v);
            segs.push({
                key,
                x: round2(xScale(i) - barWidth / 2),
                y: round2(Math.min(y0, y1)),
                w: round2(barWidth),
                h: round2(Math.abs(y0 - y1)),
            });
            acc += v;
        }
        return { weekStart: wk.week_start, segs, zeroY: round2(zeroY) };
    });
}

/**
 * Weekly adherence ribbon cells: [{week_start, paused, met, partial_days,
 * missed, scheduled_days}] → [{x, w, kind, fraction...}] where paused/empty
 * weeks render as 'muted'. The cell's met/partial/missed fractions let the
 * component draw a proportional fill.
 */
export function ribbonCells(weeks, xScale, cellWidth) {
    return weeks.map((wk, i) => {
        const total = wk.scheduled_days || 0;
        const muted = wk.paused || total === 0;
        return {
            x: round2(xScale(i) - cellWidth / 2),
            w: round2(cellWidth),
            muted,
            met: muted ? 0 : (wk.met || 0) / total,
            partial: muted ? 0 : (wk.partial_days || 0) / total,
            missed: muted ? 0 : (wk.missed || 0) / total,
            weekStart: wk.week_start,
        };
    });
}

/** Polyline `points` attribute string for a sparkline of raw values in a
 *  w×h box (padding handled by the caller); '' for <2 values. Nulls skipped. */
export function sparklinePoints(values, w, h) {
    const present = values.filter(v => v != null);
    if (present.length < 2) return '';
    const min = Math.min(...present);
    const max = Math.max(...present);
    const xs = linearScale(0, values.length - 1, 0, w);
    const ys = linearScale(min, max, h, 0);
    const pts = [];
    values.forEach((v, i) => {
        if (v == null) return;
        pts.push(`${round2(xs(i))},${round2(ys(v))}`);
    });
    return pts.join(' ');
}

/**
 * Date-aware rolling mean over [{date, value}] (ascending dates): for each
 * record, the mean of PRESENT values within the trailing `windowDays` window
 * (gap days contribute nothing — the mean is over logged values only).
 */
export function rollingMean(dailyValues, windowDays) {
    const out = [];
    for (let i = 0; i < dailyValues.length; i++) {
        const endDate = dailyValues[i].date;
        let sum = 0;
        let n = 0;
        for (let j = i; j >= 0; j--) {
            const age = dayIndex(endDate, dailyValues[j].date);
            if (age >= windowDays) break;
            if (dailyValues[j].value != null) {
                sum += dailyValues[j].value;
                n += 1;
            }
        }
        out.push({ date: endDate, value: n ? sum / n : null });
    }
    return out;
}

/** Dot radius scale for the aerobic-proxy plot: sqrt-area scaling so a
 *  2×-duration session reads ~2× the INK, not 2× the radius. */
export function dotSizeScale(minR, maxR, values) {
    const present = values.filter(v => v != null);
    if (!present.length) return () => minR;
    const lo = Math.min(...present);
    const hi = Math.max(...present);
    if (lo === hi) return () => (minR + maxR) / 2;
    return (v) => {
        const t = (v - lo) / (hi - lo);
        return minR + (maxR - minR) * Math.sqrt(t);
    };
}

function round2(v) {
    return Math.round(v * 100) / 100;
}
