package dev.jtiisto.wellness.core.data.sync

/**
 * What a module's sync function reports back to the scheduler.
 *
 * [reason] and [error] can both be set; [classifySyncOutcome] fixes the
 * precedence between them.
 */
data class SyncResult(
    val success: Boolean,
    val reason: SyncSkipReason? = null,
    val error: Throwable? = null,
)

/** Why a sync did not complete. Internal to the client — never serialized. */
enum class SyncSkipReason {
    CONFLICTS,
    OFFLINE,
    ALREADY_SYNCING,

    /** The JS "unknown reason" case: a named failure the scheduler retries. */
    OTHER,
}

/** What the scheduler does with a [SyncResult]. */
enum class SyncOutcome {
    /** Success (or journal's handled-conflicts case): clear retry state. */
    RESET,

    /** Not an error (offline / already syncing): touch nothing. */
    SKIP,

    /** A failure carrying an error object: classify, maybe surface, then retry. */
    ERROR,

    /** Generic failure: silent retry. */
    RETRY,
}

/**
 * Exponential backoff with a ceiling: `baseMs * 2^attempt`, capped at [maxMs].
 *
 * Saturating, unlike the JS original's float math: an attempt counter that ran
 * away (or a shift that would overflow `Long`) yields [maxMs] rather than a
 * negative or wrapped delay. Negative attempts clamp to attempt 0.
 */
fun computeRetryDelay(attempt: Int, baseMs: Long, maxMs: Long): Long {
    if (attempt >= Long.SIZE_BITS - 2) return maxMs
    val safeAttempt = attempt.coerceAtLeast(0)
    val factor = 1L shl safeAttempt
    val scaled = if (baseMs != 0L && factor > Long.MAX_VALUE / baseMs) Long.MAX_VALUE else baseMs * factor
    return minOf(scaled, maxMs)
}

/**
 * Decide what the scheduler does with a syncFn result. Ported 1:1 from
 * `sync-scheduler-logic.js`, including the precedence: a result that is
 * successful (or conflicted) resets even when it also carries an error.
 */
fun classifySyncOutcome(result: SyncResult): SyncOutcome {
    if (result.success || result.reason == SyncSkipReason.CONFLICTS) return SyncOutcome.RESET
    if (result.reason == SyncSkipReason.OFFLINE || result.reason == SyncSkipReason.ALREADY_SYNCING) {
        return SyncOutcome.SKIP
    }
    if (result.error != null) return SyncOutcome.ERROR
    return SyncOutcome.RETRY
}
