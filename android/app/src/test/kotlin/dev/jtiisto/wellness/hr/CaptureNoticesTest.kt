package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which capture-state changes reach the snackbar.
 *
 * The service publishes state, not events, and republishes it on every sample —
 * so the interesting property is not that a fault is reported but that it is
 * reported *once*. Pulse-bridge's equivalent wrote `state.error` and nothing
 * read it; the failure mode on this side is the opposite one.
 */
class CaptureNoticesTest {

    private val notices = CaptureNotices()

    @Test
    @DisplayName("a healthy capture says nothing")
    fun silentWhenNothingIsWrong() {
        assertNull(
            notices.noticeFor(
                HrCaptureState(isRunning = true, connectionState = ConnectionState.CONNECTED, bpm = 132),
            ),
        )
    }

    @Test
    @DisplayName("the first republish of a fault is the one that speaks")
    fun reportsAFaultOnce() {
        val faulted = HrCaptureState(
            isRunning = true,
            connectionState = ConnectionState.RECONNECTING,
            detail = "Connect attempt 2 failed — retrying",
        )

        assertEquals(
            "${CaptureNoticeCopy.PREFIX}Connect attempt 2 failed — retrying",
            notices.noticeFor(faulted),
        )
        // Every sample and every backoff tick republishes the same state.
        assertNull(notices.noticeFor(faulted))
        assertNull(notices.noticeFor(faulted.copy(bpm = 131)))
    }

    @Test
    @DisplayName("a different fault is different news")
    fun reportsEachDistinctFault() {
        val running = HrCaptureState(isRunning = true)

        assertEquals(
            "${CaptureNoticeCopy.PREFIX}Connect attempt 2 failed — retrying",
            notices.noticeFor(running.copy(detail = "Connect attempt 2 failed — retrying")),
        )
        assertEquals(
            "${CaptureNoticeCopy.PREFIX}Unable to connect after 15 attempts",
            notices.noticeFor(running.copy(detail = "Unable to connect after 15 attempts")),
        )
    }

    @Test
    @DisplayName("a fault that clears and returns is reported again")
    fun recoveryRearmsTheNotice() {
        val running = HrCaptureState(isRunning = true)
        val detail = "Connect attempt 2 failed — retrying"

        notices.noticeFor(running.copy(detail = detail))
        // Reconnected: the detail goes away.
        assertNull(notices.noticeFor(running))
        // And the strap drops again. The second drop is worth hearing about.
        assertTrue(notices.noticeFor(running.copy(detail = detail)) != null)
    }

    @Test
    @DisplayName("a capture that has stopped reports nothing, and forgets what it reported")
    fun stoppedCaptureIsSilentAndResets() {
        val detail = "Unable to connect after 15 attempts"
        notices.noticeFor(HrCaptureState(isRunning = true, detail = detail))

        // The service clears the whole state on teardown; a detail left on a
        // stopped capture is not a live fault either.
        assertNull(notices.noticeFor(HrCaptureState(isRunning = false, detail = detail)))

        // The next session hitting the same wall is news a second time.
        assertEquals(
            "${CaptureNoticeCopy.PREFIX}$detail",
            notices.noticeFor(HrCaptureState(isRunning = true, detail = detail)),
        )
    }

    @Test
    @DisplayName("blank detail is not a fault")
    fun blankDetailIsNothing() {
        assertNull(notices.noticeFor(HrCaptureState(isRunning = true, detail = "   ")))
    }

    // ---- a start the platform refused outright ------------------------------

    @Test
    @DisplayName("each refusal says something different, and each says what to do about it")
    fun startRefusalsAreDistinct() {
        val texts = CaptureStartRefusal.entries.map(CaptureNoticeCopy::startRefused)

        assertEquals(texts.size, texts.distinct().size)
        assertTrue(texts.all { it.startsWith(CaptureNoticeCopy.PREFIX) })
        assertTrue(texts.none { it.isBlank() })
    }

    @Test
    @DisplayName("a background refusal is worth retrying; a permission one is not")
    fun startRefusalsSayWhatIsWrong() {
        val notAllowed = CaptureNoticeCopy.startRefused(CaptureStartRefusal.NOT_ALLOWED)
        val permission = CaptureNoticeCopy.startRefused(CaptureStartRefusal.PERMISSION)

        assertTrue(notAllowed.contains("Try again"))
        assertTrue(permission.contains("permission"))
        // The text is authored per refusal kind, never forwarded from the
        // exception — the same permitted-field rule the detail path follows.
        assertTrue(!permission.contains("Exception"))
        assertTrue(!notAllowed.contains("Exception"))
    }

    @Test
    @DisplayName("the message names the subsystem, because the channel is shared with sync")
    fun messageIsPrefixed() {
        val notice = notices.noticeFor(HrCaptureState(isRunning = true, detail = "strap not advertising"))

        assertTrue(notice!!.startsWith(CaptureNoticeCopy.PREFIX))
        // And never the sync prefix, which would name the wrong thing entirely.
        assertTrue(!notice.startsWith("Sync Failed"))
    }
}
