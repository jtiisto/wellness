package dev.jtiisto.wellness.core.data.analysis

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The event channel's buffer, which is not what its name would suggest.
 *
 * `Channel.BUFFERED` paired with a non-suspending overflow policy resolves to a
 * channel of capacity **one**, so posting a second event evicts the first before
 * anyone has read it. That is a silent failure — the snackbar simply never
 * appears — and the capacity is written out explicitly to prevent it.
 */
class AnalysisEventsTest {

    @Test
    @DisplayName("events queued before anything is listening all survive")
    fun bufferHoldsMoreThanOneUndeliveredEvent() = runTest {
        val events = AnalysisEvents()

        events.post(AnalysisEvent.DeleteSuccess)
        events.post(AnalysisEvent.AdoptedRunning)
        events.post(AnalysisEvent.SubmitOffline)

        val received = mutableListOf<AnalysisEvent>()
        backgroundScope.launch { events.events.collect { received += it } }
        runCurrent()

        assertEquals(
            listOf(
                AnalysisEvent.DeleteSuccess,
                AnalysisEvent.AdoptedRunning,
                AnalysisEvent.SubmitOffline,
            ),
            received,
            "a capacity-one buffer would have dropped the first two before the screen composed",
        )
    }

    @Test
    @DisplayName("each event is delivered exactly once")
    fun eventsAreConsumedOnce() = runTest {
        val events = AnalysisEvents()
        val received = mutableListOf<AnalysisEvent>()
        backgroundScope.launch { events.events.collect { received += it } }
        runCurrent()

        events.post(AnalysisEvent.DeleteSuccess)
        runCurrent()
        events.post(AnalysisEvent.DeleteSuccess)
        runCurrent()

        assertEquals(2, received.size, "two deletes are two snackbars, not one deduplicated state")
    }

    @Test
    @DisplayName("every event carries the copy the snackbar shows")
    fun eventCopy() {
        assertEquals("Report deleted.", AnalysisEvent.DeleteSuccess.message)
        assertEquals(
            "A query was already running — showing it.",
            AnalysisEvent.AdoptedRunning.message,
        )
        assertEquals(
            "Server unreachable — new queries unavailable offline.",
            AnalysisEvent.SubmitOffline.message,
        )
        assertEquals("Report not available offline.", AnalysisEvent.ReportUnavailableOffline.message)
        assertEquals("Report was deleted on the server.", AnalysisEvent.ReportDeletedRemotely.message)
        // The three that carry the server's own words pass them through verbatim.
        assertEquals("Report not found", AnalysisEvent.ReportError("Report not found").message)
        assertEquals("boom", AnalysisEvent.SubmitError("boom").message)
        assertEquals("boom", AnalysisEvent.DeleteError("boom").message)
    }
}
