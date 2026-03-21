/**
 * Cardio Entry Component - For duration-based exercises
 */
import { h } from 'preact';
import htm from 'htm';

import { updateLog } from '../store.js';
import { NumericInput } from '../../shared/numeric-input.js';

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
                <${NumericInput}
                    placeholder=${targetMin || ''}
                    value=${data.duration_min}
                    onValueChange=${(v) => handleChange('duration_min', v)}
                    disabled=${!isEditable}
                />
            </div>

            <div class="cardio-field">
                <label>Avg HR</label>
                <${NumericInput}
                    placeholder="bpm"
                    value=${data.avg_hr}
                    onValueChange=${(v) => handleChange('avg_hr', v)}
                    disabled=${!isEditable}
                />
            </div>

            <div class="cardio-field">
                <label>Max HR</label>
                <${NumericInput}
                    placeholder="bpm"
                    value=${data.max_hr}
                    onValueChange=${(v) => handleChange('max_hr', v)}
                    disabled=${!isEditable}
                />
            </div>
        </div>
    `;
}
