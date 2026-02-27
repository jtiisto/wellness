/**
 * Workout View Component - Main exercise list
 */
import { h } from 'preact';
import htm from 'htm';

import { BlockView } from './BlockView.js';
import { SessionFeedback } from './SessionFeedback.js';
import { getToday } from '../../shared/utils.js';

const html = htm.bind(h);

export function WorkoutView({ date, plan, log, isEditable = true }) {
    if (!plan) {
        return html`
            <div class="empty-state">
                <div class="empty-state-icon">📋</div>
                <p class="empty-state-text">No workout scheduled for this day</p>
            </div>
        `;
    }

    const blocks = plan.blocks || [];

    const today = getToday();
    const isFutureDate = date > today;

    return html`
        <div class="workout-view ${!isEditable ? 'read-only' : ''}">
            ${!isEditable && html`
                <div class="read-only-banner">
                    ${isFutureDate ? 'Viewing scheduled workout (read-only)' : 'Viewing past workout (read-only)'}
                </div>
            `}
            <div class="workout-header">
                <h2 class="workout-day-name">${plan.day_name || 'Workout'}</h2>
                <div class="workout-meta">
                    ${plan.location && html`
                        <span class="workout-meta-item">
                            <span class="icon">📍</span>
                            ${plan.location}
                        </span>
                    `}
                    ${plan.phase && html`
                        <span class="workout-meta-item">
                            <span class="icon">📊</span>
                            ${plan.phase}
                        </span>
                    `}
                </div>
            </div>

            <div class="blocks-list">
                ${blocks.map(block => html`
                    <${BlockView}
                        key=${block.block_index}
                        date=${date}
                        block=${block}
                        log=${log}
                        isEditable=${isEditable}
                    />
                `)}
            </div>

            <${SessionFeedback}
                date=${date}
                feedback=${log?.session_feedback || {}}
                isEditable=${isEditable}
            />
        </div>
    `;
}
