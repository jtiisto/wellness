package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The ongoing notification's text, which is the only surface a capture has while
 * the app is closed. It has to answer "is this still working?" at a glance, and
 * getting that wrong is invisible until someone is standing in a gym.
 */
class HrCaptureNotificationTextTest {

    private fun state(
        connectionState: ConnectionState,
        bpm: Int? = null,
        deviceName: String? = null,
        deviceAddress: String? = null,
        isStreamStale: Boolean = false,
    ) = HrCaptureState(
        isRunning = true,
        connectionState = connectionState,
        bpm = bpm,
        deviceName = deviceName,
        deviceAddress = deviceAddress,
        isStreamStale = isStreamStale,
    )

    @Test
    @DisplayName("a connected strap shows the live number")
    fun connectedWithBpm() {
        assertEquals("Connected — 142 bpm", HrCaptureNotificationText.contentText(state(ConnectionState.CONNECTED, bpm = 142)))
    }

    @Test
    @DisplayName("connected but not yet reporting says so without inventing a number")
    fun connectedWithoutBpm() {
        assertEquals("Connected", HrCaptureNotificationText.contentText(state(ConnectionState.CONNECTED)))
    }

    @Test
    @DisplayName("a BPM left over from before a dropout is never shown as live")
    fun bpmOnlyAppearsWhenConnected() {
        // The worst thing this line could do: a stale reading is
        // indistinguishable from a working strap.
        for (connectionState in ConnectionState.entries - ConnectionState.CONNECTED) {
            val text = HrCaptureNotificationText.contentText(state(connectionState, bpm = 142))
            assertEquals(false, text.contains("142"), "$connectionState leaked a stale bpm")
        }
    }

    @Test
    @DisplayName("a connected strap that stopped sending says so instead of showing its last number")
    fun connectedButSilent() {
        // While the app is closed this line is the only thing that can report
        // the failure, and "Connected — 142 bpm" over a dead stream is
        // indistinguishable from a working strap.
        assertEquals(
            "Connected — no data",
            HrCaptureNotificationText.contentText(
                state(ConnectionState.CONNECTED, bpm = 142, isStreamStale = true),
            ),
        )
    }

    @Test
    @DisplayName("every connection state has its own line")
    fun everyStateIsDescribed() {
        val lines = ConnectionState.entries.map { HrCaptureNotificationText.contentText(state(it)) }

        assertEquals(lines.size, lines.distinct().size)
        assertEquals(
            listOf("Disconnected", "Scanning…", "Connecting…", "Connected", "Reconnecting…"),
            lines,
        )
    }

    @Test
    @DisplayName("the sub-text names the strap, falling back to its address")
    fun subTextIdentifiesTheStrap() {
        assertEquals(
            "HRM-Pro",
            HrCaptureNotificationText.subText(state(ConnectionState.CONNECTED, deviceName = "HRM-Pro", deviceAddress = "AA:BB")),
        )
        assertEquals(
            "AA:BB",
            HrCaptureNotificationText.subText(state(ConnectionState.CONNECTED, deviceAddress = "AA:BB")),
        )
        assertEquals(
            "AA:BB",
            HrCaptureNotificationText.subText(state(ConnectionState.CONNECTED, deviceName = "  ", deviceAddress = "AA:BB")),
        )
    }

    @Test
    @DisplayName("before a device is known there is no sub-text at all")
    fun subTextIsAbsentWithoutADevice() {
        assertNull(HrCaptureNotificationText.subText(HrCaptureState()))
    }

    @Test
    @DisplayName("the title is fixed — the strap belongs in the body, where it can change")
    fun titleIsConstant() {
        assertEquals("Heart rate capture", HrCaptureNotificationText.TITLE)
    }
}
