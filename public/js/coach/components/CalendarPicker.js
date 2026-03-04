/**
 * Calendar Picker Component - Full calendar popup for date selection
 */
import { h } from 'preact';
import { useState, useEffect, useRef } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { selectedDate, earliestDate } from '../store.js';
import { getToday, parseLocalDate, formatDateLocal } from '../../shared/utils.js';
import { isToday, isPast } from '../utils.js';

const html = htm.bind(h);

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
                'July', 'August', 'September', 'October', 'November', 'December'];

/**
 * Get workout status for a date
 * Returns: 'completed' | 'missed' | 'scheduled' | null
 */
function getWorkoutStatus(dateStr, plans, logs) {
    const hasPlan = !!plans[dateStr];
    const hasLog = !!logs[dateStr];

    if (!hasPlan) return null;

    const today = getToday();

    if (dateStr > today) {
        // Future date with plan
        return 'scheduled';
    }

    if (dateStr <= today) {
        // Past or today - check if workout was done
        if (hasLog) {
            // Check if any exercises were logged
            const log = logs[dateStr];
            const hasAnyProgress = Object.keys(log).some(key => {
                if (key === 'session_feedback' || key.startsWith('_')) return false;
                const entry = log[key];
                // Check for any logged data
                return entry.completed ||
                       entry.sets?.length > 0 ||
                       entry.completed_items?.length > 0 ||
                       entry.duration_min != null;
            });
            return hasAnyProgress ? 'completed' : 'missed';
        }
        // Has plan but no log
        return dateStr === today ? 'scheduled' : 'missed';
    }

    return null;
}

/**
 * Format date for display in the button
 */
function formatSelectedDate(dateStr) {
    const date = parseLocalDate(dateStr);
    const today = getToday();

    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    const yesterdayStr = formatDateLocal(yesterday);

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const tomorrowStr = formatDateLocal(tomorrow);

    if (dateStr === today) return 'Today';
    if (dateStr === yesterdayStr) return 'Yesterday';
    if (dateStr === tomorrowStr) return 'Tomorrow';

    return date.toLocaleDateString('en-US', {
        weekday: 'short',
        month: 'short',
        day: 'numeric'
    });
}

