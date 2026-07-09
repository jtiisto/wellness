/**
 * Health screen (v2 Phase 1): non-training body signals — recovery cards
 * (HRV vs Garmin's own baseline band, resting HR with rolling means, sleep
 * hours + score) over a weekly training-load context strip sharing the same
 * range. Deterministic display only: aligned axes, no computed correlations.
 * Degrades card-by-card when Garmin data is unavailable.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import {
    dayIndex, linearScale, seriesToPoints, linePath, rollingMean,
    steppedBandRects, dailyBandSegments, stackedBarLayout,
} from '../chart-logic.js';
import { BarChartStacked } from './BarChartStacked.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

const W = 360;

export function HealthScreen() {
    const [recovery, setRecovery] = useState(null);
    const [volume, setVolume] = useState(null);
    const [cardio, setCardio] = useState(null);
    const [error, setError] = useState(null);
    const [, forceRender] = useState(0);

    useEffect(() => {
        const dispose = effect(() => { range.value; forceRender(n => n + 1); });
        return dispose;
    }, []);

    const today = getToday();
    const start = rangeStart(range.value, today);
    const q = start ? `?start=${start}&end=${today}` : `?end=${today}`;

    useEffect(() => {
        let cancelled = false;
        setError(null);
        fetchCached(`health/recovery:${range.value}`, `/health/recovery${q}`)
            .then(d => !cancelled && setRecovery(d))
            .catch(err => !cancelled && setError(err.message));
        // Load context reuses the strength/cardio caches (same keys as their
        // own screens, so offline serves whichever screen filled them).
        fetchCached(`volume:${range.value}`, `/strength/volume${q}`)
            .then(d => !cancelled && setVolume(d.weeks))
            .catch(() => {});
        fetchCached(`cardio:${range.value}`, `/cardio${q}`)
            .then(d => !cancelled && setCardio(d.weeks))
            .catch(() => {});
        return () => { cancelled = true; };
    }, [q]);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${[`health/recovery:${range.value}`]}/>
            </div>
            ${error && html`<div class="trends-error">${error}</div>`}

            ${recovery && !recovery.available && html`
                <div class="trends-chart-empty">Garmin data unavailable</div>
            `}
            ${recovery && recovery.available && html`
                <${HrvCard} days=${recovery.days}/>
                <${RhrCard} days=${recovery.days}/>
                <${SleepCard} days=${recovery.days}/>
            `}
            ${volume && html`<${LoadStrip} title="Weekly tonnage" unit="kg"
                weeks=${volume} valueOf=${w => w.tonnage_kg}
                yFormat=${(v) => v >= 1000 ? `${Math.round(v / 100) / 10}t` : v}/>`}
            ${cardio && html`<${LoadStrip} title="Weekly Zone 2" unit="min"
                weeks=${cardio} valueOf=${w => w.zone2_planned_min + w.zone2_extra_min}/>`}
        </div>
    `;
}

/** Shared scaffolding: x scale over the day range, spread date ticks. */
function dayChart(days, valueOf) {
    const present = days.filter(d => valueOf(d) != null);
    if (!present.length) return null;
    const origin = days[0].date;
    const xs = present.map(d => dayIndex(d.date, origin));
    return { present, origin, xMin: Math.min(...xs), xMax: Math.max(...xs) };
}

function dateTicks(present, origin, xScale) {
    return spread(present.map(d => ({
        x: xScale(dayIndex(d.date, origin)), label: d.date.slice(5),
    })), 5);
}

function HrvCard({ days }) {
    const c = dayChart(days, d => d.hrv);
    if (!c) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">HRV</h3>
            <div class="trends-chart-empty">No HRV data in range</div>
        </section>`;
    }
    const H = 200, M = { top: 10, right: 10, bottom: 22, left: 40 };
    const bandYs = days.flatMap(d => d.hrv_band
        ? [d.hrv_band.low, d.hrv_band.high, d.hrv_band.low_floor] : [])
        .filter(v => v != null);
    const ys = c.present.map(d => d.hrv);
    const yMin = Math.min(...ys, ...bandYs);
    const yMax = Math.max(...ys, ...bandYs);
    const pad = (yMax - yMin) * 0.1 || 1;

    const xScale = linearScale(c.xMin, c.xMax + 1, M.left, W - M.right);
    const yScale = linearScale(yMin - pad, yMax + pad, H - M.bottom, M.top);

    const segs = dailyBandSegments(
        days, d => dayIndex(d.date, c.origin), d => d.hrv_band);
    const rects = steppedBandRects(
        segs, c.xMin, c.xMax + 1, xScale, yScale, M.top, H - M.bottom);

    const dots = seriesToPoints(
        c.present, d => dayIndex(d.date, c.origin), d => d.hrv, xScale, yScale);
    // Warning tone for nights below Garmin's low-zone ceiling.
    const lowFloorOf = (d) => (d.hrv_band ? d.hrv_band.low_floor : null);
    const mean = rollingMean(days.map(d => ({ date: d.date, value: d.hrv })), 7)
        .filter(m => m.value != null);
    const meanPts = seriesToPoints(
        mean, m => dayIndex(m.date, c.origin), m => m.value, xScale, yScale);

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">HRV
                <span class="trends-unit">ms · last night · 7d mean · Garmin baseline</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${yMin - pad} yMax=${yMax + pad} yScale=${yScale}
                          x0=${M.left} x1=${W - M.right}/>
                ${rects.map((r, i) => html`
                    <rect key=${i} x=${r.x} y=${r.yTop} width=${r.w}
                          height=${r.yBot - r.yTop} class="trends-band"/>
                `)}
                ${meanPts.length > 1 && html`<path d=${linePath(meanPts)} class="trends-line"/>`}
                ${c.present.map((d, i) => html`
                    <circle key=${d.date} cx=${dots[i].x} cy=${dots[i].y} r="2.5"
                            class="trends-dot ${lowFloorOf(d) != null && d.hrv < lowFloorOf(d)
                                ? 'trends-dot--warn' : 'trends-dot--value'}"/>
                `)}
                <${XAxis} ticks=${dateTicks(c.present, c.origin, xScale)} y=${H - 6}/>
            </svg>
        </section>
    `;
}

