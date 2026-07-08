/**
 * Journal screen: tracker picker → value-vs-target chart (daily dots +
 * 7-day rolling mean + stepped target band — the effective-dated history IS
 * the annotation), weekly adherence ribbon (pause weeks muted, never
 * failure), and streak numbers.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import {
    dayIndex, linearScale, seriesToPoints, linePath,
    steppedBandRects, rollingMean, ribbonCells,
} from '../chart-logic.js';
import { RangeSelector, StaleBadge, rangeStart, spread, YAxis, XAxis } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

export function JournalScreen() {
    const [trackers, setTrackers] = useState(null);
    const [selected, setSelected] = useState(localStorage.getItem('trends_tracker') || null);
    const [detail, setDetail] = useState(null);
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
        fetchCached('journal/trackers', '/journal/trackers')
            .then(data => {
                if (cancelled) return;
                setTrackers(data.trackers);
                if (!data.trackers.length) return;
                if (!data.trackers.some(t => t.id === selected)) {
                    setSelected(data.trackers[0].id);
                }
            })
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, []);

    useEffect(() => {
        if (!selected) return;
        let cancelled = false;
        localStorage.setItem('trends_tracker', selected);
        fetchCached(`journal/${selected}:${range.value}`,
                    `/journal/tracker/${encodeURIComponent(selected)}${q}`)
            .then(data => !cancelled && setDetail(data))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [selected, q]);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${[
                    'journal/trackers',
                    selected ? `journal/${selected}:${range.value}` : '',
                ]}/>
            </div>
            ${error && html`<div class="trends-error">${error}</div>`}

            ${trackers && trackers.length === 0 && html`
                <div class="trends-chart-empty">No trackers with entries yet</div>
            `}
            ${trackers && trackers.length > 0 && html`
                <select class="form-select trends-picker" value=${selected}
                        onChange=${(e) => setSelected(e.target.value)}>
                    ${trackers.map(t => html`
                        <option key=${t.id} value=${t.id}>
                            ${t.name}${t.unit ? ` (${t.unit})` : ''}
                        </option>
                    `)}
                </select>
            `}

            ${detail && html`
                ${detail.tracker.type === 'quantifiable' && html`
                    <${ValueTargetCard} detail=${detail}/>
                `}
                ${detail.tracker.actionable && html`
                    <${AdherenceCard} detail=${detail}/>
                `}
            `}
        </div>
    `;
}

function ValueTargetCard({ detail }) {
    const values = detail.values.filter(v => v.value != null);
    if (!values.length) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">${detail.tracker.name}</h3>
            <div class="trends-chart-empty">No values in range</div>
        </section>`;
    }

    const W = 360, H = 200;
    const M = { top: 10, right: 10, bottom: 22, left: 40 };
    const origin = values[0].date;
    const xs = values.map(v => dayIndex(v.date, origin));
    const ys = values.map(v => v.value);
    const bandYs = detail.target_segments.flatMap(s =>
        [s.min, s.max].filter(v => v != null));
    const yMin = Math.min(...ys, ...bandYs);
    const yMax = Math.max(...ys, ...bandYs);
    const pad = (yMax - yMin) * 0.1 || yMax * 0.05 || 1;

    const xScale = linearScale(Math.min(...xs), Math.max(...xs), M.left, W - M.right);
    const yScale = linearScale(yMin - pad, yMax + pad, H - M.bottom, M.top);

    // Inclusive [start,end] segments → x0..x1+1 so the band covers end's day.
    const bandSegs = detail.target_segments.map(s => ({
        x0: dayIndex(s.start, origin), x1: dayIndex(s.end, origin) + 1,
        min: s.min, max: s.max,
    }));
    const rects = steppedBandRects(
        bandSegs, Math.min(...xs), Math.max(...xs), xScale, yScale, M.top, H - M.bottom);

    const dots = seriesToPoints(values, v => dayIndex(v.date, origin), v => v.value, xScale, yScale);
    const mean = rollingMean(values.map(v => ({ date: v.date, value: v.value })), 7);
    const meanPts = seriesToPoints(
        mean.filter(m => m.value != null),
        m => dayIndex(m.date, origin), m => m.value, xScale, yScale);
    const ticks = spread(values.map(v => ({
        x: xScale(dayIndex(v.date, origin)), label: v.date.slice(5),
    })), 5);

    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">${detail.tracker.name}
                <span class="trends-unit">${detail.tracker.unit || ''} · 7d mean · target band</span></h3>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                <${YAxis} yMin=${yMin - pad} yMax=${yMax + pad} yScale=${yScale}
                          x0=${M.left} x1=${W - M.right}/>
                ${rects.map((r, i) => html`
                    <rect key=${i} x=${r.x} y=${r.yTop} width=${r.w}
                          height=${r.yBot - r.yTop} class="trends-band"/>
                `)}
                ${meanPts.length > 1 && html`<path d=${linePath(meanPts)} class="trends-line"/>`}
                ${dots.map((p, i) => html`
                    <circle key=${i} cx=${p.x} cy=${p.y} r="2.5" class="trends-dot trends-dot--value"/>
                `)}
                <${XAxis} ticks=${ticks} y=${H - 6}/>
            </svg>
        </section>
    `;
}

function AdherenceCard({ detail }) {
    const weeks = detail.weekly_adherence;
    const W = 360, H = 64;
    const M = { left: 8, right: 8 };
    const xScale = linearScale(-0.5, Math.max(weeks.length - 0.5, 0.5), M.left, W - M.right);
    const cellW = Math.min(24, ((W - M.left - M.right) / Math.max(weeks.length, 1)) * 0.8);
    const cells = ribbonCells(weeks, (i) => xScale(i), cellW);
    const kind = detail.weekly_adherence[0]?.metric_kind || 'adherence';
    const ticks = spread(weeks.map((w, i) => ({
        x: xScale(i), label: w.week_start.slice(5),
    })), 5);

    return html`
        <section class="trends-card">
            <div class="trends-card-head">
                <h3 class="trends-card-title">Weekly ${kind}</h3>
                <div class="trends-streaks">
                    <span title="current streak">🔥 ${detail.streaks.current}</span>
                    <span title="best streak" class="trends-streak-best">best ${detail.streaks.best}</span>
                </div>
            </div>
            <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
                ${cells.map((c, i) => html`
                    <g key=${c.weekStart}>
                        ${c.muted ? html`
                            <rect x=${c.x} y="8" width=${c.w} height="28" rx="3"
                                  class="trends-ribbon-cell trends-ribbon--muted"/>
                        ` : html`
                            <rect x=${c.x} y="8" width=${c.w} height="28" rx="3"
                                  class="trends-ribbon-cell trends-ribbon--missed"/>
                            ${c.partial + c.met > 0 && html`
                                <rect x=${c.x} y=${8 + 28 * (1 - c.met - c.partial)}
                                      width=${c.w} height=${28 * (c.met + c.partial)}
                                      rx="3" class="trends-ribbon-cell trends-ribbon--partial"/>
                            `}
                            ${c.met > 0 && html`
                                <rect x=${c.x} y=${8 + 28 * (1 - c.met)}
                                      width=${c.w} height=${28 * c.met}
                                      rx="3" class="trends-ribbon-cell trends-ribbon--met"/>
                            `}
                        `}
                        ${weeks[i].partial && html`
                            <rect x=${c.x} y="8" width=${c.w} height="28" rx="3"
                                  class="trends-ribbon-cell trends-ribbon--inprogress"/>
                        `}
                    </g>
                `)}
                <${XAxis} ticks=${ticks} y=${H - 8}/>
            </svg>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--met">met</span>
                <span class="trends-legend-item trends-legend--partial">partial</span>
                <span class="trends-legend-item trends-legend--missedleg">missed</span>
                <span class="trends-legend-item trends-legend--mutedleg">paused/off</span>
            </div>
        </section>
    `;
}
