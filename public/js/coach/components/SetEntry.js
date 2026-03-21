/**
 * Set Entry Component - For strength exercises
 */
import { h } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';
import { NumericInput } from '../../shared/numeric-input.js';

const html = htm.bind(h);

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

    // Create array for target number of sets
    const setRows = [];
    for (let i = 0; i < targetSets; i++) {
        const setData = sets[i] || {};
        setRows.push({ index: i, data: setData });
    }

    return html`
        <div class="sets-container">
            ${setRows.map(({ index, data }) => html`
                <div class="set-row" key=${index}>
                    <span class="set-num">${index + 1}</span>

                    ${showWeight ? html`
                        <${NumericInput}
                            class="set-input weight"
                            placeholder="lbs"
                            step="0.5"
                            value=${data.weight}
                            onValueChange=${(v) => handleSetChange(index, 'weight', v)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">lbs</span>
                    ` : null}

                    ${showTime ? html`
                        <${NumericInput}
                            class="set-input"
                            placeholder="sec"
                            value=${data.duration_sec}
                            onValueChange=${(v) => handleSetChange(index, 'duration_sec', v)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">sec</span>
                    ` : html`
                        <${NumericInput}
                            class="set-input reps"
                            placeholder="reps"
                            value=${data.reps}
                            onValueChange=${(v) => handleSetChange(index, 'reps', v)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">reps</span>

                        <${NumericInput}
                            class="set-input rpe"
                            placeholder="RPE"
                            min="1"
                            max="10"
                            step="0.5"
                            value=${data.rpe}
                            onValueChange=${(v) => handleSetChange(index, 'rpe', v)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">RPE</span>
                    `}

                    <div class="set-check">
                        <input
                            type="checkbox"
                            checked=${!!data.completed}
                            onChange=${(e) => handleSetChange(index, 'completed', e.target.checked)}
                            disabled=${!isEditable}
                        />
                    </div>
                </div>
            `)}
        </div>
    `;
}
