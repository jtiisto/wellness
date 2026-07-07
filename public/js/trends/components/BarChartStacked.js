/**
 * BarChartStacked — weekly stacked bars (tonnage by exercise, Zone 2
 * planned-vs-extra). The current in-progress week renders hatched-light via
 * the `partial` flag so it isn't read against complete weeks.
 *
 * Props:
 *   weeks:  [{week_start, partial, values: {key: number}}]
 *   keys:   stacking order bottom-up, [{key, cssClass, label}]
 *   height: logical height (default 180)
 *   yFormat
 */
import { h } from 'preact';
import htm from 'htm';

import { linearScale, stackedBarLayout } from '../chart-logic.js';
import { YAxis, XAxis, spread } from './primitives.js';

const html = htm.bind(h);

const W = 360;
const M = { top: 10, right: 8, bottom: 22, left: 44 };

export function BarChartStacked({ weeks = [], keys = [], height = 180, yFormat }) {
    if (!weeks.length) {
        return html`<div class="trends-chart-empty">No data in range</div>`;
    }
    const H = height;
    const totals = weeks.map(w =>
        keys.reduce((acc, k) => acc + (w.values[k.key] || 0), 0));
    const yMax = Math.max(...totals, 1);

    const xScale = linearScale(-0.5, weeks.length - 0.5, M.left, W - M.right);
    const yScale = linearScale(0, yMax * 1.05, H - M.bottom, M.top);
    const barWidth = Math.min(26, ((W - M.left - M.right) / weeks.length) * 0.72);

    const layout = stackedBarLayout(weeks, keys.map(k => k.key), xScale, yScale, barWidth);
    const cssFor = Object.fromEntries(keys.map(k => [k.key, k.cssClass]));

    const tickWeeks = spread(weeks.map((w, i) => ({ i, label: w.week_start.slice(5) })), 5);

    return html`
        <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
            <${YAxis} yMin=${0} yMax=${yMax * 1.05} yScale=${yScale}
                      x0=${M.left} x1=${W - M.right} format=${yFormat}/>
            ${layout.map((bar, i) => html`
                <g key=${bar.weekStart} class=${weeks[i].partial ? 'trends-bar--partial' : ''}>
                    ${bar.segs.map(seg => html`
                        <rect key=${seg.key} x=${seg.x} y=${seg.y} width=${seg.w} height=${seg.h}
                              rx="1.5" class="trends-bar ${cssFor[seg.key] || ''}"/>
                    `)}
                </g>
            `)}
            <${XAxis} ticks=${tickWeeks.map(t => ({ x: xScale(t.i), label: t.label }))} y=${H - 6}/>
        </svg>
    `;
}
