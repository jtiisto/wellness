/**
 * LineChart — dots + thin line, built for sparse series (15-30 points over
 * months). Optional secondary series on a fixed right-hand scale (the RPE
 * overlay: same weight at falling RPE is progress the weight line hides).
 *
 * Props:
 *   points:    [{x, y, muted?, label?}]  x = day index (chart-logic.dayIndex)
 *   points2:   optional second primary series (same y scale), e.g. e1RM
 *   secondary: optional [{x, y}] on secondaryDomain (right scale, faint)
 *   secondaryDomain: [lo, hi] fixed domain for the secondary (default [5,10])
 *   xTicks:    [{x, label}] (day-index x; sparse — see primitives.spread)
 *   height:    logical height (default 220); width is 360 viewBox units
 *   yFormat:   tick formatter
 * Muted points (off-plan sessions) render as outlined dots.
 */
import { h } from 'preact';
import htm from 'htm';

import { linearScale, linePath, seriesToPoints, niceTicks } from '../chart-logic.js';
import { YAxis, XAxis } from './primitives.js';

const html = htm.bind(h);

const W = 360;
const M = { top: 10, right: 34, bottom: 22, left: 40 };

export function LineChart({
    points = [], points2 = null, secondary = null,
    secondaryDomain = [5, 10], xTicks = [], height = 220, yFormat,
}) {
    const all = [...points, ...(points2 || [])];
    if (!all.length) {
        return html`<div class="trends-chart-empty">No data in range</div>`;
    }

    const H = height;
    const xs = all.map(p => p.x);
    const ys = all.map(p => p.y);
    const xMin = Math.min(...xs, ...(xTicks.length ? [xTicks[0].x] : []));
    const xMax = Math.max(...xs, ...(xTicks.length ? [xTicks[xTicks.length - 1].x] : []));
    const yMin = Math.min(...ys);
    const yMax = Math.max(...ys);
    const pad = (yMax - yMin) * 0.08 || yMax * 0.05 || 1;

    const xScale = linearScale(xMin, xMax, M.left, W - M.right);
    const yScale = linearScale(yMin - pad, yMax + pad, H - M.bottom, M.top);

    const toPlot = (series) =>
        seriesToPoints(series, p => p.x, p => p.y, xScale, yScale)
            .map((pt, i) => ({ ...pt, muted: series[i]?.muted }));

    const p1 = toPlot(points);
    const p2 = points2 ? toPlot(points2) : [];
    const sec = secondary
        ? seriesToPoints(secondary, p => p.x, p => p.y, xScale,
            linearScale(secondaryDomain[0], secondaryDomain[1], H - M.bottom, M.top))
        : [];

    return html`
        <svg viewBox="0 0 ${W} ${H}" class="trends-chart" role="img">
            <${YAxis} yMin=${yMin - pad} yMax=${yMax + pad} yScale=${yScale}
                      x0=${M.left} x1=${W - M.right} format=${yFormat}/>
            ${sec.length > 1 && html`
                <path d=${linePath(sec)} class="trends-line trends-line--secondary"/>
            `}
            ${p2.length > 1 && html`<path d=${linePath(p2)} class="trends-line trends-line--alt"/>`}
            ${p1.length > 1 && html`<path d=${linePath(p1)} class="trends-line"/>`}
            ${p2.map(pt => html`
                <circle key=${'b' + pt.x} cx=${pt.x} cy=${pt.y} r="2.5"
                        class="trends-dot trends-dot--alt ${pt.muted ? 'trends-dot--muted' : ''}"/>
            `)}
            ${p1.map(pt => html`
                <circle key=${'a' + pt.x} cx=${pt.x} cy=${pt.y} r="3"
                        class="trends-dot ${pt.muted ? 'trends-dot--muted' : ''}"/>
            `)}
            ${secondaryDomain && sec.length > 0 && html`
                <g class="trends-axis">
                    ${niceTicks(secondaryDomain[0], secondaryDomain[1], 3).map(t => html`
                        <text key=${t} x=${W - M.right + 4}
                              y=${linearScale(secondaryDomain[0], secondaryDomain[1], H - M.bottom, M.top)(t) + 3}
                              class="trends-tick trends-tick--secondary">${t}</text>
                    `)}
                </g>
            `}
            <${XAxis} ticks=${xTicks.map(t => ({ x: xScale(t.x), label: t.label }))} y=${H - 6}/>
        </svg>
    `;
}
