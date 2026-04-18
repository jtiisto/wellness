/**
 * Set Entry Component - For strength exercises
 *
 * Renders a grid: column headers once at the top, then one row per set.
 * Columns are derived from showWeight/showTime so the same component
 * handles weight+reps+RPE (normal lifting), reps+RPE (bodyweight),
 * weight+time (weighted isometric), or time-only.
 */
import { h, Fragment } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';
import { NumericInput } from '../../shared/numeric-input.js';

const html = htm.bind(h);

function buildColumns({ showWeight, showTime }) {
    const cols = [];
    if (showWeight) {
        cols.push({ key: 'weight', label: 'Weight', unit: 'lbs', step: '0.5' });
    }
    if (showTime) {
        cols.push({ key: 'duration_sec', label: 'Time', unit: 'sec' });
    } else {
        cols.push({ key: 'reps', label: 'Reps' });
        cols.push({ key: 'rpe', label: 'RPE', min: 1, max: 10, step: '0.5' });
    }
    return cols;
}

export function SetEntry({ date, exerciseId, targetSets, sets, showTime = false, showWeight = true, isEditable = true }) {
    const handleSetChange = (setIndex, field, value) => {
        if (!isEditable) return;
        const updatedSets = [...sets];

        // Ensure set exists
        while (updatedSets.length <= setIndex) {
            updatedSets.push({ set_num: updatedSets.length + 1 });
        }

        updatedSets[setIndex] = {
            ...updatedSets[setIndex],
            [field]: value
        };

        updateLog(date, exerciseId, { sets: updatedSets });
    };

    const columns = buildColumns({ showWeight, showTime });

    // Grid template: [set-num] [value columns...] [done-check]
    const gridTemplate = ['28px', ...columns.map(() => '1fr'), '36px'].join(' ');

    // Build target-set rows (empty inputs for untouched sets)
    const setRows = [];
    for (let i = 0; i < targetSets; i++) {
        setRows.push({ index: i, data: sets[i] || {} });
    }

    return html`
        <div class="sets-grid" style=${`grid-template-columns: ${gridTemplate};`}>
            <${Fragment}>
                <span class="sets-grid-head">#</span>
                ${columns.map(c => html`
                    <span class="sets-grid-head" key=${'h-' + c.key}>
                        ${c.label}${c.unit ? html` <span class="sets-grid-unit">(${c.unit})</span>` : null}
                    </span>
                `)}
                <span class="sets-grid-head" aria-label="Done">\u2713</span>
            </>
            ${setRows.map(({ index, data }) => html`
                <${Fragment} key=${index}>
                    <span class="sets-grid-num">${index + 1}</span>
                    ${columns.map(c => html`
                        <${NumericInput}
                            key=${'c-' + index + '-' + c.key}
                            class="sets-grid-input"
                            data-col=${c.key}
                            value=${data[c.key]}
                            onValueChange=${(v) => handleSetChange(index, c.key, v)}
                            disabled=${!isEditable}
                            step=${c.step}
                            min=${c.min}
                            max=${c.max}
                        />
                    `)}
                    <div class="sets-grid-check">
                        <input
                            type="checkbox"
                            checked=${!!data.completed}
                            onChange=${(e) => handleSetChange(index, 'completed', e.target.checked)}
                            disabled=${!isEditable}
                        />
                    </div>
                </>
            `)}
        </div>
    `;
}
