/**
 * Coach-specific utility functions
 * Shared utilities (generateId, getToday, getUtcNow, formatDateLocal,
 * parseLocalDate, deepClone) live in ../shared/utils.js
 */
import { formatDateLocal, parseLocalDate, getToday } from '../shared/utils.js';

/**
 * Format date for display (e.g., "Mon", "Feb 2")
 */
export function formatDateShort(dateStr) {
    const date = parseLocalDate(dateStr);
    return {
        day: date.toLocaleDateString('en-US', { weekday: 'short' }),
        num: date.getDate()
    };
}

/**
 * Get an array of dates centered around today
 */
export function getDateRange(centerDate, daysAround = 3) {
    const dates = [];
    const center = parseLocalDate(centerDate);

    for (let i = -daysAround; i <= daysAround; i++) {
        const date = new Date(center);
        date.setDate(date.getDate() + i);
        dates.push(formatDateLocal(date));
    }

    return dates;
}

/**
 * Check if a date is today
 */
export function isToday(dateStr) {
    return dateStr === getToday();
}

/**
 * Check if a date is in the past (before today)
 */
export function isPast(dateStr) {
    return dateStr < getToday();
}

/**
 * Check if a date is in the future (after today)
 */
export function isFuture(dateStr) {
    return dateStr > getToday();
}

/**
 * Fold consecutive exercises that share a superset_group label into a wrapper.
 *
 * Returns a list of items, each either:
 *   - { kind: 'single', exercise }                       — render as ExerciseItem
 *   - { kind: 'group', label, exercises: [...] }         — render via SupersetGroup
 *
 * Exercises without `superset_group` are emitted as singles. A run is broken
 * by either a different label or a label-less exercise, so two separate
 * "A" groups in the same block stay separate (LLMs should re-label, but we
 * shouldn't merge them on the client).
 */
export function groupExercises(exercises) {
    const items = [];
    let current = null;

    for (const ex of exercises) {
        const label = ex.superset_group;
        if (label && current && current.kind === 'group' && current.label === label) {
            current.exercises.push(ex);
            continue;
        }
        if (label) {
            current = { kind: 'group', label, exercises: [ex] };
        } else {
            current = { kind: 'single', exercise: ex };
        }
        items.push(current);
    }

    return items;
}

/**
 * Format exercise target for display
 */
export function formatTarget(exercise) {
    switch (exercise.type) {
        case 'strength':
        case 'circuit':
            if (exercise.target_sets && exercise.target_reps) {
                return `${exercise.target_sets} x ${exercise.target_reps}`;
            }
            return exercise.target_reps || exercise.target_sets || '';
        case 'duration':
            return `${exercise.target_duration_min} min`;
        case 'checklist':
            return `${exercise.items?.length || 0} items`;
        case 'weighted_time':
            return `${exercise.target_duration_sec || 60}s`;
        case 'interval': {
            const fmt = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
            const { rounds, work_duration_sec: work, rest_duration_sec: rest } = exercise;
            if (rounds && work && rest) return `${rounds} × ${fmt(work)}/${fmt(rest)}`;
            if (rounds && work) return `${rounds} × ${fmt(work)}`;
            if (rounds) return `${rounds} rounds`;
            if (exercise.target_duration_min) return `${exercise.target_duration_min} min`;
            return '';
        }
        default:
            return '';
    }
}

/**
 * Progress indicator for an exercise row header. Returns { display, complete }
 * or null when the exercise type has no meaningful "N of M" reading.
 *  - strength / circuit / weighted_time: completed sets / target sets
 *  - checklist: completed items / total items
 *  - duration (cardio): ✓ when duration_min is logged
 */
export function getExerciseProgress(exercise, logData) {
    const data = logData || {};
    switch (exercise.type) {
        case 'strength':
        case 'circuit':
        case 'weighted_time': {
            const target = exercise.target_sets || 0;
            if (!target) return null;
            const done = (data.sets || []).filter(s => s && s.completed === true).length;
            return { display: `${done}/${target}`, complete: done >= target };
        }
        case 'checklist': {
            const target = exercise.items?.length || 0;
            if (!target) return null;
            const done = (data.completed_items || []).length;
            return { display: `${done}/${target}`, complete: done >= target };
        }
        case 'duration':
        case 'interval': {
            if (data.duration_min != null && data.duration_min !== '') {
                return { display: '\u2713', complete: true };
            }
            return null;
        }
        default:
            return null;
    }
}

/**
 * Check if an exercise is completed based on log data
 */
export function isExerciseCompleted(exercise, logData) {
    if (!logData) return false;

    switch (exercise.type) {
        case 'checklist':
            const completed = logData.completed_items || [];
            return completed.length === (exercise.items?.length || 0);
        case 'strength':
            const sets = logData.sets || [];
            return sets.length >= (exercise.target_sets || 1);
        case 'duration':
        case 'interval':
            return logData.duration_min != null;
        default:
            return logData.completed === true;
    }
}
