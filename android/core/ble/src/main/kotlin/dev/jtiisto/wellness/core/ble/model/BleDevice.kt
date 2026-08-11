package dev.jtiisto.wellness.core.ble.model

/**
 * A heart-rate strap as the scanner saw it: its MAC address and whatever name
 * the advertisement carried ([name] is null when it carried none).
 *
 * Pulse-bridge made this a sealed hierarchy with a sensor type and a priority,
 * because two straps could feed it at once and a multiplexer had to rank them.
 * This app captures from one device, so the type collapses to the address — and
 * with it the whole priority dimension goes away.
 *
 * The address is the identity everywhere downstream: it is `deviceId` on every
 * stored sample and the key the known-device store remembers.
 */
data class BleDevice(
    val address: String,
    val name: String? = null,
)
