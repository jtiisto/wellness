/**
 * Cardio screen: weekly Zone 2 minutes stacked planned-vs-extra (+ interval
 * count line under the bars), and the aerobic-base proxy — avg HR of ≥20-min
 * steady sessions over time, dot size = duration. Deliberately humble
 * framing: the proxy is a proxy, not a lactate test.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import { dayIndex, linearScale, seriesToPoints, dotSizeScale } from '../chart-logic.js';
import { BarChartStacked } from './BarChartStacked.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

export function CardioScreen() {
    const [data, setData] = useState(null);
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
        fetchCached(`cardio:${range.value}`, `/cardio${q}`)
            .then(d => !cancelled && setData(d))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [q]);

    if (error) return html`<div class="trends-screen"><div class="trends-error">${error}</div></div>`;
    if (!data) return html`<div class="trends-screen"><div class="trends-chart-empty">Loading…</div></div>`;

    const stackWeeks = data.weeks.map(w => ({
        week_start: w.week_start,
        partial: w.partial,
        values: { planned: w.zone2_planned_min, extra: w.zone2_extra_min },
    }));
    const totalIntervals = data.weeks.reduce((acc, w) => acc + w.interval_sessions, 0);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${[`cardio:${range.value}`]}/>
            </div>

            <section class="trends-card">
                <h3 class="trends-card-title">Weekly Zone 2 <span class="trends-unit">min</span></h3>
                <${BarChartStacked}
                    weeks=${stackWeeks}
                    keys=${[
                        { key: 'planned', cssClass: 'trends-stack-0' },
                        { key: 'extra', cssClass: 'trends-stack-extra' },
                    ]}/>
                <div class="trends-legend">
                    <span class="trends-legend-item trends-legend--primary">planned</span>
                    <span class="trends-legend-item trends-legend--secondary">extra</span>
                    ${totalIntervals > 0 && html`
                        <span class="trends-legend-item trends-legend--muted">
                            ${totalIntervals} interval session${totalIntervals === 1 ? '' : 's'} in range
                        </span>`}
                </div>
            </section>

            <${AerobicProxyCard} sessions=${data.steady_sessions}/>
        </div>
    `;
}

function AerobicProxyCard({ sessions }) {
    if (!sessions.length) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">Steady-session HR <span class="trends-unit">avg bpm, ≥20 min</span></h3>
            <div class="trends-chart-empty">No steady sessions with HR in range</div>
        </section>`;
    }

    const W = 360, H = 190;
    const M = { top: 10, right: 10, bottom: 22, left: 36 };
    const origin = sessions[0].date;
    const xs = sessions.map(s => dayIndex(s.date, origin));
    const hrs = sessions.map(s => s.avg_hr);
    const pad = 4;
    const xScale = linearScale(Math.min(...xs), Math.max(...xs), M.left, W - M.right);
    const yScale = linearScale(Math.min(...hrs) - pad, Math.max(...hrs) + pad, H - M.bottom, M.top);
    const rScale = dotSizeScale(2.5, 7, sessions.map(s => s.duration_min));
    const pts = seriesToPoints(sessions, s => dayIndex(s.date, origin), s => s.avg_hr, xScale, yScale);
    const ticks = spread(sessions.map(s => ({
        x: xScale(dayIndex(s.date, origin)), label: s.date.slice(5),
    })), 5);

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Steady-session HR
                <span class="trends-unit">avg bpm, ≥20 min · dot = duration</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${Math.min(...hrs) - pad} yMax=${Math.max(...hrs) + pad}
                          yScale=${yScale} x0=${M.left} x1=${W - M.right}/>
                ${pts.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r=${rScale(sessions[i].duration_min)}
                            class="trends-dot ${sessions[i].off_plan ? 'trends-dot--muted' : ''}"/>
                `)}
                <${XAxis} ticks=${ticks} y=${H - 6}/>
            </svg>
        </section>
    `;
}
