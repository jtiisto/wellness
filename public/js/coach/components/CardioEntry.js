/**
 * Cardio Entry Component - For duration-based exercises
 */
import { h } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';

const html = htm.bind(h);

export function CardioEntry({ date, exerciseId, targetMin, data, isEditable = true }) {
    const handleChange = (field, value) => {
        if (!isEditable) return;
        updateLog(date, exerciseId, { [field]: value });
    };

    return html`
        <div class="cardio-entry">
            <div class="cardio-field">
                <label>Duration (min)</label>
                <input
                    type="number"
                    placeholder=${targetMin || ''}
                    value=${data.duration_min ?? ''}
                    onInput=${(e) => handleChange('duration_min', e.target.value ? Number(e.target.value) : null)}
                    disabled=${!isEditable}
                />
            </div>

            <div class="cardio-field">
                <label>Avg HR</label>
                <input
                    type="number"
                    placeholder="bpm"
                    value=${data.avg_hr ?? ''}
                    onInput=${(e) => handleChange('avg_hr', e.target.value ? Number(e.target.value) : null)}
                    disabled=${!isEditable}
                />
            </div>

            <div class="cardio-field">
                <label>Max HR</label>
                <input
                    type="number"
                    placeholder="bpm"
                    value=${data.max_hr ?? ''}
                    onInput=${(e) => handleChange('max_hr', e.target.value ? Number(e.target.value) : null)}
                    disabled=${!isEditable}
                />
            </div>
        </div>
    `;
}
