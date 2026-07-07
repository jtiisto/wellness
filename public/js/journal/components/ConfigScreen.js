/**
 * ConfigScreen Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import {
    trackerConfig,
    addTracker,
    updateTracker,
    deleteTracker
} from '../store.js';
import {
    getCategories,
    groupByCategory,
    getScheduleDaysForDate,
    lastActiveScheduleDays,
    buildTrackerSaveFields,
    formatScheduleSummary,
    parseTarget,
    formatTarget,
    formatTargetInput,
    targetForDate,
    ALL_DAYS,
    POLARITY_VALUES,
} from '../utils.js';
import { generateId, getToday } from '../../shared/utils.js';

const html = htm.bind(h);

// Weekday toggles, Monday-first for display (values stay 0=Sun..6=Sat).
const WEEKDAY_PICKER = [
    { value: 1, label: 'M' },
    { value: 2, label: 'T' },
    { value: 3, label: 'W' },
    { value: 4, label: 'T' },
    { value: 5, label: 'F' },
    { value: 6, label: 'S' },
    { value: 0, label: 'S' },
];
const DAY_FULL_NAMES = [
    'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday',
];
const POLARITY_OPTIONS = [
    { value: '', label: 'Unspecified' },
    { value: 'positive', label: 'Positive (build)' },
    { value: 'negative', label: 'Negative (avoid)' },
    { value: 'neutral', label: 'Neutral (measure)' },
];

function TrackerForm({ tracker, onSave, onCancel }) {
    const existingCategories = getCategories(trackerConfig.value);
    const today = getToday();

    const [formData, setFormData] = useState({
        name: tracker?.name || '',
        category: tracker?.category || '',
        type: tracker?.type || 'simple',
        unit: tracker?.unit || '',
        defaultValue: tracker?.defaultValue ?? '',
        accumulator: tracker?.accumulator === true,
    });

    // A tracker with an empty schedule as of today is paused (see
    // buildTrackerSaveFields / docs "Tracker scheduling").
    const [paused, setPaused] = useState(
        tracker ? getScheduleDaysForDate(tracker, today).size === 0 : false
    );

    // Weekday schedule (0=Sun..6=Sat). Seed from the tracker's current schedule
    // as of today (handles scheduleHistory + legacy frequency/weeklyDay); a new
    // tracker defaults to Daily. For a paused tracker the current set is empty,
    // so seed the picker from lastActiveScheduleDays instead — otherwise
    // unpausing without touching the picker would coerce back to Daily and lose
    // the pre-pause days. The picker keeps this selection (dimmed) while paused
    // so the user sees what resume restores.
    const [days, setDays] = useState(
        tracker
            ? (getScheduleDaysForDate(tracker, today).size === 0
                ? lastActiveScheduleDays(tracker)
                : Array.from(getScheduleDaysForDate(tracker, today)).sort((a, b) => a - b))
            : [...ALL_DAYS]
    );
    const [polarity, setPolarity] = useState(
        (tracker && POLARITY_VALUES.includes(tracker.polarity)) ? tracker.polarity : ''
    );

    // Raw target text ("10" / "150-170"); seeded from the current target so an
    // unedited save round-trips to a no-op. Only used for quantifiable trackers.
    const [targetInput, setTargetInput] = useState(
        tracker ? formatTargetInput(targetForDate(tracker, today)) : ''
    );

    const [newCategory, setNewCategory] = useState('');
    const [useNewCategory, setUseNewCategory] = useState(existingCategories.length === 0);

    const toggleDay = (value) => {
        setDays(prev => prev.includes(value)
            ? prev.filter(d => d !== value)
            : [...prev, value].sort((a, b) => a - b));
    };

    const handleChange = (field) => (e) => {
        const value = e.target.type === 'number'
            ? (e.target.value === '' ? '' : Number(e.target.value))
            : e.target.value;
        setFormData(prev => ({ ...prev, [field]: value }));
    };

    const handleSubmit = (e) => {
        e.preventDefault();

        if (!formData.name.trim()) {
            alert('Name is required');
            return;
        }

        const category = useNewCategory ? newCategory.trim() : formData.category;
        if (!category) {
            alert('Category is required');
            return;
        }

        const trackerData = {
            id: tracker?.id || generateId(),
            name: formData.name.trim(),
            category: category,
            type: formData.type,
        };

        let target;  // undefined = leave targetHistory alone
        if (formData.type === 'quantifiable') {
            trackerData.unit = formData.unit;
            trackerData.defaultValue = formData.defaultValue !== '' ? Number(formData.defaultValue) : null;
            trackerData.accumulator = formData.accumulator === true;

            const parsed = parseTarget(targetInput, polarity);
            if (parsed.error) {
                alert(parsed.error);
                return;
            }
            target = parsed.target;  // {min?,max?} or null
        } else if (tracker && targetForDate(tracker, today) != null) {
            // Leaving the quantifiable type while a target is in effect: clear
            // it explicitly (null-target segment). Only quantifiable trackers
            // render the target input, so a stale target would otherwise stay
            // in force forever — judging every checkbox-only day 'missed' —
            // with no UI path to remove it.
            target = null;
        }

        // Schedule + polarity + target — see buildTrackerSaveFields. `paused`
        // saves an empty-days schedule (bypasses the empty→Daily coercion).
        Object.assign(trackerData, buildTrackerSaveFields(tracker, { days, polarity, target, paused }, today));

        onSave(trackerData);
    };

    // Live interpretation of the target input under the current polarity.
    const targetParse = parseTarget(targetInput, polarity);

    return html`
        <div class="modal-overlay" onClick=${onCancel}>
            <div class="modal-content" onClick=${(e) => e.stopPropagation()}>
                <div class="modal-header">
                    <h2 class="modal-title">${tracker ? 'Edit Tracker' : 'New Tracker'}</h2>
                    <button class="icon-btn" onClick=${onCancel}>\u2715</button>
                </div>
                <form onSubmit=${handleSubmit}>
                    <div class="form-group">
                        <label class="form-label">Name</label>
                        <input
                            type="text"
                            class="form-input"
                            value=${formData.name}
                            onInput=${handleChange('name')}
                            placeholder="e.g., Meditation"
                            required
                        />
                    </div>

                    <div class="form-group">
                        <label class="form-label">Category</label>
                        ${existingCategories.length > 0 && !useNewCategory ? html`
                            <select
                                class="form-select"
                                value=${formData.category}
                                onChange=${handleChange('category')}
                            >
                                <option value="">Select category...</option>
                                ${existingCategories.map(cat => html`
                                    <option value=${cat} key=${cat}>${cat}</option>
                                `)}
                            </select>
                            <button
                                type="button"
                                class="btn btn-secondary mt-md"
                                onClick=${() => setUseNewCategory(true)}
                            >
                                + New Category
                            </button>
                        ` : html`
                            <input
                                type="text"
                                class="form-input"
                                value=${newCategory}
                                onInput=${(e) => setNewCategory(e.target.value)}
                                placeholder="e.g., Supplements"
                            />
                            ${existingCategories.length > 0 && html`
                                <button
                                    type="button"
                                    class="btn btn-secondary mt-md"
                                    onClick=${() => setUseNewCategory(false)}
                                >
                                    Use Existing
                                </button>
                            `}
                        `}
                    </div>

                    <div class="form-group">
                        <label class="form-label">Type</label>
                        <select
                            class="form-select"
                            value=${formData.type}
                            onChange=${handleChange('type')}
                        >
                            <option value="simple">Simple (Yes/No only)</option>
                            <option value="quantifiable">Quantifiable (Yes/No + Value)</option>
                            <option value="evaluation">Evaluation (Yes/No + Percentage)</option>
                            <option value="note">Note (Yes/No + Text)</option>
                        </select>
                    </div>

                    ${formData.type === 'quantifiable' && html`
                        <div class="form-row">
                            <div class="form-group">
                                <label class="form-label">Unit Label</label>
                                <input
                                    type="text"
                                    class="form-input"
                                    value=${formData.unit}
                                    onInput=${handleChange('unit')}
                                    placeholder="e.g., mg, min"
                                />
                            </div>
                            <div class="form-group">
                                <label class="form-label">Default Value</label>
                                <input
                                    type="number"
                                    class="form-input"
                                    value=${formData.defaultValue}
                                    onInput=${handleChange('defaultValue')}
                                    placeholder="e.g., 30"
                                    min="0"
                                    step="any"
                                />
                            </div>
                        </div>
                        <div class="form-group">
                            <label class="form-checkbox">
                                <input
                                    type="checkbox"
                                    checked=${formData.accumulator}
                                    onChange=${(e) => setFormData(prev => ({ ...prev, accumulator: e.target.checked }))}
                                />
                                <span>Running total (accumulator) — tap + to add throughout the day</span>
                            </label>
                        </div>
                        <div class="form-group">
                            <label class="form-label">Target</label>
                            <input
                                type="text"
                                class="form-input"
                                value=${targetInput}
                                onInput=${(e) => setTargetInput(e.target.value)}
                                placeholder="e.g. 150 or 150-170"
                                aria-label="Target value or range"
                            />
                            ${targetInput.trim() && targetParse.error
                                ? html`<div class="form-error">${targetParse.error}</div>`
                                : (targetParse.target
                                    ? html`<div class="schedule-summary">Target: ${formatTarget(targetParse.target, formData.unit)}</div>`
                                    : null)}
                        </div>
                    `}

                    <div class="form-group">
                        <label class="form-label">Scheduled days</label>
                        <label class="form-checkbox">
                            <input
                                type="checkbox"
                                checked=${paused}
                                onChange=${(e) => setPaused(e.target.checked)}
                                aria-label="Paused"
                            />
                            <span>Paused</span>
                        </label>
                        <div class="form-hint">Hidden from the daily view; adherence pauses. History is kept.</div>
                        <div class="day-picker ${paused ? 'day-picker--disabled' : ''}">
                            <div class="day-toggle-group" role="group" aria-label="Scheduled days">
                                ${WEEKDAY_PICKER.map(day => html`
                                    <button
                                        type="button"
                                        class="day-toggle ${days.includes(day.value) ? 'active' : ''}"
                                        data-day=${day.value}
                                        aria-pressed=${days.includes(day.value)}
                                        aria-label=${DAY_FULL_NAMES[day.value]}
                                        onClick=${() => toggleDay(day.value)}
                                        disabled=${paused}
                                        key=${day.value}
                                    >${day.label}</button>
                                `)}
                            </div>
                            <div class="day-toggle-presets">
                                <button type="button" class="btn btn-secondary" onClick=${() => setDays([...ALL_DAYS])} disabled=${paused}>Daily</button>
                                <button type="button" class="btn btn-secondary" onClick=${() => setDays([1, 2, 3, 4, 5])} disabled=${paused}>Weekdays</button>
                            </div>
                        </div>
                        <div class="schedule-summary">${paused
                            ? 'Paused'
                            : formatScheduleSummary(days.length > 0 ? days : ALL_DAYS)}</div>
                    </div>

                    <div class="form-group">
                        <label class="form-label">Polarity</label>
                        <select
                            class="form-select"
                            aria-label="Polarity"
                            value=${polarity}
                            onChange=${(e) => setPolarity(e.target.value)}
                        >
                            ${POLARITY_OPTIONS.map(opt => html`
                                <option value=${opt.value} key=${opt.value}>${opt.label}</option>
                            `)}
                        </select>
                    </div>

                    <div class="form-group mt-md">
                        <button type="submit" class="btn btn-primary btn-block">
                            ${tracker ? 'Save Changes' : 'Add Tracker'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    `;
}

function TrackerConfigItem({ tracker, onEdit, onDelete }) {
    const getTypeLabel = (type) => {
        switch (type) {
            case 'quantifiable': return `Quantifiable${tracker.unit ? ` (${tracker.unit})` : ''}`;
            case 'evaluation': return 'Evaluation (%)';
            case 'note': return 'Note';
            default: return 'Simple';
        }
    };

    const scheduleLabel = formatScheduleSummary(getScheduleDaysForDate(tracker, getToday()));
    const polarityLabel = POLARITY_VALUES.includes(tracker.polarity)
        ? tracker.polarity.charAt(0).toUpperCase() + tracker.polarity.slice(1)
        : null;
    const targetLabel = formatTarget(targetForDate(tracker, getToday()), tracker.unit);

    return html`
        <div class="tracker-config-item">
            <div class="tracker-config-info">
                <div class="tracker-config-name">${tracker.name}</div>
                <div class="tracker-config-meta">
                    ${getTypeLabel(tracker.type)} \u2022 ${scheduleLabel}${targetLabel ? ` \u2022 ${targetLabel}` : ''}${polarityLabel ? ` \u2022 ${polarityLabel}` : ''}
                </div>
            </div>
            <div class="tracker-config-actions">
                <button class="icon-btn" onClick=${() => onEdit(tracker)} title="Edit" aria-label=${`Edit ${tracker.name}`}>
                    \u270E
                </button>
                <button class="icon-btn" onClick=${() => onDelete(tracker.id)} title="Delete" aria-label=${`Delete ${tracker.name}`}>
                    \u{1F5D1}
                </button>
            </div>
        </div>
    `;
}

export function ConfigScreen() {
    const [showForm, setShowForm] = useState(false);
    const [editTracker, setEditTracker] = useState(null);
    const [config, setConfig] = useState(trackerConfig.value);

    useEffect(() => {
        const dispose = effect(() => {
            setConfig([...trackerConfig.value]);
        });
        return dispose;
    }, []);

    // Filter out deleted trackers
    const activeConfig = config.filter(t => !t._deleted);
    const grouped = groupByCategory(activeConfig);
    const categories = Object.keys(grouped);

    const handleAddNew = () => {
        setEditTracker(null);
        setShowForm(true);
    };

    const handleEdit = (tracker) => {
        setEditTracker(tracker);
        setShowForm(true);
    };

    const handleDelete = (trackerId) => {
        if (confirm('Are you sure you want to delete this tracker?')) {
            deleteTracker(trackerId);
        }
    };

    const handleSave = (trackerData) => {
        if (editTracker) {
            updateTracker(editTracker.id, trackerData);
        } else {
            addTracker(trackerData);
        }
        setShowForm(false);
        setEditTracker(null);
    };

    const handleCancel = () => {
        setShowForm(false);
        setEditTracker(null);
    };

    return html`
        <div class="config-screen">
            <div class="config-header">
                <h2 class="config-title">Trackers</h2>
                <button class="btn btn-primary" onClick=${handleAddNew}>
                    + Add
                </button>
            </div>

            ${activeConfig.length === 0 ? html`
                <div class="empty-state">
                    <div class="empty-state-icon">\u2699</div>
                    <p>No trackers configured yet.</p>
                    <p>Tap "Add" to create your first tracker.</p>
                </div>
            ` : html`
                <div class="tracker-list-config">
                    ${categories.map(category => html`
                        <div class="category" key=${category}>
                            <h3 class="category-title">${category}</h3>
                            ${grouped[category].map(tracker => html`
                                <${TrackerConfigItem}
                                    tracker=${tracker}
                                    onEdit=${handleEdit}
                                    onDelete=${handleDelete}
                                    key=${tracker.id}
                                />
                            `)}
                        </div>
                    `)}
                </div>
            `}

            ${showForm && html`
                <${TrackerForm}
                    tracker=${editTracker}
                    onSave=${handleSave}
                    onCancel=${handleCancel}
                />
            `}
        </div>
    `;
}
