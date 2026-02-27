/**
 * Date Selector Component - 7-day strip
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import { effect } from '@preact/signals';
import htm from 'htm';

import { selectedDate } from '../store.js';
import { getDateRange, formatDateShort, isToday } from '../utils.js';

const html = htm.bind(h);

export function DateSelector({ plans, logs }) {
    const [current, setCurrent] = useState(selectedDate.value);
    const [dates, setDates] = useState([]);

    useEffect(() => {
        const dispose = effect(() => {
            setCurrent(selectedDate.value);
        });
        return dispose;
    }, []);

    useEffect(() => {
        setDates(getDateRange(current, 3));
    }, [current]);

    const handleDateClick = (date) => {
        selectedDate.value = date;
    };

    return html`
        <div class="date-selector">
            ${dates.map(date => {
                const { day, num } = formatDateShort(date);
                const isSelected = date === current;
                const hasPlan = !!plans[date];
                const hasLog = !!logs[date];

                let className = 'date-item';
                if (isSelected) className += ' selected';
                if (hasPlan) className += ' has-plan';
                if (hasLog) className += ' has-log';

                return html`
                    <div
                        class=${className}
                        onClick=${() => handleDateClick(date)}
                    >
                        <span class="date-day">${day}</span>
                        <span class="date-num">${num}</span>
                    </div>
                `;
            })}
        </div>
    `;
}