export function CalendarPicker({ plans, logs }) {
    const [isOpen, setIsOpen] = useState(false);
    const [current, setCurrent] = useState(selectedDate.value);
    const [viewDate, setViewDate] = useState(() => {
        const d = parseLocalDate(selectedDate.value);
        return { year: d.getFullYear(), month: d.getMonth() };
    });
    const modalRef = useRef(null);

    useEffect(() => {
        const dispose = effect(() => {
            setCurrent(selectedDate.value);
            const d = parseLocalDate(selectedDate.value);
            setViewDate({ year: d.getFullYear(), month: d.getMonth() });
        });
        return dispose;
    }, []);

    // Close on outside click
    useEffect(() => {
        if (!isOpen) return;

        const handleClickOutside = (e) => {
            if (modalRef.current && !modalRef.current.contains(e.target)) {
                setIsOpen(false);
            }
        };

        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, [isOpen]);

    const handleDateSelect = (dateStr) => {
        if (earliestDate.value && dateStr < earliestDate.value) return;
        selectedDate.value = dateStr;
        setIsOpen(false);
    };

    const handlePrevMonth = () => {
        setViewDate(prev => {
            const newMonth = prev.month === 0 ? 11 : prev.month - 1;
            const newYear = prev.month === 0 ? prev.year - 1 : prev.year;
            if (earliestDate.value) {
                const lastDayOfMonth = new Date(newYear, newMonth + 1, 0);
                const lastDateStr = `${newYear}-${String(newMonth + 1).padStart(2, '0')}-${String(lastDayOfMonth.getDate()).padStart(2, '0')}`;
                if (lastDateStr < earliestDate.value) return prev;
            }
            return { year: newYear, month: newMonth };
        });
    };

    const handleNextMonth = () => {
        setViewDate(prev => {
            if (prev.month === 11) {
                return { year: prev.year + 1, month: 0 };
            }
            return { year: prev.year, month: prev.month + 1 };
        });
    };

    const handleToday = () => {
        const today = getToday();
        selectedDate.value = today;
        const d = parseLocalDate(today);
        setViewDate({ year: d.getFullYear(), month: d.getMonth() });
        setIsOpen(false);
    };

    // Generate calendar days
    const generateCalendarDays = () => {
        const { year, month } = viewDate;
        const firstDay = new Date(year, month, 1);
        const lastDay = new Date(year, month + 1, 0);
        const startPadding = firstDay.getDay();
        const totalDays = lastDay.getDate();

        const days = [];

        // Previous month padding
        const prevMonth = month === 0 ? 11 : month - 1;
        const prevYear = month === 0 ? year - 1 : year;
        const prevMonthDays = new Date(prevYear, prevMonth + 1, 0).getDate();

        for (let i = startPadding - 1; i >= 0; i--) {
            const day = prevMonthDays - i;
            const dateStr = `${prevYear}-${String(prevMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const isDisabled = earliestDate.value && dateStr < earliestDate.value;
            days.push({ day, dateStr, isOtherMonth: true, isDisabled });
        }

        // Current month
        for (let day = 1; day <= totalDays; day++) {
            const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const isDisabled = earliestDate.value && dateStr < earliestDate.value;
            days.push({ day, dateStr, isOtherMonth: false, isDisabled });
        }

        // Next month padding
        const remaining = 42 - days.length; // 6 rows * 7 days
        const nextMonth = month === 11 ? 0 : month + 1;
        const nextYear = month === 11 ? year + 1 : year;

        for (let day = 1; day <= remaining; day++) {
            const dateStr = `${nextYear}-${String(nextMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const isDisabled = earliestDate.value && dateStr < earliestDate.value;
            days.push({ day, dateStr, isOtherMonth: true, isDisabled });
        }

        return days;
    };

    const calendarDays = isOpen ? generateCalendarDays() : [];
    const today = getToday();
    const status = getWorkoutStatus(current, plans, logs);

    return html`
        <div class="calendar-picker" ref=${modalRef}>
            <button
                class="calendar-trigger"
                onClick=${() => setIsOpen(!isOpen)}
            >
                <span class="calendar-icon">📅</span>
                <span class="calendar-date">${formatSelectedDate(current)}</span>
                ${status && html`<span class="calendar-status-dot ${status}"></span>`}
                <span class="calendar-chevron">${isOpen ? '▲' : '▼'}</span>
            </button>

            ${isOpen && html`
                <div class="calendar-modal">
                    <div class="calendar-header">
                        <button class="calendar-nav" onClick=${handlePrevMonth}>◀</button>
                        <span class="calendar-month-year">
                            ${MONTHS[viewDate.month]} ${viewDate.year}
                        </span>
                        <button class="calendar-nav" onClick=${handleNextMonth}>▶</button>
                    </div>

                    <div class="calendar-weekdays">
                        ${DAYS.map(day => html`
                            <div class="calendar-weekday">${day}</div>
                        `)}
                    </div>

                    <div class="calendar-days">
                        ${calendarDays.map(({ day, dateStr, isOtherMonth, isDisabled }) => {
                            const dayStatus = getWorkoutStatus(dateStr, plans, logs);
                            const isSelected = dateStr === current;
                            const isTodayDate = dateStr === today;

                            let className = 'calendar-day';
                            if (isOtherMonth) className += ' other-month';
                            if (isSelected) className += ' selected';
                            if (isTodayDate) className += ' today';
                            if (dayStatus) className += ` status-${dayStatus}`;
                            if (isDisabled) className += ' disabled';

                            return html`
                                <button
                                    class=${className}
                                    onClick=${isDisabled ? undefined : () => handleDateSelect(dateStr)}
                                >
                                    ${day}
                                </button>
                            `;
                        })}
                    </div>

                    <div class="calendar-footer">
                        <button class="calendar-today-btn" onClick=${handleToday}>
                            Today
                        </button>
                    </div>

                    <div class="calendar-legend">
                        <span class="legend-item"><span class="legend-dot completed"></span> Completed</span>
                        <span class="legend-item"><span class="legend-dot missed"></span> Missed</span>
                        <span class="legend-item"><span class="legend-dot scheduled"></span> Scheduled</span>
                    </div>
                </div>
            `}
        </div>
    `;
}
