package dev.jtiisto.wellness.core.ble.capture

import dev.jtiisto.wellness.core.ble.model.ConnectionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What a connection-state change should do to the inactivity countdown. */
enum class InactivityAction {
    /** Start the countdown if it is not already running; leave a running one alone. */
    ARM,

    /** Stop it — the link is alive. */
    CANCEL,

    /** Neither: the state is a transient step on the way to one of the above. */
    LEAVE,
}

/**
 * When a capture with no data coming in should give up on itself.
 *
 * The case this exists for is a strap that goes flat, or is taken off and left
 * in a bag: the connection drops, the backoff runs its fifteen attempts, and
 * without this the service would sit in the foreground holding a wake lock until
 * the user noticed. Five minutes is long enough to survive walking out of range
 * mid-session and short enough that a dead strap costs a bounded amount of
 * battery.
 *
 * Arming on [ConnectionState.RECONNECTING] as well as [ConnectionState.DISCONNECTED]
 * is the point: the backoff *is* the failure, and waiting for it to exhaust
 * itself before starting the clock would double the timeout.
 *
 * [ConnectionState.SCANNING] and [ConnectionState.CONNECTING] are [InactivityAction.LEAVE]
 * rather than [InactivityAction.CANCEL]: a reconnect passes through CONNECTING
 * on every attempt, so cancelling there would let a strap that connects and
 * immediately drops loop forever with the countdown reset each time round.
 */
object InactivityPolicy {

    val TIMEOUT: Duration = 5.minutes

    fun actionFor(state: ConnectionState): InactivityAction = when (state) {
        ConnectionState.DISCONNECTED, ConnectionState.RECONNECTING -> InactivityAction.ARM
        ConnectionState.CONNECTED -> InactivityAction.CANCEL
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> InactivityAction.LEAVE
    }
}
