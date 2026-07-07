/**
 * Overview screen: headline stat tiles (Phase 6) + the body-weight chart.
 * Weight comes from the Garmin health DB read-only; the card hides entirely
 * when the source is unavailable (`available: false`).
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import { dayIndex, linearScale, seriesToPoints, linePath, rollingMean } from '../chart-logic.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

export function OverviewScreen() {
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
        fetchCached(`weight:${range.value}`, `/weight${q}`)
            .then(d => !cancelled && setWeight(d))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [q]);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${[`weight:${range.value}`]}/>
            </div>
            ${error && html`<div class="trends-error">${error}</div>`}
            ${weight && weight.available && weight.series.length > 0 && html`
                <${WeightCard} series=${weight.series}/>
            `}
        </div>
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
