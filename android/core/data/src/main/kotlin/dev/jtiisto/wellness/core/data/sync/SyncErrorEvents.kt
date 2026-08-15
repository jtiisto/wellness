package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.network.describeForLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The bridge from a scheduler's server-classified failure to the app's snackbar.
 *
 * A [Channel] rather than a `StateFlow`: a failure is an **event**, and each one
 * must produce exactly one snackbar. Held as state it would re-show itself on
 * every recomposition and every rotation, long after the sync that failed.
 * Network errors never arrive here — the status indicator already says
 * "offline", and a toast per dropped connection is noise.
 */
class SyncErrorEvents {

    private val channel = Channel<String>(
        // Written out because `Channel.BUFFERED` is not what it looks like here:
        // paired with a non-suspending overflow policy it resolves to capacity
        // ONE, so a journal failure and a coach failure raised in the same burst
        // would show only the second. (Same fix as AnalysisEvents.)
        capacity = 64,
        // A burst of failures with nothing listening (the app is backgrounded)
        // must not suspend the scheduler; the newest message is the useful one.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Consumed once. A second collector will not see an already-shown message. */
    val messages: Flow<String> = channel.receiveAsFlow()

    /**
     * Described through [describeForLog], never from `Throwable.message`.
     *
     * The snackbar is the same disclosure as the debug dump and then some — it
     * appears unasked, over whatever tab the user is on. A Ktor
     * `ResponseException` carries the request URL and the response body in its
     * message, so a FastAPI 422 echoing the journal entry it rejected would put
     * that entry, and the private server address, on screen.
     */
    fun postServerError(error: Throwable) {
        channel.trySend("Sync Failed: ${describeForLog(error)}")
    }

    /**
     * Show [message] verbatim.
     *
     * Same channel, no prefix. Separate from [postServerError] because that one
     * is a statement about a *sync* and says so: a heart-rate capture that lost
     * its strap is not a sync and not a failure of one, and borrowing the prefix
     * would have the snackbar name the wrong subsystem.
     *
     * The caller owns the wording, and with it the debug-log permitted-field
     * policy — nothing derived from a `Throwable.message` (a Ktor response
     * exception's message is the whole response body) may be passed here.
     */
    fun postMessage(message: String) {
        channel.trySend(message)
    }
}
