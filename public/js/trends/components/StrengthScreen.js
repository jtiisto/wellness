/**
 * Strength screen: exercise picker (slug exposed — adjacent near-duplicates
 * are meant to be spottable), top-set/e1RM progression with optional RPE
 * overlay, weekly tonnage stacked by exercise, PR board.
 */
import { h } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { fetchCached, range } from '../store.js';
import { dayIndex } from '../chart-logic.js';
import { LineChart } from './LineChart.js';
import { BarChartStacked } from './BarChartStacked.js';
import { RangeSelector, StaleBadge, rangeStart, spread } from './primitives.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

const VOLUME_STACK_SLUGS = 3;  // top N slugs stacked; the rest fold into "other"

function rangeQuery() {
    const today = getToday();
    const start = rangeStart(range.value, today);
    return start ? `?start=${start}&end=${today}` : `?end=${today}`;
}

export function StrengthScreen() {
    const [exercises, setExercises] = useState(null);
    const [selected, setSelected] = useState(localStorage.getItem('trends_exercise') || null);
    const [series, setSeries] = useState(null);
    const [volume, setVolume] = useState(null);
    const [showRpe, setShowRpe] = useState(true);
    const [error, setError] = useState(null);
    const [, forceRender] = useState(0);

    // Re-fetch on range changes (signal → local rerender + fetches below).
    useEffect(() => {
        const dispose = effect(() => { range.value; forceRender(n => n + 1); });
        return dispose;
    }, []);

    const q = rangeQuery();

    useEffect(() => {
        let cancelled = false;
        setError(null);
        fetchCached(`strength/exercises:${range.value}`, `/strength/exercises${q}`)
            .then(data => {
                if (cancelled) return;
                setExercises(data.exercises);
                if (!data.exercises.length) return;
                if (!data.exercises.some(e => e.slug === selected)) {
                    setSelected(data.exercises[0].slug);
                }
            })
            .catch(err => !cancelled && setError(err.message));
        fetchCached(`volume:${range.value}`, `/strength/volume${q}`)
            .then(data => !cancelled && setVolume(data.weeks))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [q]);

    useEffect(() => {
        if (!selected) return;
        let cancelled = false;
        localStorage.setItem('trends_exercise', selected);
        fetchCached(`strength/${selected}:${range.value}`,
                    `/strength/exercise/${encodeURIComponent(selected)}${q}`)
            .then(data => !cancelled && setSeries(data))
            .catch(err => !cancelled && setError(err.message));
        return () => { cancelled = true; };
    }, [selected, q]);

    return html`
        <div class="trends-screen">
            <div class="trends-toolbar">
                <${RangeSelector}/>
                <${StaleBadge} cacheKeys=${[
                    `strength/exercises:${range.value}`,
                    `volume:${range.value}`,
                    selected ? `strength/${selected}:${range.value}` : '',
                ]}/>
            </div>
            ${error && html`<div class="trends-error">${error}</div>`}

            ${exercises && exercises.length === 0 && html`
                <div class="trends-chart-empty">No logged sets yet</div>
            `}
            ${exercises && exercises.length > 0 && html`
                <select class="form-select trends-picker" value=${selected}
                        onChange=${(e) => setSelected(e.target.value)}>
                    ${exercises.map(e => html`
                        <option key=${e.slug} value=${e.slug}>${e.name} (${e.slug})</option>
                    `)}
                </select>
            `}

            ${series && html`<${ProgressionCard} series=${series} showRpe=${showRpe}
                                                 onToggleRpe=${() => setShowRpe(v => !v)}/>`}
            ${volume && html`<${VolumeCard} weeks=${volume}/>`}
            ${exercises && exercises.length > 0 && html`<${PRBoard} exercises=${exercises}/>`}
        </div>
    `;
}

function ProgressionCard({ series, showRpe, onToggleRpe }) {
    const sessions = series.sessions;
    if (!sessions.length) {
        return html`<section class="trends-card">
            <h3 class="trends-card-title">${series.exercise.name}</h3>
            <div class="trends-chart-empty">No sessions in range</div>
        </section>`;
    }
    const origin = sessions[0].date;
    const pts = sessions.map(s => ({
        x: dayIndex(s.date, origin), y: s.top_set.weight, muted: s.off_plan,
    }));
    const e1rm = sessions.map(s => ({
        x: dayIndex(s.date, origin), y: s.e1rm, muted: s.off_plan,
    }));
    const rpe = showRpe
        ? sessions.filter(s => s.top_set_rpe != null)
                  .map(s => ({ x: dayIndex(s.date, origin), y: s.top_set_rpe }))
        : null;
    const ticks = spread(sessions.map(s => ({
        x: dayIndex(s.date, origin), label: s.date.slice(5),
    })), 5);

    return html`
        <section class="trends-card">
            <div class="trends-card-head">
                <h3 class="trends-card-title">${series.exercise.name}
                    <span class="trends-unit">top set · e1RM (${series.unit})</span></h3>
                <button class="trends-toggle ${showRpe ? 'active' : ''}"
                        onClick=${onToggleRpe}>RPE</button>
            </div>
            <${LineChart} points=${pts} points2=${e1rm}
                          secondary=${rpe} xTicks=${ticks}/>
            <div class="trends-legend">
                <span class="trends-legend-item trends-legend--primary">top set</span>
                <span class="trends-legend-item trends-legend--alt">e1RM</span>
                ${showRpe && html`<span class="trends-legend-item trends-legend--secondary">RPE</span>`}
                <span class="trends-legend-item trends-legend--muted">○ off-plan</span>
            </div>
        </section>
    `;
}

function VolumeCard({ weeks }) {
    // Top N slugs by total tonnage in range; the rest fold into "other".
    const totals = {};
    for (const w of weeks) {
        for (const e of w.by_exercise) {
            totals[e.slug] = (totals[e.slug] || 0) + e.tonnage_kg;
        }
    }
    const top = Object.entries(totals).sort((a, b) => b[1] - a[1])
        .slice(0, VOLUME_STACK_SLUGS).map(([slug]) => slug);
    const stackWeeks = weeks.map(w => {
        const values = { other: 0 };
        for (const e of w.by_exercise) {
            if (top.includes(e.slug)) values[e.slug] = e.tonnage_kg;
            else values.other += e.tonnage_kg;
        }
        return { week_start: w.week_start, partial: w.partial, values };
    });
    const keys = [
        ...top.map((slug, i) => ({ key: slug, cssClass: `trends-stack-${i}` })),
        { key: 'other', cssClass: 'trends-stack-other' },
    ];
    const hasOther = stackWeeks.some(w => w.values.other > 0);
    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Weekly volume <span class="trends-unit">kg</span></h3>
            <${BarChartStacked} weeks=${stackWeeks} keys=${keys}
                                yFormat=${(v) => v >= 1000 ? `${Math.round(v / 100) / 10}t` : v}/>
            <div class="trends-legend">
                ${top.map((slug, i) => html`
                    <span key=${slug} class="trends-legend-item trends-legend--stack${i}">${slug}</span>
                `)}
                ${hasOther && html`<span class="trends-legend-item trends-legend--stackother">other</span>`}
            </div>
        </section>
    `;
}

function PRBoard({ exercises }) {
    return html`
        <section class="trends-card">
            <h3 class="trends-card-title">Records</h3>
            <div class="trends-pr-list">
                ${exercises.map(e => html`
                    <div class="trends-pr-row" key=${e.slug}>
                        <div class="trends-pr-name">${e.name}
                            <span class="trends-pr-slug">${e.slug}</span></div>
                        <div class="trends-pr-vals">
                            <span title="best e1RM">${e.all_time.best_e1rm.value} ${e.unit}</span>
                            <span class="trends-pr-detail">
                                ${e.all_time.best_weight.weight}×${e.all_time.best_weight.reps}
                                · ${e.all_time.best_e1rm.date}
                            </span>
                        </div>
                    </div>
                `)}
            </div>
        </section>
    `;
}
