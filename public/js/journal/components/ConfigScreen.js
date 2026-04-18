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
import { getCategories, groupByCategory } from '../utils.js';
import { generateId } from '../../shared/utils.js';

const html = htm.bind(h);

const DAYS_OF_WEEK = [
    { value: 0, label: 'Sunday' },
    { value: 1, label: 'Monday' },
    { value: 2, label: 'Tuesday' },
    { value: 3, label: 'Wednesday' },
    { value: 4, label: 'Thursday' },
    { value: 5, label: 'Friday' },
    { value: 6, label: 'Saturday' }
];

function TrackerForm({ tracker, onSave, onCancel }) {
    const existingCategories = getCategories(trackerConfig.value);

    const [formData, setFormData] = useState({
        name: tracker?.name || '',
        category: tracker?.category || '',
        type: tracker?.type || 'simple',
        unit: tracker?.unit || '',
        defaultValue: tracker?.defaultValue ?? '',
        accumulator: tracker?.accumulator === true,
        frequency: tracker?.frequency || 'daily',
        weeklyDay: tracker?.weeklyDay ?? 1
    });

    const [newCategory, setNewCategory] = useState('');
    const [useNewCategory, setUseNewCategory] = useState(existingCategories.length === 0);

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
            frequency: formData.frequency
        };

        if (formData.type === 'quantifiable') {
            trackerData.unit = formData.unit;
            trackerData.defaultValue = formData.defaultValue !== '' ? Number(formData.defaultValue) : null;
            trackerData.accumulator = formData.accumulator === true;
        }

        if (formData.frequency === 'weekly') {
            trackerData.weeklyDay = Number(formData.weeklyDay);
        }

        onSave(trackerData);
    };

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
                    `}

                    <div class="form-group">
                        <label class="form-label">Frequency</label>
                        <select
                            class="form-select"
                            value=${formData.frequency}
                            onChange=${handleChange('frequency')}
                        >
                            <option value="daily">Daily</option>
                            <option value="weekly">Weekly</option>
                        </select>
                    </div>

                    ${formData.frequency === 'weekly' && html`
                        <div class="form-group">
                            <label class="form-label">Day of Week</label>
                            <select
                                class="form-select"
                                value=${formData.weeklyDay}
                                onChange=${handleChange('weeklyDay')}
                            >
                                ${DAYS_OF_WEEK.map(day => html`
                                    <option value=${day.value} key=${day.value}>${day.label}</option>
                                `)}
                            </select>
                        </div>
                    `}

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

    const getFrequencyLabel = () => {
        if (tracker.frequency === 'weekly') {
            const day = DAYS_OF_WEEK.find(d => d.value === tracker.weeklyDay);
            return `Weekly (${day?.label || 'Unknown'})`;
        }
        return 'Daily';
    };

    return html`
        <div class="tracker-config-item">
            <div class="tracker-config-info">
                <div class="tracker-config-name">${tracker.name}</div>
                <div class="tracker-config-meta">
                    ${getTypeLabel(tracker.type)} \u2022 ${getFrequencyLabel()}
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
