package dev.jtiisto.wellness.core.ble.connection

/**
 * What the advertising probe managed to observe during one connect attempt.
 *
 * [INACTIVE] and [UNAVAILABLE] are kept apart from each other even though they
 * produce the same verdict: the first means no probe was configured, the second
 * that one ran and could not listen. Both are "we learned nothing", and
 * collapsing them would lose the distinction the diagnostics log needs.
 */
enum class ProbeState {
    /** No probe configured, or the last one was torn down. */
    INACTIVE,

    /** Listening, and no advertisement heard yet. */
    LISTENING,

    /** At least one advertisement heard from the target address. */
    HEARD,

    /** A probe ran and could not scan — the verdict must stay agnostic. */
    UNAVAILABLE,
}

/**
 * The human-readable half of [GarminHrmConnection]: turning a probe outcome and
 * an attempt count into the text the notification and the strap sheet show.
 *
 * Pulled out of the connection deliberately. The connection is device-only glue
 * and excluded from the coverage gate, but *which failure the user is told about*
 * is a real decision with a real way to get it wrong — claiming "strap not
 * advertising" when the probe never listened would send someone hunting a
 * hardware fault that does not exist.
 */
object ConnectDiagnostics {

    /**
     * The verdict for a connect attempt that timed out, or null when the probe
     * has nothing to say.
     *
     * Null is not a fallback: silence is the only honest answer when nothing was
     * heard *and* nothing was listening. Only [ProbeState.LISTENING] — a probe
     * that ran, scanned, and heard nothing — earns the "not advertising" claim.
     */
    fun verdict(probe: ProbeState, rssi: Int?): String? = when (probe) {
        ProbeState.HEARD -> "strap is advertising, rssi=$rssi — connection failed or rejected"
        ProbeState.LISTENING -> "strap not advertising — likely held by another device (watch/ANT+) or asleep"
        ProbeState.INACTIVE, ProbeState.UNAVAILABLE -> null
    }

    /** Progress text while the backoff is still running. */
    fun retryDetail(attempt: Int, verdict: String?): String =
        "Connect attempt $attempt failed${suffix(verdict)} — retrying"

    /** Terminal text once the attempt budget is spent. */
    fun giveUpDetail(attempts: Int, verdict: String?): String =
        "Unable to connect after $attempts attempts${suffix(verdict)}"

    private fun suffix(verdict: String?): String = verdict?.let { " ($it)" }.orEmpty()
}
