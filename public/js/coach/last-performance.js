/**
 * Pure lookup: the previous time a given exercise (matched by canonical_slug,
 * across ANY past workout in the synced window) was actually performed.
 *
 * No DOM/Preact deps so it's unit-testable under node:test. Dates are
 * "YYYY-MM-DD" strings — lexicographic comparison is correct and TZ-safe.
 */

const SET_DATA_FIELDS = ['weight', 'reps', 'rpe', 'duration_sec'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A set counts as performed if it carries any real metric. */
export function setHasData(s) {
    return s != null && SET_DATA_FIELDS.some(k => s[k] != null);
}

/** The exercise_key in a plan whose canonical_slug matches, or null. */
function exerciseKeyForSlug(plan, canonicalSlug) {
    if (!plan || !Array.isArray(plan.blocks)) return null;
    for (const block of plan.blocks) {
        for (const ex of (block.exercises || [])) {
            if (ex.canonical_slug === canonicalSlug) return ex.id;
        }
    }
    return null;
}

/**
 * Most recent session strictly before refDate where this exercise was actually
 * logged with set data. Returns { date, sets } (sets filtered to those with
 * data, set_num preserved) or null.
 */
export function findLastPerformance(canonicalSlug, refDate, workoutPlans, workoutLogs) {
    if (!canonicalSlug || !refDate || !workoutPlans) return null;
    const dates = Object.keys(workoutPlans)
        .filter(d => d < refDate)
        .sort()
        .reverse(); // newest first
    for (const date of dates) {
        const exId = exerciseKeyForSlug(workoutPlans[date], canonicalSlug);
        if (!exId) continue;
        const log = workoutLogs && workoutLogs[date] && workoutLogs[date][exId];
        const sets = (log && Array.isArray(log.sets) ? log.sets : []).filter(setHasData);
        if (sets.length > 0) return { date, sets };
    }
    return null;
}

/** "2026-06-01" -> "Jun 1" (parses parts, no Date/TZ shift). */
export function formatShortDate(isoDate) {
    if (!isoDate) return '';
    const [, m, d] = isoDate.split('-').map(Number);
    if (!m || !d) return isoDate;
    return `${MONTHS[m - 1]} ${d}`;
}
