/**
 * TrackerList Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { trackerConfig, selectedDate, syncMetadata, pendingConflicts, expandedCategories, toggleCategoryExpanded } from '../store.js';
import { getLastNDays, shouldShowTracker, groupByCategory } from '../utils.js';
import { getToday } from '../../shared/utils.js';
import { TrackerItem } from './TrackerItem.js';

const html = htm.bind(h);

function DateSelector({ selected, hasDirtyTrackers, hasConflicts, onDateSelect }) {
    const days = getLastNDays(7);
    const today = getToday();

    const handleDateClick = (date) => {
        if ((hasDirtyTrackers || hasConflicts) && date !== today) {
            return;
        }
        onDateSelect(date);
    };

    const isDisabled = (date) => (hasDirtyTrackers || hasConflicts) && date !== today;
    const lockReason = 'Locked — resolve conflicts or commit changes first';

    return html`
        <div class="date-selector" role="group" aria-label="Select date">
            ${days.map(day => {
                const disabled = isDisabled(day.date);
                const isToday = day.date === today;
                const classes = [
                    'date-item',
                    day.date === selected ? 'selected' : '',
                    isToday ? 'today' : '',
                    disabled ? 'disabled' : ''
                ].filter(Boolean).join(' ');
                const label = `${day.dayName} ${day.dayNum}${isToday ? ' (today)' : ''}${disabled ? ' — ' + lockReason : ''}`;
                return html`
                    <button
                        type="button"
                        class=${classes}
                        onClick=${() => handleDateClick(day.date)}
                        aria-pressed=${day.date === selected}
                        aria-disabled=${disabled}
                        aria-label=${label}
                        disabled=${disabled}
                        key=${day.date}
                    >
                        <span class="date-day" aria-hidden="true">${day.dayName}</span>
                        <span class="date-num" aria-hidden="true">${day.dayNum}</span>
                        ${disabled && html`
                            <svg class="date-lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="10" height="10" aria-hidden="true">
                                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                            </svg>
                        `}
                    </button>
                `;
            })}
        </div>
    `;
}

export function TrackerList() {
    const [config, setConfig] = useState(trackerConfig.value);
    const [date, setDate] = useState(selectedDate.value);
    const [hasDirtyTrackers, setHasDirtyTrackers] = useState(syncMetadata.value.dirtyTrackers.length > 0);
    const [hasConflicts, setHasConflicts] = useState(pendingConflicts.value.length > 0);
    const [expanded, setExpanded] = useState(new Set(expandedCategories.value));

    useEffect(() => {
        const dispose = effect(() => {
            setConfig([...trackerConfig.value]);
            setDate(selectedDate.value);
            setHasDirtyTrackers(syncMetadata.value.dirtyTrackers.length > 0);
            setHasConflicts(pendingConflicts.value.length > 0);
            setExpanded(new Set(expandedCategories.value));
        });
        return dispose;
    }, []);

    const handleDateSelect = (newDate) => {
        selectedDate.value = newDate;
    };

    // Filter trackers that should appear on this date (exclude deleted ones)
    const visibleTrackers = config.filter(t => !t._deleted && shouldShowTracker(t, date));

    // Group by category
    const grouped = groupByCategory(visibleTrackers);
    const categories = Object.keys(grouped);

    if (config.length === 0) {
        return html`
            <div>
                <${DateSelector} selected=${date} hasDirtyTrackers=${hasDirtyTrackers} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
                <div class="main-content">
                    <div class="empty-state">
                        <div class="empty-state-icon">\u{1F4DD}</div>
                        <p>No trackers configured yet.</p>
                        <p>Tap the settings icon in the header to add your first tracker.</p>
                    </div>
                </div>
            </div>
        `;
    }

    if (visibleTrackers.length === 0) {
        return html`
            <div>
                <${DateSelector} selected=${date} hasDirtyTrackers=${hasDirtyTrackers} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
                <div class="main-content">
                    <div class="empty-state">
                        <div class="empty-state-icon">\u{1F4C5}</div>
                        <p>No trackers scheduled for this day.</p>
                    </div>
                </div>
            </div>
        `;
    }

    return html`
        <div>
            <${DateSelector} selected=${date} hasDirtyTrackers=${hasDirtyTrackers} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
            <div class="main-content">
                ${categories.map(category => {
                    const isCollapsed = !expanded.has(category);
                    return html`
                        <div class="category" key=${category}>
                            <button
                                type="button"
                                class="category-header"
                                onClick=${() => toggleCategoryExpanded(category)}
                                aria-expanded=${!isCollapsed}
                            >
                                <span class="category-chevron ${isCollapsed ? 'collapsed' : ''}">▼</span>
                                <h2 class="category-title">${category}</h2>
                            </button>
                            ${!isCollapsed && grouped[category].map(tracker => html`
                                <${TrackerItem} tracker=${tracker} key=${tracker.id} />
                            `)}
                        </div>
                    `;
                })}
            </div>
        </div>
    `;
}
