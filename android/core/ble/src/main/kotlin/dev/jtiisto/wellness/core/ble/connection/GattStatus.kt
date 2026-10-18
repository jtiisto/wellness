package dev.jtiisto.wellness.core.ble.connection

import java.util.Locale

/**
 * Names for the numbers `onConnectionStateChange` reports, for the debug log.
 *
 * The status on a disconnect is the one number that decides between a radio
 * problem (8, the supervision timeout), the strap ending the link (19), and the
 * phone ending it (22, or 133 from the stack itself). It is logged in decimal
 * **and** hex because the platform sources and the Bluetooth Core spec list the
 * HCI reason codes in hex while logcat prints them in decimal, and a reader
 * matching one against the other should not have to convert.
 *
 * Literals rather than `BluetoothGatt` / `BluetoothProfile` constants: most of
 * these codes have no public constant at all, and keeping this free of the
 * framework is what lets it be unit-tested.
 */
object GattStatus {

    private val NAMES = mapOf(
        0 to "success",
        8 to "connection timeout",
        19 to "remote terminated",
        22 to "local host terminated",
        34 to "LMP response timeout",
        133 to "stack error",
    )

    /** `BluetoothProfile.STATE_*`, by value. */
    private val STATES = mapOf(
        0 to "disconnected",
        1 to "connecting",
        2 to "connected",
        3 to "disconnecting",
    )

    /** E.g. `"8 (0x08, connection timeout)"`; an unnamed code is `"N (0xNN)"`. */
    fun describe(status: Int): String {
        val name = NAMES[status]
        return if (name == null) "$status (${hex(status)})" else "$status (${hex(status)}, $name)"
    }

    /** E.g. `"0 (disconnected)"`; an unnamed state is just its number. */
    fun describeState(newState: Int): String = STATES[newState]?.let { "$newState ($it)" } ?: "$newState"

    private fun hex(value: Int): String =
        "0x" + Integer.toHexString(value).uppercase(Locale.ROOT).padStart(2, '0')
}
