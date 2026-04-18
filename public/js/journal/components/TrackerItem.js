/**
 * TrackerItem Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import {
    selectedDate,
    dailyLogs,
    trackerValueUpdatedTimes,
    updateEntry,
    markValueUpdated,
    isDayEditable,
    getLastValue
} from '../store.js';
import { NumericInput } from '../../shared/numeric-input.js';
import { getToday, parseLocalDate } from '../../shared/utils.js';

const html = htm.bind(h);

// Format a value-updated timestamp for display.
// Same calendar day as the selected date → time only. Different day → short date + time.
function formatLastUpdated(isoString, selectedDateStr) {
    if (!isoString) return null;
    const d = new Date(isoString);
    // Parse selected date as local (yyyy-mm-dd), compare only calendar parts.
    const [yr, mo, dy] = selectedDateStr.split('-').map(Number);
    const sameDay = d.getFullYear() === yr && (d.getMonth() + 1) === mo && d.getDate() === dy;
    if (sameDay) {
        return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    }
    return d.toLocaleString(undefined, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });
}

export function TrackerItem({ tracker }) {
    const [date, setDate] = useState(selectedDate.value);
    const [logs, setLogs] = useState(dailyLogs.value);
    const [valueUpdated, setValueUpdated] = useState(trackerValueUpdatedTimes.value);

    useEffect(() => {
        const dispose = effect(() => {
            setDate(selectedDate.value);
            setLogs({...dailyLogs.value});
            setValueUpdated({...trackerValueUpdatedTimes.value});
        });
        return dispose;
    }, []);

    const entry = logs[date]?.[tracker.id] || {};
    const editable = isDayEditable(date);

    const completed = entry.completed ?? false;
    const isCommitted = entry.completed === true;
    const value = entry.value ?? tracker.defaultValue ?? (tracker.type === 'evaluation' ? 50 : null);

    const isAccumulator = tracker.type === 'quantifiable' && tracker.accumulator === true;
    const lastUpdatedIso = valueUpdated[`${date}|${tracker.id}`] || null;
    const lastUpdatedLabel = isAccumulator ? formatLastUpdated(lastUpdatedIso, date) : null;

    // Memory hint: on today's entry, show the most recent prior committed
    // value. Skip for accumulators (their "last updated" line already serves).
    const showLastValueHint = tracker.type === 'quantifiable'
        && !isAccumulator
        && date === getToday();
    const lastPrior = showLastValueHint ? getLastValue(tracker.id, date) : null;
    const lastPriorLabel = lastPrior
        ? `Last: ${lastPrior.value}${tracker.unit ? ' ' + tracker.unit : ''} on ${parseLocalDate(lastPrior.date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`
        : null;

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

    const handleSliderChange = (e) => {
        if (!editable) return;
        updateEntry(date, tracker.id, { value: Number(e.target.value) });
    };

    const handleNoteChange = (e) => {
        if (!editable) return;
        const noteValue = e.target.value;
        updateEntry(date, tracker.id, { value: noteValue, completed: noteValue.trim() !== '' });
    };

    const handleNumericChange = (v) => {
        if (!editable) return;
        updateEntry(date, tracker.id, { value: v });
        if (isAccumulator) markValueUpdated(date, tracker.id);
    };

    const handleAccumulatorAdd = () => {
        if (!editable) return;
        const raw = prompt(`Add to ${tracker.name}${tracker.unit ? ` (${tracker.unit})` : ''}:`);
        if (raw === null) return;
        const increment = Number(raw);
        if (!Number.isFinite(increment) || increment === 0) return;
        const current = Number(value) || 0;
        const next = current + increment;
        updateEntry(date, tracker.id, { value: next });
        markValueUpdated(date, tracker.id);
    };

    const rowClasses = [
        'tracker-item',
        !editable ? 'disabled' : '',
        !isCommitted ? 'tracker-item--uncommitted' : '',
        tracker.type === 'note' ? 'tracker-item-note' : ''
    ].filter(Boolean).join(' ');

    return html`
        <div class=${rowClasses}>
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
                        <${NumericInput}
                            value=${value}
                            onValueChange=${handleNumericChange}
                            disabled=${!editable}
                            min="0"
                            step="any"
                        />
                        <span class="tracker-unit">${tracker.unit || ''}</span>
                        ${isAccumulator && html`
                            <button
                                type="button"
                                class="tracker-accum-btn"
                                onClick=${handleAccumulatorAdd}
                                disabled=${!editable}
                                title="Add to total"
                                aria-label="Add to total"
                            >+</button>
                        `}
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
                            aria-valuetext="${value ?? 50}"
                        />
                        <span class="tracker-slider-value" aria-hidden="true">${value ?? 50}</span>
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
            ${lastUpdatedLabel && html`
                <div class="tracker-last-updated">Last updated ${lastUpdatedLabel}</div>
            `}
            ${lastPriorLabel && html`
                <div class="tracker-last-value">${lastPriorLabel}</div>
            `}
        </div>
    `;
}
