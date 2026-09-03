package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.model.BleDevice
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The strap section's decisions, away from the composable that renders them. */
class StrapLogicTest {

    private fun found(address: String, name: String? = "HRM-Pro", rssi: Int = -55) =
        DiscoveredDevice(BleDevice(address = address, name = name), rssi)

    // ---- the discovered list ----------------------------------------------

    @Test
    @DisplayName("a new advertisement is appended in the order it was first heard")
    fun appendsNewDevices() {
        val list = StrapLogic.merge(
            StrapLogic.merge(emptyList(), found("AA:01")),
            found("AA:02"),
        )

        assertEquals(listOf("AA:01", "AA:02"), list.map { it.address })
    }

    @Test
    @DisplayName("a device heard again is replaced where it stands, never moved")
    fun replacesInPlace() {
        // A strap advertises several times a second and its RSSI swings; a list
        // that re-sorted on it would reorder under the user's finger.
        val list = listOf(found("AA:01", rssi = -55), found("AA:02", rssi = -80))
            .let { StrapLogic.merge(it, found("AA:02", rssi = -40)) }

        assertEquals(listOf("AA:01", "AA:02"), list.map { it.address })
        assertEquals(-40, list[1].rssi)
    }

    @Test
    @DisplayName("a strap already paired is not offered a second time in the scan list")
    fun pairedDevicesAreFilteredOut() {
        val discovered = listOf(found("AA:01"), found("AA:02"))
        val known = listOf(KnownDevice("AA:01", "HRM-Pro"))

        assertEquals(listOf("AA:02"), StrapLogic.unpaired(discovered, known).map { it.address })
    }

    @Test
    @DisplayName("with nothing paired, everything found is a candidate")
    fun nothingPairedOffersEverything() {
        val discovered = listOf(found("AA:01"), found("AA:02"))

        assertEquals(discovered, StrapLogic.unpaired(discovered, emptyList()))
    }

    @Test
    @DisplayName("an unnamed advertisement is still connectable, and never renders blank")
    fun unnamedDeviceGetsALabel() {
        assertEquals("HRM-Pro", StrapLogic.displayName(found("AA:01")))
        assertEquals(StrapCopy.UNNAMED, StrapLogic.displayName(found("AA:01", name = null)))
        assertEquals(StrapCopy.UNNAMED, StrapLogic.displayName(found("AA:01", name = "  ")))
    }

    // ---- starting from a row ------------------------------------------------

    @Test
    @DisplayName("with nothing running, every known strap's row can start a capture")
    fun anyStrapStartsWhileIdle() {
        // No strap is the privileged one — which one is being worn is known
        // only at the tap — so the answer cannot depend on the list at all.
        assertTrue(StrapLogic.canStart(HrCaptureState()))
    }

    @Test
    @DisplayName("while a capture runs, no row starts another")
    fun noRowStartsWhileRunning() {
        // The service is single-source and refuses a second start; a row that
        // still looked tappable would be a tap failing out of sight.
        val capture = HrCaptureState(isRunning = true, deviceAddress = "AA:01", deviceName = "Alpha")

        assertFalse(StrapLogic.canStart(capture))
    }

    // ---- the stop control -----------------------------------------------------

    @Test
    @DisplayName("nothing running: nothing to stop")
    fun noStopControlWhileIdle() {
        assertNull(StrapLogic.stopControl(HrCaptureState()))
    }

    @Test
    @DisplayName("a running capture is always stoppable, even from a strap that was just forgotten")
    fun runningCaptureIsAlwaysStoppable() {
        val capture = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = "Gone")

        val control = StrapLogic.stopControl(capture)

        assertEquals("AA:09", control!!.address)
        assertEquals("Gone", control.name)
    }

    @Test
    @DisplayName("a running capture with no name falls back to its address rather than an empty row")
    fun runningCaptureFallsBackToAddress() {
        val capture = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = " ")

        assertEquals("AA:09", StrapLogic.stopControl(capture)?.name)
    }

    // ---- forgetting ---------------------------------------------------------

    @Test
    @DisplayName("forgetting the strap that is recording stops it first")
    fun forgetStopsItsOwnCapture() {
        val capture = HrCaptureState(isRunning = true, deviceAddress = "AA:01")

        assertTrue(StrapLogic.forgetStopsCapture("AA:01", capture))
    }

    @Test
    @DisplayName("forgetting a different strap leaves the recording alone")
    fun forgetLeavesOtherCapturesAlone() {
        val capture = HrCaptureState(isRunning = true, deviceAddress = "AA:02")

        assertFalse(StrapLogic.forgetStopsCapture("AA:01", capture))
        assertFalse(StrapLogic.forgetStopsCapture("AA:01", HrCaptureState(deviceAddress = "AA:01")))
    }
}
