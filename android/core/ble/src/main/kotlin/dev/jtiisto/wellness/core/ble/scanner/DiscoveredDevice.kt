package dev.jtiisto.wellness.core.ble.scanner

import dev.jtiisto.wellness.core.ble.model.BleDevice

/**
 * One advertisement that passed [HrmAdvertisementFilter], as the pairing list
 * shows it.
 *
 * [rssi] is signal strength in dBm — closer to zero is stronger — and exists
 * only so the user can tell which of two straps in the room is the one on their
 * chest. It is not stored anywhere; the identity that outlives the scan is
 * [device], and that is why this wraps [BleDevice] rather than repeating its
 * fields.
 */
data class DiscoveredDevice(
    val device: BleDevice,
    val rssi: Int,
) {
    val address: String get() = device.address
    val name: String? get() = device.name
}
