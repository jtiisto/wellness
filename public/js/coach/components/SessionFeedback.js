/**
 * Session Feedback Component - Pain/discomfort and general notes
 */
import { h } from 'preact';
import htm from 'htm';

import { updateSessionFeedback } from '../store.js';

const html = htm.bind(h);

export function SessionFeedback({ date, feedback, isEditable = true }) {
    const handleChange = (field, value) => {
        if (!isEditable) return;
        updateSessionFeedback(date, { [field]: value });
    };

    return html`
        <div class="session-feedback">
            <h3 class="feedback-title">Session Feedback</h3>

            <div class="feedback-field">
                <label class="feedback-label">Pain / Discomfort</label>
                <textarea
                    class="feedback-textarea"
                    placeholder=${isEditable ? "Note any pain, discomfort, or issues..." : "No notes recorded"}
                    value=${feedback.pain_discomfort || ''}
                    onInput=${(e) => handleChange('pain_discomfort', e.target.value)}
                    disabled=${!isEditable}
                />
            </div>

            <div class="feedback-field">
                <label class="feedback-label">General Notes</label>
                <textarea
                    class="feedback-textarea"
                    placeholder=${isEditable ? "How did the session feel overall?" : "No notes recorded"}
                    value=${feedback.general_notes || ''}
                    onInput=${(e) => handleChange('general_notes', e.target.value)}
                    disabled=${!isEditable}
                />
            </div>
        </div>
    `;
}
