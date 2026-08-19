package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice

/**
 * The one Start/Stop control the strap section shows, or null when there is
 * nothing to control yet.
 */
data class CaptureControl(
    val running: Boolean,
    val address: String,
    val name: String,
)

/**
 * The strap section's decisions, as functions of state.
 *
 * Separated from the ViewModel for the same reason [ToolsLogic] is: these are
 * the parts with a wrong answer. Which strap the Start button would use decides
 * what gets recorded; whether a scan result is already paired decides whether
 * the list offers a Connect that would do nothing new.
 */
object StrapLogic {

    /**
     * Fold one advertisement into the discovered list.
     *
     * First-seen order, replaced in place. Sorting by signal strength is the
     * obvious alternative and the wrong one: RSSI swings several dB between
     * packets from a strap lying still, so the rows would reorder under the
     * user's finger as they reached for one.
     */
    fun merge(current: List<DiscoveredDevice>, found: DiscoveredDevice): List<DiscoveredDevice> {
        val index = current.indexOfFirst { it.address == found.address }
        if (index < 0) return current + found
        return current.toMutableList().apply { this[index] = found }
    }

    /**
     * The scan results worth offering: everything not already remembered.
     *
     * A paired strap advertises just as loudly as an unpaired one, and listing
     * it twice — once as "known", once as "connect to this" — is how a user ends
     * up believing they have two straps.
     */
    fun unpaired(discovered: List<DiscoveredDevice>, known: List<KnownDevice>): List<DiscoveredDevice> {
        val addresses = known.mapTo(mutableSetOf()) { it.address }
        return discovered.filterNot { it.address in addresses }
    }

    /**
     * Which capture control to show.
     *
     * A running capture is always stoppable, whatever the known list holds —
     * including a strap forgotten mid-session, which is exactly when a stop
     * button that had vanished would be most missed. With nothing running the
     * control appears only when there is a remembered strap to start: an unknown
     * one is the pairing list's business, one row above.
     *
     * [known] arrives from `KnownDeviceStore.devices`, which publishes the same
     * ordering `preferred()` picks from, so the first entry here and the strap
     * the Start Workout sheet offers are the same device by construction.
     */
    fun captureControl(capture: HrCaptureState, known: List<KnownDevice>): CaptureControl? {
        if (capture.isRunning) {
            val address = capture.deviceAddress.orEmpty()
            return CaptureControl(
                running = true,
                address = address,
                name = capture.deviceName?.takeIf { it.isNotBlank() } ?: address,
            )
        }
        return known.firstOrNull()?.let { CaptureControl(running = false, address = it.address, name = it.name) }
    }

    /**
     * Forgetting the strap a capture is running on stops that capture first.
     *
     * Otherwise the session would keep recording from a device the user has just
     * told the app it does not know — and the notification would name it.
     */
    fun forgetStopsCapture(address: String, capture: HrCaptureState): Boolean =
        capture.isRunning && capture.deviceAddress == address

    /** An advertisement with no name is still connectable; it just has none. */
    fun displayName(device: DiscoveredDevice): String =
        device.name?.takeIf { it.isNotBlank() } ?: StrapCopy.UNNAMED
}

/** Every user-facing string in the strap section. Asserted by the tests. */
object StrapCopy {

    const val TITLE = "Heart rate strap"

    const val EMPTY =
        "No strap paired. Scan to find one — it must be worn and not connected to a watch."

    const val UNNAMED = "Unnamed strap"

    /**
     * The word beside a capturing strap's name — the state has to survive being
     * read aloud, and the underline alone is invisible to a screen reader. The
     * server list's `ACTIVE` label is the same rule; this row's state is not
     * "active", it is capturing, so the word says that.
     */
    const val CAPTURING_LABEL = "Capturing"

    const val SCAN = "Scan for straps"
    const val SCAN_STOP = "Stop scanning"
    const val SCANNING = "Scanning…"
    const val NO_RESULTS = "Nothing found yet. A Garmin strap only advertises while it is worn."

    const val CONNECT = "Connect"
    const val FORGET = "Forget"

    const val START_CAPTURE = "Start capture"
    const val STOP_CAPTURE = "Stop capture"

    /**
     * Says what a capture without a workout is *for*, because the alternative
     * reading — that this is how you record a workout — would have the user
     * starting captures here that the coach tab never anchors to anything.
     */
    const val CAPTURE_HINT =
        "Records heart rate on its own. During a workout, start it from the Coach tab instead " +
            "so the recording is linked to that workout."

    const val SCAN_FAILED =
        "Could not scan. Check that Bluetooth is switched on, then try again."
}
