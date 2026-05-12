/**
 * Block View Component - Renders a block of exercises with header
 */
import { h } from 'preact';
import htm from 'htm';
import { ExerciseItem } from './ExerciseItem.js';
import { SupersetGroup } from './SupersetGroup.js';
import { groupExercises, formatInterval } from '../utils.js';

const html = htm.bind(h);

function renderExercise(date, exercise, log, isEditable, block) {
    return html`
        <${ExerciseItem}
            key=${exercise.id}
            date=${date}
            exercise=${exercise}
            logData=${log?.[exercise.id]}
            block=${block}
            isEditable=${isEditable}
        />
    `;
}

export function BlockView({ date, block, log, isEditable = true }) {
    const { block_type, title, rest_guidance, exercises = [] } = block;
    const items = groupExercises(exercises);
    // Circuit/interval timing is canonical at the block level.
    const timing = formatInterval(block);

    return html`
        <div class="exercise-block" data-block-type=${block_type}>
            <div class="block-header">
                <span class="block-title">${title || block_type}</span>
                ${timing && html`
                    <span class="block-rounds">${timing}</span>
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
                                ${item.exercises.map(ex => renderExercise(date, ex, log, isEditable, block))}
                            </${SupersetGroup}>
                        `;
                    }
                    return renderExercise(date, item.exercise, log, isEditable, block);
                })}
            </div>
        </div>
    `;
}
