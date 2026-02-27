/**
 * TrackerList Component
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';
import { trackerConfig, selectedDate, syncMetadata, pendingConflicts, collapsedCategories, toggleCategoryCollapsed } from '../store.js';
import { getLastNDays, shouldShowTracker, groupByCategory } from '../utils.js';
import { getToday } from '../../shared/utils.js';
import { TrackerItem } from './TrackerItem.js';

const html = htm.bind(h);

function DateSelector({ selected, dirtyConfig, hasConflicts, onDateSelect }) {
    const days = getLastNDays(5);
    const today = getToday();

    const handleDateClick = (date) => {
        // Disable past dates if config is dirty or there are conflicts
        if ((dirtyConfig || hasConflicts) && date !== today) {
            return;
        }
        onDateSelect(date);
    };

    const isDisabled = (date) => (dirtyConfig || hasConflicts) && date !== today;

    return html`
        <div class="date-selector">
            ${days.map(day => html`
                <div
                    class="date-item ${day.date === selected ? 'selected' : ''} ${isDisabled(day.date) ? 'disabled' : ''}"
                    onClick=${() => handleDateClick(day.date)}
                    key=${day.date}
                >
                    <span class="date-day">${day.dayName}</span>
                    <span class="date-num">${day.dayNum}</span>
                </div>
            `)}
        </div>
    `;
}

export function TrackerList() {
    const [config, setConfig] = useState(trackerConfig.value);
    const [date, setDate] = useState(selectedDate.value);
    const [dirtyConfig, setDirtyConfig] = useState(syncMetadata.value.dirtyConfig);
    const [hasConflicts, setHasConflicts] = useState(pendingConflicts.value.length > 0);
    const [collapsed, setCollapsed] = useState(new Set(collapsedCategories.value));

    useEffect(() => {
        const dispose = effect(() => {
            setConfig([...trackerConfig.value]);
            setDate(selectedDate.value);
            setDirtyConfig(syncMetadata.value.dirtyConfig);
            setHasConflicts(pendingConflicts.value.length > 0);
            setCollapsed(new Set(collapsedCategories.value));
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
                <${DateSelector} selected=${date} dirtyConfig=${dirtyConfig} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
                <div class="main-content">
                    <div class="empty-state">
                        <div class="empty-state-icon">\u{1F4DD}</div>
                        <p>No trackers configured yet.</p>
                        <p>Tap the settings icon to add your first tracker.</p>
                    </div>
                </div>
            </div>
        `;
    }

    if (visibleTrackers.length === 0) {
        return html`
            <div>
                <${DateSelector} selected=${date} dirtyConfig=${dirtyConfig} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
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
            <${DateSelector} selected=${date} dirtyConfig=${dirtyConfig} hasConflicts=${hasConflicts} onDateSelect=${handleDateSelect} />
            <div class="main-content">
                ${categories.map(category => {
                    const isCollapsed = collapsed.has(category);
                    return html`
                        <div class="category" key=${category}>
                            <div class="category-header" onClick=${() => toggleCategoryCollapsed(category)}>
                                <span class="category-chevron ${isCollapsed ? 'collapsed' : ''}">▼</span>
                                <h2 class="category-title">${category}</h2>
                            </div>
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
