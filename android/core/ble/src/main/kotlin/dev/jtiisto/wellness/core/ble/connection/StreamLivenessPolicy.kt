package dev.jtiisto.wellness.core.ble.connection

/**
 * When a strap that is still connected has stopped being a heart-rate monitor.
 *
 * A GATT link reports its own death reliably; a *notification subscription* does
 * not. It can be killed by firmware that hangs, by a CCCD the peer quietly
 * forgets, or by skin contact lost while the radio link holds — and every one of
 * those leaves a connection the platform is perfectly happy with, delivering
 * nothing. Without this, the last beat received stays on screen as the current
 * heart rate for as long as the capture is left running.
 *
 * The rule is one sentence: **a link is only alive while data flows**, which is
 * the same sentence the ported first-sample watchdog was already enforcing for
 * the first ten seconds of a connection. This generalises it to every second
 * after that, so the two questions differ only in which clock applies.
 *
 * Pure, because the two things that can be wrong here — the numbers, and the
 * arithmetic that decides when they have elapsed — must be testable off a
 * device. The timer that consumes it lives in [GarminHrmConnection], with the
 * rest of the framework glue.
 */
object StreamLivenessPolicy {

    /**
     * How long a fresh connection has to produce its first sample.
     *
     * Discovery, the CCCD write and notification delivery can each fail *after*
     * `STATE_CONNECTED`, and a link that is going to work proves it within about
     * a second of the descriptor acknowledgement. Ten is roughly tenfold margin
     * on a link that has proven nothing, and short enough to keep the retry loop
     * worth having. Ported value, unchanged.
     */
    const val FIRST_SAMPLE_TIMEOUT_MS = 10_000L

    /**
     * How long a *proven* link may go quiet before it is declared dead.
     *
     * Deliberately longer than [FIRST_SAMPLE_TIMEOUT_MS]: a link that has been
     * delivering has earned more patience than one that never started. Straps
     * notify at about 1 Hz and this package already treats three seconds of
     * silence as a discontinuity (`GAP_THRESHOLD_MS`), so fifteen is five
     * consecutive discontinuity windows — far outside anything sub-second jitter
     * or a brief radio hiccup can produce, which is the thing that must not make
     * the tone flap, and far inside the harm, which is a reading that freezes for
     * tens of seconds or forever.
     *
     * The other half of the argument is what this watchdog is *for*: a link that
     * genuinely drops reports itself through the BLE supervision timeout within a
     * few seconds, so this timer is only ever the net for silence the link layer
     * does not report. Being a few seconds slower to catch that costs nothing,
     * while tearing down a healthy link on a ten-second hiccup costs real beats.
     */
    const val STALE_AFTER_MS = 15_000L

    /**
     * Which clock the link is being held to right now.
     *
     * @param hasDelivered whether this connection has produced a sample yet.
     *   Per connection, not per capture: a reconnect is unproven again.
     */
    fun timeoutMs(
        hasDelivered: Boolean,
        firstSampleTimeoutMs: Long = FIRST_SAMPLE_TIMEOUT_MS,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Long = if (hasDelivered) staleAfterMs else firstSampleTimeoutMs

    /**
     * Whether a stream last heard from [silenceMs] ago is dead.
     *
     * Exactly at the threshold counts as stale. The countdown re-reads the real
     * time of the last sample on every wake, so a boundary that resolved to
     * "not yet" would compute a zero-length wait and spin.
     *
     * A negative [silenceMs] is not stale. It cannot arise from the elapsed-time
     * source this is called with, and reading a clock that went backwards as a
     * dead strap is the one interpretation that would be actively wrong.
     */
    fun isStale(silenceMs: Long, timeoutMs: Long): Boolean = silenceMs >= timeoutMs

    /**
     * How long to wait before asking again, never negative.
     *
     * Zero means the answer is already known and the caller must not delay on it
     * — [isStale] is the question, this is only the sleep.
     */
    fun remainingMs(silenceMs: Long, timeoutMs: Long): Long =
        (timeoutMs - silenceMs).coerceAtLeast(0L)
}
