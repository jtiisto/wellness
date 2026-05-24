/**
 * Journal-specific utility functions
 */
import { formatDateLocal, parseLocalDate } from '../shared/utils.js';

/**
 * Get the last N days including today
 * @param {number} n - Number of days
 * @returns {Array<{date: string, dayName: string, dayNum: number, isToday: boolean}>}
 */
export function getLastNDays(n = 7) {
    const days = [];
    const today = new Date();
    const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

    for (let i = n - 1; i >= 0; i--) {
        const date = new Date(today);
        date.setDate(today.getDate() - i);

        days.push({
            date: formatDateLocal(date),
            dayName: dayNames[date.getDay()],
            dayNum: date.getDate(),
            isToday: i === 0,
            dayOfWeek: date.getDay()
        });
    }

    return days;
}

/**
 * Get the day of week for a date string
 * @param {string} dateStr - ISO date string
 * @returns {number} 0-6 (Sunday-Saturday)
 */
export function getDayOfWeek(dateStr) {
    return parseLocalDate(dateStr).getDay();
}

/**
 * Check if a tracker should appear on a given date based on frequency
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - ISO date string
 * @returns {boolean}
 */
export function shouldShowTracker(tracker, dateStr) {
    if (tracker.frequency === 'daily') {
        return true;
    }

    if (tracker.frequency === 'weekly') {
        const dayOfWeek = getDayOfWeek(dateStr);
        return dayOfWeek === tracker.weeklyDay;
    }

    return true;
}

/**
 * Check if date is within the last N days
 * @param {string} dateStr - ISO date string
 * @param {number} days - Number of days
 * @returns {boolean}
 */
export function isWithinLastNDays(dateStr, days = 7) {
    const date = parseLocalDate(dateStr);
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const cutoff = new Date(today);
    cutoff.setDate(today.getDate() - days);

    return date >= cutoff;
}

/**
 * Group trackers by category, sorted alphabetically
 * @param {Array} trackers - Array of tracker configs
 * @returns {Object} Object with category names as keys
 */
export function groupByCategory(trackers) {
    const grouped = {};

    trackers.forEach(tracker => {
        const category = tracker.category || 'Uncategorized';
        if (!grouped[category]) {
            grouped[category] = [];
        }
        grouped[category].push(tracker);
    });

    // Sort trackers within each category alphabetically
    Object.keys(grouped).forEach(category => {
        grouped[category].sort((a, b) => a.name.localeCompare(b.name));
    });

    // Return sorted categories
    const sortedCategories = Object.keys(grouped).sort();
    const result = {};
    sortedCategories.forEach(cat => {
        result[cat] = grouped[cat];
    });

    return result;
}

/**
 * Get unique categories from trackers
 * @param {Array} trackers - Array of tracker configs
 * @returns {Array<string>} Sorted array of unique categories
 */
export function getCategories(trackers) {
    const categories = new Set();
    trackers.forEach(t => {
        if (t.category) {
            categories.add(t.category);
        }
    });
    return Array.from(categories).sort();
}
