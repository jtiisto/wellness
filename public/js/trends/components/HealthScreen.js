/**
 * Health screen (v2): non-training body signals — recovery cards (HRV vs
 * Garmin's own baseline band, resting HR with rolling means, sleep hours +
 * score), DEXA composition, and labs. Deterministic display only; degrades
 * card-by-card when a source is unavailable. Training-load strips were
 * removed after live feedback: they duplicated the Strength/Cardio tabs.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import {
    coerceNumeric, dayIndex, linearScale, seriesToPoints, linePath,
    rollingMean, steppedBandRects, dailyBandSegments, stackedBarLayout,
} from '../chart-logic.js';
import { PillSelect } from './PillSelect.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

const W = 360;

export function HealthScreen() {
    const [recovery, setRecovery] = useState(null);
    const [weight, setWeight] = useState(null);
    const [composition, setComposition] = useState(null);
    const [labs, setLabs] = useState(null);
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
        // Weight reuses the Overview cache key; composition is
        // range-independent (scans are months apart — always show all).
        fetchCached(`weight:${range.value}`, `/weight${q}`)
            .then(d => !cancelled && setWeight(d))
            .catch(() => {});
        fetchCached('health/composition', `/health/composition?end=${today}`)
            .then(d => !cancelled && setComposition(d))
            .catch(() => {});
        fetchCached('health/labs', `/health/labs?end=${today}`)
            .then(d => !cancelled && setLabs(d))
            .catch(() => {});
        return () => { cancelled = true; };
    }, [q]);

    const scans = composition && composition.available ? composition.scans : [];

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
            ${weight && weight.available && weight.series.length > 0 && html`
                <${BodyCard} series=${weight.series} scans=${scans}/>
            `}
            ${scans.length > 0 && html`<${CompositionCard} scans=${scans}/>`}
            ${labs && labs.available && labs.panels.length > 0 && html`
                <${LabsSection} panels=${labs.panels}/>
            `}
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

function BodyCard({ series, scans }) {
    // Body weight (Garmin scale) with DEXA total-mass scan rings — the
    // scale-vs-DEXA sanity check on one honest axis. Lean/fat live in the
    // composition card: ~58/~25 kg on this axis would flatten the trend.
    const H = 200, M = { top: 10, right: 10, bottom: 22, left: 40 };
    const origin = series[0].date;
    const last = series[series.length - 1].date;
    const inRange = scans.filter(s => s.date >= origin && s.date <= last
        && s.total_kg != null);
    const ys = [...series.map(s => s.kg), ...inRange.map(s => s.total_kg)];
    const xs = series.map(s => dayIndex(s.date, origin));
    const pad = (Math.max(...ys) - Math.min(...ys)) * 0.12 || 0.5;

    const xScale = linearScale(Math.min(...xs), Math.max(...xs), M.left, W - M.right);
    const yScale = linearScale(Math.min(...ys) - pad, Math.max(...ys) + pad,
                               H - M.bottom, M.top);

    const dots = seriesToPoints(series, s => dayIndex(s.date, origin), s => s.kg, xScale, yScale);
    const mean7 = rollingMean(series.map(s => ({ date: s.date, value: s.kg })), 7)
        .filter(m => m.value != null);
    const pts7 = seriesToPoints(mean7, m => dayIndex(m.date, origin), m => m.value, xScale, yScale);
    const scanPts = inRange.map(s => ({
        x: xScale(dayIndex(s.date, origin)), y: yScale(s.total_kg),
    }));

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Body weight + DEXA
                <span class="trends-unit">kg · 7d mean · scan total mass</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${Math.min(...ys) - pad} yMax=${Math.max(...ys) + pad}
                          yScale=${yScale} x0=${M.left} x1=${W - M.right}
                          format=${(v) => v.toFixed ? v.toFixed(1) : v}/>
                ${pts7.length > 1 && html`<path d=${linePath(pts7)} class="trends-line"/>`}
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2" class="trends-dot trends-dot--value"/>
                `)}
                ${scanPts.length > 1 && html`
                    <path d=${linePath(scanPts)} class="trends-line trends-line--scan"/>
                `}
                ${scanPts.map((p, i) => html`
                    <circle key=${'s' + i} cx=${p.x} cy=${p.y} r="4" class="trends-marker--scan"/>
                `)}
                <${XAxis} ticks=${dateTicks(series, origin, xScale)} y=${H - 6}/>
            </svg>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--primary">7d mean</span>
                <span class="trends-legend-item trends-legend--secondary">DEXA total</span>
                ${inRange.length === 0 && html`
                    <span class="trends-legend-item trends-legend--muted">
                        no scans in range — see composition below</span>`}
            </div>
        </section>
    `;
}

const COMPOSITION_METRICS = [
    { key: 'lean_kg', label: 'Lean mass', unit: 'kg' },
    { key: 'fat_kg', label: 'Fat mass', unit: 'kg' },
    { key: 'body_fat_pct', label: 'Body fat', unit: '%' },
    { key: 'vat_kg', label: 'VAT', unit: 'kg' },
    { key: 'ag_ratio', label: 'A/G ratio', unit: '' },
];

function CompositionCard({ scans }) {
    // All scans regardless of range — months apart, a 12w window would show
    // at most one. Small multiples share the x domain; bone is a table.
    const origin = scans[0].date;
    const xMax = Math.max(...scans.map(s => dayIndex(s.date, origin)), 1);
    const M = { left: 64, right: 44 };
    const xScale = linearScale(0, xMax, M.left, W - M.right);
    const ticks = spread(scans.map(s => ({
        x: xScale(dayIndex(s.date, origin)), label: s.date.slice(2, 7),
    })), 4);
    const boneRows = scans.filter(s => s.bmd_total != null);

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Composition
                <span class="trends-unit">DEXA · all scans</span></h3>
            ${COMPOSITION_METRICS.map(m => html`
                <${MiniMetric} key=${m.key} scans=${scans} metric=${m}
                               origin=${origin} xScale=${xScale}/>
            `)}
            <svg viewBox="0 0 ${W} 16" class="trends-chart" role="img">
                <${XAxis} ticks=${ticks} y=${11}/>
            </svg>
            ${boneRows.length > 0 && html`
                <div class="trends-bone-table">
                    ${boneRows.map(s => html`
                        <div class="trends-pr-row" key=${s.date}>
                            <div class="trends-pr-name">Bone (total) <span class="trends-pr-slug">${s.date}</span></div>
                            <div class="trends-pr-vals">
                                <span>${s.bmd_total} g/cm²</span>
                                <span class="trends-pr-detail">t-score ${s.t_score_total}</span>
                            </div>
                        </div>
                    `)}
                </div>
            `}
        </section>
    `;
}

function MiniMetric({ scans, metric, origin, xScale }) {
    const pts = scans.filter(s => s[metric.key] != null);
    if (!pts.length) return null;
    const H = 56, top = 8, bottom = 8;
    const ys = pts.map(s => s[metric.key]);
    const yMin = Math.min(...ys), yMax = Math.max(...ys);
    const pad = (yMax - yMin) * 0.15 || 0.1;
    const yScale = linearScale(yMin - pad, yMax + pad, H - bottom, top);
    const dots = pts.map(s => ({
        x: xScale(dayIndex(s.date, origin)), y: yScale(s[metric.key]),
    }));
    const latest = pts[pts.length - 1][metric.key];

    return html`
        <div class="trends-mini-metric">
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <text x="4" y=${H / 2 + 3} class="trends-tick">${metric.label}</text>
                ${dots.length > 1 && html`<path d=${linePath(dots)} class="trends-line trends-line--scan"/>`}
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2.5" class="trends-marker--scan"/>
                `)}
                <text x=${W - 40} y=${H / 2 + 3} class="trends-tick">
                    ${latest}${metric.unit}</text>
            </svg>
        </div>
    `;
}

function LabsSection({ panels }) {
    // Labs: panel picker (the Journal-screen pattern) → per-test mini charts
    // for tests with ≥2 numeric observations, a latest-value table for the
    // rest. Coloring uses the LAB's own H/L flag, never a recomputed range.
    const [panel, setPanel] = useState(
        localStorage.getItem('trends_lab_panel') || panels[0].name);
    const current = panels.find(p => p.name === panel) || panels[0];
    useEffect(() => { localStorage.setItem('trends_lab_panel', current.name); },
              [current.name]);

    const numericObs = (t) => t.observations
        .map(o => ({ ...o, num: coerceNumeric(o.value) }))
        .filter(o => o.num != null);
    const chartable = current.tests
        .map(t => ({ ...t, obs: numericObs(t) }))
        .filter(t => t.obs.length >= 2);
    const tabular = current.tests.filter(
        t => numericObs(t).length < 2);

    const allDates = current.tests.flatMap(t => t.observations.map(o => o.date));
    const origin = allDates.slice().sort()[0];

    return html`
        <section class="trends-card">
            <div class="trends-card-head">
                <h3 class="trends-card-title">Labs
                    <span class="trends-unit">Quest · all reports</span></h3>
            </div>
            <${PillSelect} title="Panel" value=${current.name}
                onChange=${setPanel}
                options=${panels.map(p => ({ value: p.name, label: p.name }))}/>
            ${chartable.map(t => html`
                <${MiniLab} key=${t.name} test=${t} origin=${origin}/>
            `)}
            ${tabular.length > 0 && html`
                <div class="trends-bone-table">
                    ${tabular.map(t => {
                        const last = t.observations[t.observations.length - 1];
                        return html`
                            <div class="trends-pr-row" key=${t.name}>
                                <div class="trends-pr-name">${t.name}
                                    <span class="trends-pr-slug">${last.date}${last.ref_text ? ` · ref ${last.ref_text}` : ''}</span></div>
                                <div class="trends-pr-vals">
                                    <span class=${last.flag ? 'trends-lab-flag' : ''}>
                                        ${last.text != null ? last.text
                                            : `${last.prefix || ''}${last.value ?? '—'}`}${t.unit ? ` ${t.unit}` : ''}
                                    </span>
                                </div>
                            </div>
                        `;
                    })}
                </div>
            `}
        </section>
    `;
}

function MiniLab({ test, origin }) {
    const H = 64, M = { left: 8, right: 52 }, top = 10, bottom = 14;
    const obs = test.obs;
    const latest = obs[obs.length - 1];
    const xMax = Math.max(...obs.map(o => dayIndex(o.date, origin)), 1);
    const xScale = linearScale(0, xMax, M.left, W - M.right);
    const band = { low: latest.ref_low, high: latest.ref_high };
    const bandYs = [band.low, band.high].filter(v => v != null);
    const ys = obs.map(o => o.num);
    const yMin = Math.min(...ys, ...bandYs);
    const yMax = Math.max(...ys, ...bandYs);
    const pad = (yMax - yMin) * 0.2 || yMax * 0.1 || 1;
    const yScale = linearScale(yMin - pad, yMax + pad, H - bottom, top);

    // One constant band from the LATEST range (ranges rarely move; per-dot
    // correctness comes from the lab's flag, not the band).
    const rects = (band.low != null || band.high != null)
        ? steppedBandRects([{ x0: 0, x1: xMax + 1, min: band.low, max: band.high }],
                           0, xMax + 1, xScale, yScale, top, H - bottom)
        : [];
    const dots = obs.map(o => ({
        x: xScale(dayIndex(o.date, origin)), y: yScale(o.num), flag: o.flag,
    }));
    const ticks = spread(obs.map(o => ({
        x: xScale(dayIndex(o.date, origin)), label: o.date.slice(2, 7),
    })), 3);

    return html`
        <div class="trends-mini-metric">
            <div class="trends-lab-name">${test.name}</div>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                ${rects.map((r, i) => html`
                    <rect key=${i} x=${r.x} y=${r.yTop} width=${r.w}
                          height=${r.yBot - r.yTop} class="trends-band"/>
                `)}
                ${dots.length > 1 && html`<path d=${linePath(dots)} class="trends-line trends-line--scan"/>`}
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2.5"
                            class="trends-dot ${p.flag ? 'trends-dot--warn' : 'trends-dot--value'}"/>
                `)}
                <text x=${W - M.right + 6} y=${H / 2 + 3} class="trends-tick">
                    ${latest.prefix || ''}${latest.num}${test.unit ? ` ${test.unit}` : ''}</text>
                <${XAxis} ticks=${ticks} y=${H - 3}/>
            </svg>
        </div>
    `;
}

