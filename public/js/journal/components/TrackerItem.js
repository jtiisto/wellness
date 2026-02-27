/**
 * TrackerItem Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { selectedDate, dailyLogs, updateEntry, isDayEditable } from '../store.js';

const html = htm.bind(h);

export function TrackerItem({ tracker }) {
    const [date, setDate] = useState(selectedDate.value);
    const [logs, setLogs] = useState(dailyLogs.value);

    useEffect(() => {
        const dispose = effect(() => {
            setDate(selectedDate.value);
            setLogs({...dailyLogs.value});
        });
        return dispose;
    }, []);

    const entry = logs[date]?.[tracker.id] || {};
    const editable = isDayEditable(date);

    const completed = entry.completed ?? false;
    const value = entry.value ?? tracker.defaultValue ?? (tracker.type === 'evaluation' ? 50 : null);

    const handleCompletedChange = (e) => {
        if (!editable) return;
        const newCompleted = e.target.checked;
        const updateData = { completed: newCompleted };

        // When checking a quantifiable/evaluation tracker without an existing value,
        // include the default value in the entry
        if (newCompleted && entry.value === undefined) {
            if (tracker.type === 'quantifiable' && tracker.defaultValue !== undefined) {
                updateData.value = tracker.defaultValue;
            } else if (tracker.type === 'evaluation') {
                updateData.value = tracker.defaultValue ?? 50;
            }
        }

        updateEntry(date, tracker.id, updateData);
    };

    const handleValueChange = (e) => {
        if (!editable || !completed) return;
        const newValue = e.target.value === '' ? null : Number(e.target.value);
        updateEntry(date, tracker.id, { value: newValue });
    };

    const handleSliderChange = (e) => {
        if (!editable || !completed) return;
        updateEntry(date, tracker.id, { value: Number(e.target.value) });
    };

    const handleNoteChange = (e) => {
        if (!editable) return;
        const noteValue = e.target.value;
        updateEntry(date, tracker.id, { value: noteValue, completed: noteValue.trim() !== '' });
    };

    return html`
        <div class="tracker-item ${!editable ? 'disabled' : ''} ${tracker.type === 'note' ? 'tracker-item-note' : ''}">
            <div class="tracker-row">
                ${tracker.type !== 'note' && html`
                    <div class="tracker-checkbox">
                        <input
                            type="checkbox"
                            checked=${completed}
                            onChange=${handleCompletedChange}
                            disabled=${!editable}
                        />
                    </div>
                `}
                <div class="tracker-info">
                    <div class="tracker-name">${tracker.name}</div>
                </div>
                ${tracker.type === 'quantifiable' && html`
                    <div class="tracker-value-input">
                        <input
                            type="number"
                            value=${value ?? ''}
                            onChange=${handleValueChange}
                            disabled=${!editable}
                            min="0"
                            step="any"
                        />
                        <span class="tracker-unit">${tracker.unit || ''}</span>
                    </div>
                `}
                ${tracker.type === 'evaluation' && html`
                    <div class="tracker-slider">
                        <input
                            type="range"
                            min="0"
                            max="100"
                            step="25"
                            value=${value ?? 50}
                            onInput=${handleSliderChange}
                            onWheel=${(e) => e.preventDefault()}
                            disabled=${!editable}
                        />
                    </div>
                `}
            </div>
            ${tracker.type === 'note' && html`
                <div class="tracker-note-input">
                    <textarea
                        value=${value ?? ''}
                        onInput=${handleNoteChange}
                        disabled=${!editable}
                        placeholder="Add note..."
                        rows="2"
                    />
                </div>
            `}
        </div>
    `;
}
