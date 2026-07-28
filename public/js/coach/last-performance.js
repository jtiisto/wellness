/**
 * Pure lookup: the previous time a given exercise (matched by canonical_slug,
 * across ANY past workout in the synced window) was actually performed.
 *
 * Optionally narrowed to one `exposure` (HEAVY / LIGHT / … — several exposures
 * of a movement deliberately share one canonical_slug). The exposure is read
 * from the HISTORICAL PLAN of each past date, not from log data: plans in the
 * synced window carry it, while the lean sync log shape stays wire-invariant.
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

/**
 * The plan exercise whose canonical_slug matches, as { id, exposure }, or null.
 * `exposure` is null when the plan entry carries none (the common case).
 */
function planExerciseForSlug(plan, canonicalSlug) {
    if (!plan || !Array.isArray(plan.blocks)) return null;
    for (const block of plan.blocks) {
        for (const ex of (block.exercises || [])) {
            if (ex.canonical_slug === canonicalSlug) {
                return { id: ex.id, exposure: ex.exposure ?? null };
            }
        }
    }
    return null;
}

/**
 * Most recent session strictly before refDate where this exercise was actually
 * logged with set data. Returns { date, sets } (sets filtered to those with
 * data, set_num preserved) or null.
 *
 * With an `exposure`, the most recent SAME-exposure session wins even over a
 * newer session of another exposure — progression chains run per exposure. The
 * newest any-exposure session is kept as a fallback so a brand-new exposure key
 * (and pre-epoch history, which carries none) still shows something; the date
 * hint gives the context. Both sides are server-normalized, so `===` suffices.
 */
export function findLastPerformance(canonicalSlug, refDate, workoutPlans, workoutLogs, exposure = null) {
    if (!canonicalSlug || !refDate || !workoutPlans) return null;
    const dates = Object.keys(workoutPlans)
        .filter(d => d < refDate)
        .sort()
        .reverse(); // newest first
    let fallback = null; // newest match of any exposure, used only if the requested one has no history
    for (const date of dates) {
        const planEx = planExerciseForSlug(workoutPlans[date], canonicalSlug);
        if (!planEx) continue;
        const log = workoutLogs && workoutLogs[date] && workoutLogs[date][planEx.id];
        const sets = (log && Array.isArray(log.sets) ? log.sets : []).filter(setHasData);
        if (sets.length === 0) continue;
        if (!exposure || planEx.exposure === exposure) return { date, sets };
        if (!fallback) fallback = { date, sets };
    }
    return fallback;
}

/** "2026-06-01" -> "Jun 1" (parses parts, no Date/TZ shift). */
export function formatShortDate(isoDate) {
    if (!isoDate) return '';
    const [, m, d] = isoDate.split('-').map(Number);
    if (!m || !d) return isoDate;
    return `${MONTHS[m - 1]} ${d}`;
}
