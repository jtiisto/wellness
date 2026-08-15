package dev.jtiisto.wellness.core.data.analysis

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Something the user should be told once.
 *
 * Every message the Analysis module can raise is enumerated here rather than
 * assembled at the callsite, so the copy is reviewable in one place and a
 * server `detail` is never mistaken for a sentence we wrote.
 */
sealed interface AnalysisEvent {

    /** What to put on the snackbar. */
    val message: String

    /** The server could not be reached, so there is nothing to submit to. */
    data object SubmitOffline : AnalysisEvent {
        override val message = "Server unreachable — new queries unavailable offline."
    }

    /** The server refused the submit and said why; its words, verbatim. */
    data class SubmitError(val detail: String) : AnalysisEvent {
        override val message = detail
    }

    /** A 409 resolved into the report that was already running. */
    data object AdoptedRunning : AnalysisEvent {
        override val message = "A query was already running — showing it."
    }

    data object DeleteSuccess : AnalysisEvent {
        override val message = "Report deleted."
    }

    data class DeleteError(val detail: String) : AnalysisEvent {
        override val message = detail
    }

    /** Asked for a report we have never cached, with no way to fetch it. */
    data object ReportUnavailableOffline : AnalysisEvent {
        override val message = "Report not available offline."
    }

    /** The polled report's row is gone — deleted from another client. */
    data object ReportDeletedRemotely : AnalysisEvent {
        override val message = "Report was deleted on the server."
    }

    /**
     * A report fetch failed for a reason the offline copy cannot cover — a 404
     * on a History row, say, whose `detail` reads "Report not found".
     */
    data class ReportError(val detail: String) : AnalysisEvent {
        override val message = detail
    }
}

/**
 * The bridge from the store to the app's snackbar.
 *
 * A [Channel] rather than state, for the reason `SyncErrorEvents` is one: these
 * are events, and each must produce exactly one snackbar. Held as state, "Report
 * deleted." would re-announce itself on every rotation.
 *
 * The buffer drops its oldest entry rather than suspending. The store's control
 * context is single-threaded and drives the poll — it must never be parked
 * waiting for a UI that is not composed.
 *
 * The capacity is written out rather than left as `Channel.BUFFERED`, which is
 * not what it looks like: paired with a non-suspending overflow policy, that
 * constant resolves to a channel of capacity **one**, so a second event would
 * evict the first before anybody had read it. Two snackbars raised in the same
 * breath — "Report deleted." then a failed submit — would show only the second.
 */
class AnalysisEvents {

    private val channel = Channel<AnalysisEvent>(
        capacity = CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Consumed once. A second collector will not see an already-shown event. */
    val events: Flow<AnalysisEvent> = channel.receiveAsFlow()

    fun post(event: AnalysisEvent) {
        channel.trySend(event)
    }

    private companion object {
        /** Deep enough that a backgrounded app never loses a message it earned. */
        const val CAPACITY = 64
    }
}
