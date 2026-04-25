/**
 * Block View Component - Renders a block of exercises with header
 */
import { h } from 'preact';
import htm from 'htm';
import { ExerciseItem } from './ExerciseItem.js';
import { SupersetGroup } from './SupersetGroup.js';
import { groupExercises } from '../utils.js';

const html = htm.bind(h);

function renderExercise(date, exercise, log, isEditable) {
    return html`
        <${ExerciseItem}
            key=${exercise.id}
            date=${date}
            exercise=${exercise}
            logData=${log?.[exercise.id]}
            isEditable=${isEditable}
        />
    `;
}

export function BlockView({ date, block, log, isEditable = true }) {
    const { block_type, title, rest_guidance, rounds, exercises = [] } = block;
    const items = groupExercises(exercises);

    return html`
        <div class="exercise-block" data-block-type=${block_type}>
            <div class="block-header">
                <span class="block-title">${title || block_type}</span>
                ${rounds && html`
                    <span class="block-rounds">${rounds} rounds</span>
                `}
                ${rest_guidance && html`
                    <span class="rest-guidance">${rest_guidance}</span>
                `}
            </div>
            <div class="block-exercises">
                ${items.map((item, i) => {
                    if (item.kind === 'group') {
                        return html`
                            <${SupersetGroup}
                                key=${`group-${item.label}-${i}`}
                                label=${item.label}
                            >
                                ${item.exercises.map(ex => renderExercise(date, ex, log, isEditable))}
                            </${SupersetGroup}>
                        `;
                    }
                    return renderExercise(date, item.exercise, log, isEditable);
                })}
            </div>
        </div>
    `;
}