function RhrCard({ days }) {
    const c = dayChart(days, d => d.rhr);
    if (!c) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">Resting HR</h3>
            <div class="trends-chart-empty">No RHR data in range</div>
        </section>`;
    }
    const H = 180, M = { top: 10, right: 10, bottom: 22, left: 40 };
    const ys = c.present.map(d => d.rhr);
    const yMin = Math.min(...ys), yMax = Math.max(...ys);
    const pad = (yMax - yMin) * 0.1 || 1;
    const xScale = linearScale(c.xMin, c.xMax, M.left, W - M.right);
    const yScale = linearScale(yMin - pad, yMax + pad, H - M.bottom, M.top);

    const dots = seriesToPoints(
        c.present, d => dayIndex(d.date, c.origin), d => d.rhr, xScale, yScale);
    const series = days.map(d => ({ date: d.date, value: d.rhr }));
    const mean7 = rollingMean(series, 7).filter(m => m.value != null);
    const mean28 = rollingMean(series, 28).filter(m => m.value != null);
    const pts7 = seriesToPoints(mean7, m => dayIndex(m.date, c.origin), m => m.value, xScale, yScale);
    const pts28 = seriesToPoints(mean28, m => dayIndex(m.date, c.origin), m => m.value, xScale, yScale);

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Resting HR
                <span class="trends-unit">bpm · 7d & 28d means</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${yMin - pad} yMax=${yMax + pad} yScale=${yScale}
                          x0=${M.left} x1=${W - M.right}/>
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2" class="trends-dot trends-dot--value"/>
                `)}
                ${pts7.length > 1 && html`<path d=${linePath(pts7)} class="trends-line"/>`}
                ${pts28.length > 1 && html`<path d=${linePath(pts28)} class="trends-line trends-line--alt"/>`}
                <${XAxis} ticks=${dateTicks(c.present, c.origin, xScale)} y=${H - 6}/>
            </svg>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--primary">7d mean</span>
                <span class="trends-legend-item trends-legend--alt">28d mean</span>
            </div>
        </section>
    `;
}

function SleepCard({ days }) {
    const c = dayChart(days, d => d.sleep_hours);
    if (!c) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">Sleep</h3>
            <div class="trends-chart-empty">No sleep data in range</div>
        </section>`;
    }
    const H = 180, M = { top: 10, right: 34, bottom: 22, left: 40 };
    const yMax = Math.max(...c.present.map(d => d.sleep_hours), 9);
    const xScale = linearScale(c.xMin - 0.5, c.xMax + 0.5, M.left, W - M.right);
    const yScale = linearScale(0, yMax * 1.05, H - M.bottom, M.top);
    const barWidth = Math.max(1.5, Math.min(10,
        ((W - M.left - M.right) / (c.xMax - c.xMin + 1)) * 0.7));
    const layout = stackedBarLayout(
        c.present.map(d => ({ week_start: d.date, values: { h: d.sleep_hours } })),
        ['h'],
        (i) => xScale(dayIndex(c.present[i].date, c.origin)), yScale, barWidth);

    // Sleep score on a fixed right-hand 0-100 scale (faint dots).
    const scoreScale = linearScale(0, 100, H - M.bottom, M.top);
    const scoreDots = days.filter(d => d.sleep_score != null).map(d => ({
        x: xScale(dayIndex(d.date, c.origin)), y: scoreScale(d.sleep_score),
    }));

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Sleep
                <span class="trends-unit">hours · score (right) · 8h guide</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${0} yMax=${yMax * 1.05} yScale=${yScale}
                          x0=${M.left} x1=${W - M.right}/>
                ${layout.map((bar, i) => html`
                    <g key=${bar.weekStart}>
                        ${bar.segs.map(seg => html`
                            <rect key=${seg.key} x=${seg.x} y=${seg.y} width=${seg.w}
                                  height=${seg.h} rx="1" class="trends-bar"/>
                        `)}
                    </g>
                `)}
                <line x1=${M.left} y1=${yScale(8)} x2=${W - M.right} y2=${yScale(8)}
                      class="trends-guide"/>
                ${scoreDots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="1.8" class="trends-dot trends-dot--secondary"/>
                `)}
                <text x=${W - M.right + 4} y=${scoreScale(100) + 3} class="trends-tick">100</text>
                <text x=${W - M.right + 4} y=${scoreScale(0) + 3} class="trends-tick">0</text>
                <${XAxis} ticks=${dateTicks(c.present, c.origin, xScale)} y=${H - 6}/>
            </svg>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--primary">hours</span>
                <span class="trends-legend-item trends-legend--secondary">score</span>
                <span class="trends-legend-item trends-legend--muted">— 8h</span>
            </div>
        </section>
    `;
}

function LoadStrip({ title, unit, weeks, valueOf, yFormat }) {
    const stackWeeks = weeks.map(w => ({
        week_start: w.week_start, partial: w.partial, values: { v: valueOf(w) },
    }));
    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">${title}
                <span class="trends-unit">${unit} · training-load context</span></h3>
            <${BarChartStacked} weeks=${stackWeeks} height=${100}
                                keys=${[{ key: 'v', cssClass: 'trends-stack-0' }]}
                                yFormat=${yFormat}/>
        </section>
    `;
}
