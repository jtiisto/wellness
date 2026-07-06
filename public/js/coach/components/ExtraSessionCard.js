/**
 * Extra Session Card — ad-hoc off-plan Zone 2 on a rest day.
 *
 * Draft-then-adopt: before the first Save the fields live in component state
 * only (nothing in the log store, nothing syncs). Save requires a duration —
 * a duration-less cardio entry carries no uploadable content, so its dirty
 * date would be dropped as unsatisfiable — and commits via one updateLog()
 * call. From then on the entry renders through the regular CardioEntry and
 * auto-saves edits like planned cardio; Delete tombstones it for the server
 * (see deleteLogEntry / withEntryDeleted).
 */
import { h } from 'preact';
import { useState } from 'preact/hooks';
import htm from 'htm';

import { updateLog, deleteLogEntry } from '../store.js';
import { CardioEntry } from './CardioEntry.js';
import { NumericInput } from '../../shared/numeric-input.js';
import { EXTRA_SESSION_KEY, EXTRA_SESSION_TITLE } from '../utils.js';
import { isDeletedEntry } from '../sync-logic.js';

const html = htm.bind(h);

const DRAFT_FIELDS = [
    { field: 'duration_min', label: 'Duration (min)', placeholder: 'min' },
    { field: 'avg_hr', label: 'Avg HR', placeholder: 'bpm' },
    { field: 'max_hr', label: 'Max HR', placeholder: 'bpm' },
];

function Header() {
    return html`
        <div class="extra-session-header">
            <h3 class="extra-session-title">${EXTRA_SESSION_TITLE}</h3>
            <span class="extra-session-pill">off-plan</span>
        </div>
    `;
}

export function ExtraSessionCard({ date, entry, isEditable = true }) {
    const [draft, setDraft] = useState(null); // null = not drafting

    const hasEntry = entry && !isDeletedEntry(entry);

    if (hasEntry) {
        return html`
            <div class="extra-session-card">
                <${Header} />
                <${CardioEntry}
                    date=${date}
                    exerciseId=${EXTRA_SESSION_KEY}
                    targetMin=${null}
                    data=${entry}
                    isEditable=${isEditable}
                />
                ${isEditable && html`
                    <div class="extra-session-actions">
                        <button
                            class="extra-session-delete"
                            onClick=${() => deleteLogEntry(date, EXTRA_SESSION_KEY)}
                        >Delete session</button>
                    </div>
                `}
            </div>
        `;
    }

    if (!isEditable) return null;

    if (draft === null) {
        return html`
            <button class="extra-session-add-btn" onClick=${() => setDraft({})}>
                + Add Zone 2 session
            </button>
        `;
    }

    const canSave = draft.duration_min != null;

    const save = () => {
        if (!canSave) return;
        const data = {};
        for (const { field } of DRAFT_FIELDS) {
            if (draft[field] != null) data[field] = draft[field];
        }
        updateLog(date, EXTRA_SESSION_KEY, data);
        setDraft(null);
    };

    return html`
        <div class="extra-session-card extra-session-card--draft">
            <${Header} />
            <div class="cardio-entry">
                ${DRAFT_FIELDS.map(({ field, label, placeholder }) => html`
                    <div class="cardio-field" key=${field}>
                        <label>${label}</label>
                        <${NumericInput}
                            placeholder=${placeholder}
                            value=${draft[field]}
                            onValueChange=${(v) => setDraft(prev => ({ ...prev, [field]: v }))}
                        />
                    </div>
                `)}
            </div>
            <div class="extra-session-actions">
                <button
                    class="extra-session-save"
                    disabled=${!canSave}
                    onClick=${save}
                >Save</button>
                <button
                    class="extra-session-delete"
                    onClick=${() => setDraft(null)}
                >Delete</button>
            </div>
        </div>
    `;
}
