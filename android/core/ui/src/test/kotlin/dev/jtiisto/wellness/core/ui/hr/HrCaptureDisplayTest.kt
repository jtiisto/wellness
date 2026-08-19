package dev.jtiisto.wellness.core.ui.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.quality.SignalQuality
import dev.jtiisto.wellness.core.ble.quality.SignalQualityLevel
import dev.jtiisto.wellness.core.ui.theme.LiveSignalDark
import dev.jtiisto.wellness.core.ui.theme.LiveSignalLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the BPM chip and the capture sheet show.
 *
 * The null return is the chip's visibility rule, so it is tested as one: the
 * spec says there is no chip when idle, and this is the single place that
 * decides it for both the coach top bar and the strap section.
 */
class HrCaptureDisplayTest {

    @Test
    @DisplayName("nothing capturing, nothing to draw — the chip's whole visibility rule")
    fun hiddenWhenIdle() {
        assertNull(hrCaptureDisplay(HrCaptureState()))
        // Even holding a device and a reading: a state left over from a finished
        // session must not put a ghost chip back on screen.
        assertNull(
            hrCaptureDisplay(
                HrCaptureState(isRunning = false, bpm = 132, deviceName = "HRM-Pro"),
            ),
        )
    }

    @Test
    @DisplayName("a running capture is shown, reading or not")
    fun shownWhileRunning() {
        val display = requireNotNull(hrCaptureDisplay(HrCaptureState(isRunning = true)))

        // Before the first beat: a placeholder, never a zero — zero is a heart
        // rate, and a wrong one.
        assertEquals(HrCaptureCopy.NO_READING, display.bpmText)
    }

    @Test
    @DisplayName("the BPM is the number the strap sent")
    fun bpmText() {
        val display = hrCaptureDisplay(HrCaptureState(isRunning = true, bpm = 132))

        assertEquals("132", display?.bpmText)
    }

    @Test
    @DisplayName("one tone→colour table, and it is the live-signal exception's own")
    fun toneColours() {
        // The dot's colour is the design's documented live-signal exception —
        // the one thing on the page that is measured rather than judged — so
        // the mapping is pinned here rather than left to a composable only a
        // device could inspect.
        for (colors in listOf(LiveSignalLight, LiveSignalDark)) {
            assertEquals(colors.live, HrTone.LIVE.colorOn(colors))
            assertEquals(colors.waiting, HrTone.WAITING.colorOn(colors))
            assertEquals(colors.attention, HrTone.LOST.colorOn(colors))
        }
    }

    @Test
    @DisplayName("connected is live; connecting, reconnecting and scanning are all waiting; disconnected is lost")
    fun toneFollowsTheConnection() {
        fun toneFor(state: ConnectionState) =
            hrCaptureDisplay(HrCaptureState(isRunning = true, connectionState = state))?.tone

        assertEquals(HrTone.LIVE, toneFor(ConnectionState.CONNECTED))
        assertEquals(HrTone.WAITING, toneFor(ConnectionState.CONNECTING))
        assertEquals(HrTone.WAITING, toneFor(ConnectionState.RECONNECTING))
        assertEquals(HrTone.WAITING, toneFor(ConnectionState.SCANNING))
        assertEquals(HrTone.LOST, toneFor(ConnectionState.DISCONNECTED))
    }

    @Test
    @DisplayName("every connection state has its own words")
    fun everyConnectionStateReads() {
        val texts = ConnectionState.entries.map {
            hrCaptureDisplay(HrCaptureState(isRunning = true, connectionState = it))?.connectionText
        }

        assertEquals(texts.size, texts.distinct().size)
        assertTrue(texts.none { it.isNullOrBlank() })
    }

    @Test
    @DisplayName("the device falls back to its address, and then to a generic name")
    fun deviceNameFallsBack() {
        fun nameFor(name: String?, address: String?) = hrCaptureDisplay(
            HrCaptureState(isRunning = true, deviceName = name, deviceAddress = address),
        )?.deviceName

        assertEquals("HRM-Pro", nameFor("HRM-Pro", "AA:BB"))
        assertEquals("AA:BB", nameFor(null, "AA:BB"))
        assertEquals("AA:BB", nameFor("  ", "AA:BB"))
        assertEquals(HrCaptureCopy.UNKNOWN_DEVICE, nameFor(null, null))
    }

    @Test
    @DisplayName("signal quality is silent until it has measured something")
    fun measuringSaysNothing() {
        val display = hrCaptureDisplay(
            HrCaptureState(
                isRunning = true,
                signalQuality = SignalQuality(SignalQualityLevel.MEASURING, rrCoveragePercent = 0),
            ),
        )

        // "Measuring" beside a live BPM reads as a fault when it only means the
        // first few seconds have not passed.
        assertNull(display?.qualityText)
    }

    @Test
    @DisplayName("a measured quality names the level and the RR coverage behind it")
    fun qualityText() {
        fun textFor(level: SignalQualityLevel) = hrCaptureDisplay(
            HrCaptureState(
                isRunning = true,
                signalQuality = SignalQuality(level, rrCoveragePercent = 96),
            ),
        )?.qualityText

        assertEquals("Good signal · 96% RR coverage", textFor(SignalQualityLevel.GOOD))
        assertEquals("Fair signal · 96% RR coverage", textFor(SignalQualityLevel.FAIR))
        assertEquals("Poor signal · 96% RR coverage", textFor(SignalQualityLevel.POOR))
    }

    @Test
    @DisplayName("the connect diagnostics come through, and blank text does not")
    fun detailPassesThrough() {
        fun detailFor(detail: String?) =
            hrCaptureDisplay(HrCaptureState(isRunning = true, detail = detail))?.detail

        assertEquals("Connect attempt 2 failed — retrying", detailFor("Connect attempt 2 failed — retrying"))
        assertNull(detailFor(null))
        assertNull(detailFor("   "))
    }

    @Test
    @DisplayName("the chip's accessible name carries what its digits cannot")
    fun chipDescription() {
        val display = requireNotNull(
            hrCaptureDisplay(
                HrCaptureState(isRunning = true, bpm = 132, connectionState = ConnectionState.CONNECTED),
            ),
        )

        assertEquals("Heart rate 132, Connected", HrCaptureCopy.chipDescription(display))
    }

    @Test
    @DisplayName("the ellipsis is for the eye, not for a screen reader")
    fun chipDescriptionDropsTheEllipsis() {
        val display = requireNotNull(
            hrCaptureDisplay(
                HrCaptureState(isRunning = true, connectionState = ConnectionState.RECONNECTING),
            ),
        )

        assertEquals("Heart rate —, Reconnecting", HrCaptureCopy.chipDescription(display))
    }
}
