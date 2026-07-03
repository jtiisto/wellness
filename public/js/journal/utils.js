/**
 * Journal-specific utility functions
 */
import { formatDateLocal, parseLocalDate } from '../shared/utils.js';

// Every weekday (Sun..Sat, 0..6) — the "daily" schedule and the default when a
// tracker carries no schedule at all.
export const ALL_DAYS = [0, 1, 2, 3, 4, 5, 6];

// Far-past sentinel for the genesis schedule segment. Sorts below any real
// YYYY-MM-DD under plain string comparison, so the genesis segment covers all
// dates before the first schedule change. Never parsed as a Date.
export const SCHEDULE_GENESIS_DATE = '0000-01-01';

// Tracker polarity values. Stored-only for now — nothing reads it yet; the
// later journal entry-screen redesign is the intended consumer.
// Absent polarity is treated as unspecified/neutral.
export const POLARITY_VALUES = ['positive', 'negative', 'neutral'];

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

// Normalize a weekday list to sorted, de-duplicated integers in 0..6. Ignores
// anything out of range or non-integer, so a stray value can never widen or
// corrupt a schedule.
function normalizeDays(days) {
    if (!Array.isArray(days)) {
        return [];
    }
    const seen = new Set();
    for (const d of days) {
        const n = Number(d);
        if (Number.isInteger(n) && n >= 0 && n <= 6) {
            seen.add(n);
        }
    }
    return Array.from(seen).sort((a, b) => a - b);
}

function daysEqual(a, b) {
    if (a.length !== b.length) {
        return false;
    }
    return a.every((v, i) => v === b[i]);
}

/**
 * Resolve the set of weekdays a tracker is scheduled on, as of a given date.
 *
 * Priority (see docs/ARCHITECTURE.md "Tracker scheduling"):
 *   1. `scheduleHistory` (effective-dated segments) — pick the segment with the
 *      greatest `effectiveFrom <= dateStr`; if the date precedes every segment,
 *      fall back to the earliest. `effectiveFrom` is compared as a plain
 *      YYYY-MM-DD string (== chronological for zero-padded ISO dates; no Date,
 *      no timezone).
 *   2. Legacy `frequency: 'weekly'` → just `weeklyDay`.
 *   3. Legacy `frequency: 'daily'`, or nothing at all → every day.
 *
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {Set<number>} weekdays (0=Sun..6=Sat)
 */
export function getScheduleDaysForDate(tracker, dateStr) {
    const history = tracker && tracker.scheduleHistory;
    if (Array.isArray(history) && history.length > 0) {
        let chosen = null;
        for (const seg of history) {
            if (seg.effectiveFrom <= dateStr &&
                (chosen === null || seg.effectiveFrom > chosen.effectiveFrom)) {
                chosen = seg;
            }
        }
        if (chosen === null) {
            // dateStr precedes every segment — use the earliest.
            chosen = history.reduce(
                (a, b) => (b.effectiveFrom < a.effectiveFrom ? b : a));
        }
        return new Set(normalizeDays(chosen.days));
    }

    if (tracker && tracker.frequency === 'weekly') {
        return new Set([tracker.weeklyDay]);
    }

    return new Set(ALL_DAYS);
}

/**
 * Whether a tracker is expected on a given date — its schedule (as of that date)
 * includes that date's local weekday.
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {boolean}
 */
export function isExpectedOn(tracker, dateStr) {
    return getScheduleDaysForDate(tracker, dateStr).has(getDayOfWeek(dateStr));
}

/**
 * Check if a tracker should appear on a given date.
 *
 * Visibility == "expected on this date". The off-schedule "an entry already
 * exists → also show" rule ships with the visibility-integration work and is
 * intentionally NOT applied here yet.
 *
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {boolean}
 */
export function shouldShowTracker(tracker, dateStr) {
    return isExpectedOn(tracker, dateStr);
}

/**
 * Compute the tracker's next `scheduleHistory` after the user picks a new set of
 * weekdays, following the apply-from-today write rules (see
 * docs/ARCHITECTURE.md "Tracker scheduling"):
 *
 *   - No-op: `newDays` equals the currently-effective days → `changed: false`
 *     and the caller must NOT write or mark the tracker dirty.
 *   - First edit (no `scheduleHistory` yet): materialize a genesis segment with
 *     the pre-edit schedule from `SCHEDULE_GENESIS_DATE`, then a segment with
 *     the new schedule effective `today` — the whole past keeps the old
 *     schedule, today-onward gets the new one.
 *   - Same-day re-edit (latest segment's `effectiveFrom === today`): replace
 *     that segment's days in place, keeping `effectiveFrom` strictly increasing.
 *   - Later change (latest `effectiveFrom < today`): append a new segment.
 *
 * Pure: `today` is passed in (local YYYY-MM-DD) rather than read from the clock.
 *
 * @param {Object} tracker - Current tracker config (may carry scheduleHistory or legacy fields)
 * @param {number[]} newDays - Chosen weekdays (0..6)
 * @param {string} today - Local YYYY-MM-DD (caller passes getToday())
 * @returns {{changed: boolean, scheduleHistory: Array<{effectiveFrom: string, days: number[]}>|undefined}}
 */
export function computeScheduleHistoryUpdate(tracker, newDays, today) {
    const days = normalizeDays(newDays);
    const currentDays = normalizeDays(
        Array.from(getScheduleDaysForDate(tracker, today)));

    if (daysEqual(days, currentDays)) {
        return { changed: false, scheduleHistory: tracker && tracker.scheduleHistory };
    }

    const history = (tracker && Array.isArray(tracker.scheduleHistory) &&
                     tracker.scheduleHistory.length > 0)
        ? tracker.scheduleHistory
        : null;

    if (history === null) {
        // First edit: split the past (old schedule) from today (new schedule).
        return {
            changed: true,
            scheduleHistory: [
                { effectiveFrom: SCHEDULE_GENESIS_DATE, days: currentDays },
                { effectiveFrom: today, days },
            ],
        };
    }

    const latest = history.reduce(
        (a, b) => (b.effectiveFrom > a.effectiveFrom ? b : a));

    if (latest.effectiveFrom === today) {
        // Same-day re-edit: replace in place rather than duplicating today.
        return {
            changed: true,
            scheduleHistory: history.map(
                seg => (seg === latest ? { ...seg, days } : seg)),
        };
    }

    return {
        changed: true,
        scheduleHistory: [...history, { effectiveFrom: today, days }],
    };
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
