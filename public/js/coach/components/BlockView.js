/**
 * Block View Component - Renders a block of exercises with header
 */
import { h } from 'preact';
import htm from 'htm';
import { ExerciseItem } from './ExerciseItem.js';

const html = htm.bind(h);

export function BlockView({ date, block, log, isEditable = true }) {
    const { block_type, title, rest_guidance, exercises = [] } = block;

    return html`
        <div class="exercise-block" data-block-type=${block_type}>
            <div class="block-header">
                <span class="block-title">${title || block_type}</span>
                ${rest_guidance && html`
                    <span class="rest-guidance">${rest_guidance}</span>
                `}
            </div>
            <div class="block-exercises">
                ${exercises.map(exercise => html`
                    <${ExerciseItem}
                        key=${exercise.id}
                        date=${date}
                        exercise=${exercise}
                        logData=${log?.[exercise.id]}
                        isEditable=${isEditable}
                    />
                `)}
            </div>
        </div>
    `;
}
