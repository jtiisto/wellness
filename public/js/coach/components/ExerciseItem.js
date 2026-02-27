/**
 * Exercise Item Component - Accordion-style exercise entry
 */
import { h } from 'preact';
import { useState } from 'preact/hooks';
import htm from 'htm';

import { updateLog } from '../store.js';
import { formatTarget, isExerciseCompleted } from '../utils.js';
import { SetEntry } from './SetEntry.js';
import { CardioEntry } from './CardioEntry.js';
import { ChecklistEntry } from './ChecklistEntry.js';

const html = htm.bind(h);

function parseName(name) {
    const pills = [];
    const base = name.replace(/\s*[\(\[](.*?)[\)\]]/g, (_, inner) => {
        pills.push(inner);
        return '';
    }).trim();
    return { base, pills };
}

export function ExerciseItem({ date, exercise, logData, isEditable = true }) {
    const [expanded, setExpanded] = useState(false);

    const completed = isExerciseCompleted(exercise, logData);
    const target = formatTarget(exercise);

    const handleCompletedChange = (e) => {
        if (!isEditable) return;
        updateLog(date, exercise.id, { completed: e.target.checked });
    };

    const handleNoteChange = (e) => {
        if (!isEditable) return;
        updateLog(date, exercise.id, { user_note: e.target.value });
    };

    const renderInputs = () => {
        switch (exercise.type) {
            case 'checklist':
                return html`
                    <${ChecklistEntry}
                        date=${date}
                        exerciseId=${exercise.id}
                        items=${exercise.items || []}
                        completedItems=${logData?.completed_items || []}
                        isEditable=${isEditable}
                    />
                `;
            case 'strength':
            case 'circuit':
                return html`
                    <${SetEntry}
                        date=${date}
                        exerciseId=${exercise.id}
                        targetSets=${exercise.target_sets || 3}
                        sets=${logData?.sets || []}
                        showTime=${!!exercise.show_time}
                        showWeight=${!exercise.hide_weight}
                        isEditable=${isEditable}
                    />
                `;
            case 'duration':
                return html`
                    <${CardioEntry}
                        date=${date}
                        exerciseId=${exercise.id}
                        targetMin=${exercise.target_duration_min}
                        data=${logData || {}}
                        isEditable=${isEditable}
                    />
                `;
            case 'weighted_time':
                return html`
                    <${SetEntry}
                        date=${date}
                        exerciseId=${exercise.id}
                        targetSets=${exercise.target_sets || 1}
                        sets=${logData?.sets || []}
                        showTime=${true}
                        isEditable=${isEditable}
                    />
                `;
            default:
                return null;
        }
    };

    return html`
        <div class="exercise-item ${expanded ? 'expanded' : ''} ${completed ? 'completed' : ''}">
            <div class="exercise-header" onClick=${() => setExpanded(!expanded)}>
                <div class="exercise-checkbox" onClick=${(e) => e.stopPropagation()}>
                    <input
                        type="checkbox"
                        checked=${completed}
                        onChange=${handleCompletedChange}
                        disabled=${!isEditable}
                    />
                </div>
                <span class="exercise-name">${parseName(exercise.name).base}</span>
                ${parseName(exercise.name).pills.map(p => html`
                    <span class="exercise-pill">${p}</span>
                `)}
                <span class="exercise-target">${target}</span>
                <span class="exercise-chevron">${expanded ? '▲' : '▼'}</span>
            </div>

            ${expanded && html`
                <div class="exercise-body">
                    ${exercise.guidance_note && html`
                        <div class="guidance-note">${exercise.guidance_note}</div>
                    `}

                    ${renderInputs()}

                    <div class="user-note">
                        <textarea
                            placeholder=${isEditable ? "Add notes..." : "No notes"}
                            value=${logData?.user_note || ''}
                            onInput=${handleNoteChange}
                            disabled=${!isEditable}
                        />
                    </div>
                </div>
            `}
        </div>
    `;
}
