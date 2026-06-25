/**
 * Exercise Item Component - Accordion-style exercise entry
 */
import { h } from 'preact';
import { useState } from 'preact/hooks';
import htm from 'htm';

import { updateLog, workoutPlans, workoutLogs } from '../store.js';
import { formatTarget, getExerciseProgress, isExerciseCompleted, buildPrescription } from '../utils.js';
import { findLastPerformance } from '../last-performance.js';
import { SetEntry } from './SetEntry.js';
import { CardioEntry } from './CardioEntry.js';
import { ChecklistEntry } from './ChecklistEntry.js';

const html = htm.bind(h);

// Small dumbbell glyph for the load token — load carries no text label (it's
// self-describing: "70%", "24kg"), so the icon is its visual cue. currentColor
// + the module accent; no external asset.
const DUMBBELL = html`
    <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
         stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <line x1="3.5" y1="8.5" x2="3.5" y2="15.5" />
        <line x1="6.5" y1="6" x2="6.5" y2="18" />
        <line x1="17.5" y1="6" x2="17.5" y2="18" />
        <line x1="20.5" y1="8.5" x2="20.5" y2="15.5" />
        <line x1="6.5" y1="12" x2="17.5" y2="12" />
    </svg>
`;

// Render one prescription token. `load` gets the dumbbell icon; `rpe`/`tempo`
// get a small teal label + value.
function renderRxToken(token) {
    if (token.kind === 'load') {
        return html`<span class="rx-token rx-load">${DUMBBELL}${token.value}</span>`;
    }
    const label = token.kind === 'rpe' ? 'RPE' : 'Tempo';
    return html`<span class="rx-token"><span class="rx-label">${label}</span> ${token.value}</span>`;
}

function parseName(name) {
    const pills = [];
    const base = name.replace(/\s*[\(\[](.*?)[\)\]]/g, (_, inner) => {
        pills.push(inner);
        return '';
    }).trim();
    return { base, pills };
}

export function ExerciseItem({ date, exercise, logData, block, isEditable = true }) {
    const [expanded, setExpanded] = useState(false);

    const completed = isExerciseCompleted(exercise, logData);
    const target = formatTarget(exercise, block);
    const progress = getExerciseProgress(exercise, logData);
    const parsed = parseName(exercise.name);
    const prescription = buildPrescription(exercise);

    const handleNoteChange = (e) => {
        if (!isEditable) return;
        updateLog(date, exercise.id, { user_note: e.target.value });
    };

    const renderInputs = () => {
        // Previous time this exact exercise (by slug) was performed — surfaced as
        // faint placeholders + a date hint in the set grid. Computed lazily (only
        // when expanded, since renderInputs only runs then).
        const lastPerformance = exercise.canonical_slug
            ? findLastPerformance(exercise.canonical_slug, date, workoutPlans.value, workoutLogs.value)
            : null;
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
                        lastPerformance=${lastPerformance}
                        isEditable=${isEditable}
                    />
                `;
            case 'duration':
            case 'interval':
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
                        lastPerformance=${lastPerformance}
                        isEditable=${isEditable}
                    />
                `;
            default:
                return null;
        }
    };

    const handleHeaderKeyDown = (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            setExpanded(!expanded);
        }
    };

    return html`
        <div
            class="exercise-item ${expanded ? 'expanded' : ''} ${completed ? 'completed' : ''}"
        >
            <div
                class="exercise-header"
                onClick=${() => setExpanded(!expanded)}
                onKeyDown=${handleHeaderKeyDown}
                role="button"
                tabIndex="0"
                aria-expanded=${expanded}
            >
                <span class="exercise-name">${parsed.base}</span>
                ${parsed.pills.map(p => html`
                    <span class="exercise-pill">${p}</span>
                `)}
                <span class="exercise-target">${target}</span>
                ${progress && html`
                    <span
                        class="exercise-progress ${progress.complete ? 'exercise-progress--complete' : ''}"
                        aria-label="Progress: ${progress.display}"
                    >${progress.display}</span>
                `}
                <span class="exercise-chevron">▼</span>
            </div>

            ${expanded && html`
                <div class="exercise-body">
                    ${exercise.guidance_note && html`
                        <div class="guidance-note">${exercise.guidance_note}</div>
                    `}

                    ${prescription.length > 0 && html`
                        <div class="exercise-prescription">
                            ${prescription.map((token, i) => html`
                                ${i > 0 && html`<span class="rx-sep">·</span>`}
                                ${renderRxToken(token)}
                            `)}
                        </div>
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
