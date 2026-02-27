/**
 * Set Entry Component - For strength exercises
 */
import { h } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';

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
                        <input
                            type="number"
                            class="set-input weight"
                            placeholder="lbs"
                            value=${data.weight ?? ''}
                            onInput=${(e) => handleSetChange(index, 'weight', e.target.value ? Number(e.target.value) : null)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">lbs</span>
                    ` : null}

                    ${showTime ? html`
                        <input
                            type="number"
                            class="set-input"
                            placeholder="sec"
                            value=${data.duration_sec ?? ''}
                            onInput=${(e) => handleSetChange(index, 'duration_sec', e.target.value ? Number(e.target.value) : null)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">sec</span>
                    ` : html`
                        <input
                            type="number"
                            class="set-input reps"
                            placeholder="reps"
                            value=${data.reps ?? ''}
                            onInput=${(e) => handleSetChange(index, 'reps', e.target.value ? Number(e.target.value) : null)}
                            disabled=${!isEditable}
                        />
                        <span class="set-label">reps</span>

                        <input
                            type="number"
                            class="set-input rpe"
                            placeholder="RPE"
                            min="1"
                            max="10"
                            step="0.5"
                            value=${data.rpe ?? ''}
                            onInput=${(e) => handleSetChange(index, 'rpe', e.target.value ? Number(e.target.value) : null)}
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
