/**
 * SyncScheduler decision logic — PURE functions extracted from the scheduler so
 * the retry/backoff math and the outcome handling are unit-testable without
 * timers, DOM events, or storage (test/js/sync-scheduler-logic.test.js).
 */

/**
 * Exponential backoff with a ceiling: base * 2^attempt, capped at maxMs.
 */
export function computeRetryDelay(attempt, baseMs, maxMs) {
    return Math.min(baseMs * Math.pow(2, attempt), maxMs);
}

/**
 * Decide what the scheduler does with a syncFn result:
 *   'reset' — success (or journal's handled-conflicts case): clear retry state
 *   'skip'  — not an error (offline / already syncing): do nothing
 *   'error' — a failure carrying an error object: classify + maybe toast + retry
 *   'retry' — generic failure: schedule a silent retry
 */
export function classifySyncOutcome(result) {
    if (result.success || result.reason === 'conflicts') return 'reset';
    if (result.reason === 'offline' || result.reason === 'already syncing') return 'skip';
    if (result.error) return 'error';
    return 'retry';
}
