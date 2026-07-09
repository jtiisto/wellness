/**
 * Small shared chart building blocks: RangeSelector, StaleBadge, axis ticks.
 * Chart geometry comes from ../chart-logic.js; these are thin htm/preact
 * consumers styled by styles.css (.trends section).
 */
import { h } from 'preact';
import htm from 'htm';

import { range, setRange, staleness } from '../store.js';
import { niceTicks } from '../chart-logic.js';

const html = htm.bind(h);

export const RANGES = [
    { id: '4w', label: '4w', days: 28 },
    { id: '12w', label: '12w', days: 84 },
    { id: '6m', label: '6m', days: 182 },
    { id: 'all', label: 'All', days: null },
];

/** start date param (YYYY-MM-DD) for the active range, or null for All. */
export function rangeStart(rangeId, todayStr) {
    const spec = RANGES.find(r => r.id === rangeId);
    if (!spec || spec.days == null) return null;
    const [y, m, d] = todayStr.split('-').map(Number);
    const t = new Date(y, m - 1, d);
    t.setDate(t.getDate() - spec.days);
    const pad = (n) => String(n).padStart(2, '0');
    return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`;
}

export function RangeSelector() {
    return html`
        <div class="trends-range" role="group" aria-label="Time range">
            ${RANGES.map(r => html`
                <button
                    key=${r.id}
                    class="trends-range-btn ${range.value === r.id ? 'active' : ''}"
                    onClick=${() => setRange(r.id)}
                >${r.label}</button>
            `)}
        </div>
    `;
}

/** "cached · Xh ago" badge, shown only when the given cache keys are serving
 *  stale (offline-fallback) data. */
export function StaleBadge({ cacheKeys }) {
    const stamps = cacheKeys
        .map(k => staleness.value[k])
        .filter(Boolean);
    if (!stamps.length) return null;
    const oldest = Math.min(...stamps);
    const mins = Math.max(1, Math.round((Date.now() - oldest) / 60000));
    const age = mins < 60 ? `${mins}m` : `${Math.round(mins / 60)}h`;
    return html`<span class="trends-stale-badge" title="Offline — showing cached data">
        cached · ${age} ago
    </span>`;
}

/** Horizontal grid lines + right-aligned y tick labels for a plot area.
 *  Ticks whose FORMATTED label repeats the previous one drop the label (an
 *  integer format over a small range yielded "0, 1, 1" — review F6). */
export function YAxis({ yMin, yMax, yScale, x0, x1, targetCount = 4, format }) {
    const fmt = format || ((v) => String(v));
    let prevLabel = null;
    return html`
        <g class="trends-axis">
            ${niceTicks(yMin, yMax, targetCount).map(t => {
                const label = String(fmt(t));
                const dup = label === prevLabel;
                prevLabel = label;
                return html`
                    <g key=${t}>
                        <line x1=${x0} y1=${yScale(t)} x2=${x1} y2=${yScale(t)} class="trends-gridline"/>
                        ${!dup && html`<text x=${x0 - 4} y=${yScale(t) + 3} text-anchor="end" class="trends-tick">${label}</text>`}
                    </g>
                `;
            })}
        </g>
    `;
}

/** Sparse x tick labels under a plot. `ticks`: [{x (scaled), label}]. */
export function XAxis({ ticks, y }) {
    return html`
        <g class="trends-axis">
            ${ticks.map(t => html`
                <text key=${t.label + t.x} x=${t.x} y=${y} text-anchor="middle" class="trends-tick">${t.label}</text>
            `)}
        </g>
    `;
}

/** Pick ≤n evenly-spread items from a list (first/last always included). */
export function spread(items, n) {
    if (items.length <= n) return items;
    const out = [];
    for (let i = 0; i < n; i++) {
        out.push(items[Math.round(i * (items.length - 1) / (n - 1))]);
    }
    return [...new Set(out)];
}
