package dev.jtiisto.wellness.core.ble.connection

/**
 * More than three seconds without a notification is a discontinuity.
 *
 * One number with two consumers, and their boundaries differ by design:
 * - [GarminHrmConnection] gap-marks the next stored sample when the silence
 *   before it is **strictly greater** than this (`> 3000 ms`) — the definition
 *   in the *data*, unchanged from the port;
 * - [ReconnectNotice] treats an outage as news once it **reaches** this
 *   (`>= 3000 ms`, inclusive) — the convention [StreamLivenessPolicy.isStale]
 *   uses, so the notice timer's re-check cannot resolve to a zero-length wait.
 *
 * Sharing the constant keeps the two from drifting apart by more than that one
 * millisecond: an outage of exactly 3000 ms earns a notice while the record
 * calls it continuous. Nothing depends on the two agreeing at the boundary.
 */
const val GAP_THRESHOLD_MS: Long = 3_000L

/**
 * Whether a link loss in progress is news yet, and what to say if it is.
 *
 * On a loss the connection publishes `RECONNECTING` at once — the state is the
 * truth and the display rules depend on it — but the *detail text* waits. A
 * strap that drops and is back inside [GAP_THRESHOLD_MS] has cost the record
 * nothing it would mark, and telling the user "Connect attempt 1 failed" about
 * it, as the ported code did, claimed a failure that had not happened.
 *
 * Two things make it news, and nothing else does:
 * - **a connect attempt has actually failed** — the attempt's own diagnosis
 *   ([ConnectDiagnostics.retryDetail]) wins, whatever the clock says;
 * - **the outage has lasted [GAP_THRESHOLD_MS]** without a sample — the record
 *   now has a hole in it, and [ConnectDiagnostics.dropDetail] says so.
 *
 * Pure, so the rule is unit-tested rather than device-tested. The timer that
 * asks it lives in [GarminHrmConnection] with the rest of the framework glue,
 * on the elapsed-realtime clock: a wall-clock jump must never read as an outage.
 */
object ReconnectNotice {

    /**
     * The detail text for an outage [sinceLossMs] old, or null while it is not
     * news.
     *
     * @param failedAttempt the number of the connect attempt that failed,
     *   counted within the current outage (1 for the first `connectGatt()` of a
     *   session or after a drop) — *not* the backoff budget's count, which a
     *   drop advances without any attempt failing. Only read when
     *   [attemptFailed].
     * @param verdict the failed attempt's probe verdict, if it had one.
     * @param attemptFailed whether a connect attempt in this outage has in fact
     *   failed, as opposed to the backoff merely running.
     */
    fun detail(sinceLossMs: Long, failedAttempt: Int, verdict: String?, attemptFailed: Boolean): String? = when {
        attemptFailed -> ConnectDiagnostics.retryDetail(failedAttempt, verdict)
        isPastThreshold(sinceLossMs) -> ConnectDiagnostics.dropDetail()
        else -> null
    }

    /**
     * Whether an outage [sinceLossMs] old has outlasted the gap threshold.
     *
     * Exactly at the threshold counts as past it — the convention
     * [StreamLivenessPolicy.isStale] uses, for the same reason: the timer
     * re-asks on every wake, and a boundary that resolved to "not yet" would
     * compute a zero-length wait and spin. A negative age (a clock read out of
     * order) is not news.
     */
    fun isPastThreshold(sinceLossMs: Long): Boolean = sinceLossMs >= GAP_THRESHOLD_MS

    /**
     * How long to wait before asking again, never negative. Zero means the
     * answer is already known.
     */
    fun remainingMs(sinceLossMs: Long): Long = (GAP_THRESHOLD_MS - sinceLossMs).coerceAtLeast(0L)
}
