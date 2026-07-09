/**
 * Overview screen: headline stat tiles (Phase 6) + the body-weight chart.
 * Weight comes from the Garmin health DB read-only; the card hides entirely
 * when the source is unavailable (`available: false`).
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range, setActiveScreen } from '../store.js';
import {
    dayIndex, linearScale, seriesToPoints, linePath, rollingMean, sparklinePoints,
} from '../chart-logic.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

export function OverviewScreen() {
    const [overview, setOverview] = useState(null);
    const [weight, setWeight] = useState(null);
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
        fetchCached('overview', '/overview')
            .then(d => !cancelled && setOverview(d))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, []);

    useEffect(() => {
        let cancelled = false;
        setError(null);  // a prior failure must not outlive its fetch (F21)
        fetchCached(`weight:${range.value}`, `/weight${q}`)
            .then(d => !cancelled && setWeight(d))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [q]);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${['overview', `weight:${range.value}`]}/>
            </div>
            ${error && html`<div class="trends-error">${error}</div>`}
            ${overview && html`
                <div class="trends-tiles">
                    <${StatTile}
                        label="Zone 2 last week" unit="min"
                        value=${overview.zone2.last_week_min}
                        avg=${overview.zone2.four_week_avg_min}
                        soFar=${overview.zone2.this_week_min}
                        spark=${overview.zone2.sparkline.map(w => w.planned_min + w.extra_min)}
                        onClick=${() => setActiveScreen('cardio')}/>
                    <${StatTile}
                        label="Tonnage last week" unit="kg"
                        value=${overview.tonnage.last_week_kg}
                        avg=${overview.tonnage.four_week_avg_kg}
                        soFar=${overview.tonnage.this_week_kg}
                        spark=${overview.tonnage.sparkline.map(w => w.tonnage_kg)}
                        onClick=${() => setActiveScreen('strength')}/>
                </div>
                ${overview.prs.count_30d > 0 && html`<${PRTile} prs=${overview.prs}/>`}
                ${overview.adherence_focus.length > 0 && html`
                    <${FocusCard} focus=${overview.adherence_focus}/>
                `}
            `}
            ${weight && weight.available && weight.series.length > 0 && html`
                <${WeightCard} series=${weight.series}/>
            `}
        </div>
    `;
}

function StatTile({ label, unit, value, avg, soFar, spark, onClick }) {
    const pts = sparklinePoints(spark, 96, 26);
    // Delta compares COMPLETE weeks only (value = last complete week); the
    // in-progress week renders as a delta-free "so far" line.
    const delta = avg && value != null ? Math.round((value / avg - 1) * 100) : null;
    return html`
        <button class="trends-tile" onClick=${onClick}>
            <div class="trends-tile-label">${label}</div>
            <div class="trends-tile-value">${value ?? 0}<span class="trends-unit">${unit}</span></div>
            ${avg != null && html`
                <div class="trends-tile-avg">
                    4wk avg ${avg}${delta != null ? ` · ${delta >= 0 ? '+' : ''}${delta}%` : ''}
                </div>
            `}
            <div class="trends-tile-avg">this week so far: ${soFar} ${unit}</div>
            ${pts && html`
                <svg viewBox="0 0 96 26" class="trends-sparkline" aria-hidden="true">
                    <polyline points=${pts} class="trends-sparkline-line"/>
                </svg>
            `}
        </button>
    `;
}

function PRTile({ prs }) {
    const l = prs.latest;
    return html`
        <section class="trends-card trends-pr-tile">
            <span class="trends-pr-badge">🏆 ${prs.count_30d} PR${prs.count_30d === 1 ? '' : 's'} in 30d</span>
            ${l && html`<span class="trends-pr-latest">
                latest: ${l.name} e1RM ${l.e1rm} ${l.unit} (${l.date})
            </span>`}
        </section>
    `;
}

function FocusCard({ focus }) {
    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Adherence focus
                <span class="trends-unit">weakest, rolling 14d</span></h3>
            ${focus.map(f => html`
                <div class="trends-focus-row" key=${f.tracker_id}>
                    <div class="trends-focus-name">${f.name}
                        <span class="trends-focus-rate">${Math.round(f.rate * 100)}% ${f.metric_kind}</span>
                        ${f.dropping && html`<span class="trends-drop-badge"
                            title="≥15 points below the preceding 14 days">↓ dropping</span>`}
                    </div>
                    <div class="trends-focus-dots">
                        ${f.ribbon.map(r => html`
                            <span key=${r.date} class="trends-day-dot trends-day--${r.status}"
                                  title="${r.date}: ${r.status}"></span>
                        `)}
                    </div>
                </div>
            `)}
        </section>
    `;
}

function WeightCard({ series }) {
    const W = 360, H = 200;
    const M = { top: 10, right: 10, bottom: 22, left: 40 };
    const origin = series[0].date;
    const xs = series.map(s => dayIndex(s.date, origin));
    const ys = series.map(s => s.kg);
    const pad = (Math.max(...ys) - Math.min(...ys)) * 0.15 || 0.5;

    const xScale = linearScale(Math.min(...xs), Math.max(...xs), M.left, W - M.right);
    const yScale = linearScale(Math.min(...ys) - pad, Math.max(...ys) + pad, H - M.bottom, M.top);

    const dots = seriesToPoints(series, s => dayIndex(s.date, origin), s => s.kg, xScale, yScale);
    const mean7 = rollingMean(series.map(s => ({ date: s.date, value: s.kg })), 7)
        .filter(m => m.value != null);
    const mean28 = rollingMean(series.map(s => ({ date: s.date, value: s.kg })), 28)
        .filter(m => m.value != null);
    const toPts = (mm) => seriesToPoints(mm, m => dayIndex(m.date, origin), m => m.value, xScale, yScale);
    const ticks = spread(series.map(s => ({
        x: xScale(dayIndex(s.date, origin)), label: s.date.slice(5),
    })), 5);
    const latest = series[series.length - 1];

    return html`
        <section class="trends-card">
            <div class="trends-card-head">
                <h3 class="trends-card-title">Body weight
                    <span class="trends-unit">kg · 7d & 28d mean</span></h3>
                <span class="trends-latest">${latest.kg} kg</span>
            </div>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${Math.min(...ys) - pad} yMax=${Math.max(...ys) + pad}
                          yScale=${yScale} x0=${M.left} x1=${W - M.right}
                          format=${(v) => v.toFixed ? v.toFixed(1) : v}/>
                ${mean28.length > 1 && html`<path d=${linePath(toPts(mean28))} class="trends-line trends-line--alt"/>`}
                ${mean7.length > 1 && html`<path d=${linePath(toPts(mean7))} class="trends-line"/>`}
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2" class="trends-dot trends-dot--value"/>
                `)}
                <${XAxis} ticks=${ticks} y=${H - 6}/>
            </svg>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--primary">7d mean</span>
                <span class="trends-legend-item trends-legend--alt">28d mean</span>
            </div>
        </section>
    `;
}
