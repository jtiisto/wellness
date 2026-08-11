package dev.jtiisto.wellness.core.ble.scanner

import java.util.UUID

/**
 * Whether an advertisement is worth showing as a heart-rate strap.
 *
 * The scan itself is deliberately **unfiltered** — a [android.bluetooth.le.ScanFilter]
 * on the HRM service UUID misses straps that advertise the service only in the
 * scan response, which is most of them, and pulse-bridge lost a device session
 * to exactly that. So everything is delivered and this decides, which also means
 * the decision is a pure function with no adapter anywhere near it.
 *
 * Two independent signals, either of which is enough: the strap advertises the
 * GATT Heart Rate Service, or its name starts like one. The name list is
 * pulse-bridge's. Polar stays on it even though this app never ported the Polar
 * offline-sync path — a Polar strap speaks the same standard HRM characteristic
 * live, so listing it costs nothing and excluding it would hide a device that
 * works.
 */
object HrmAdvertisementFilter {

    /** GATT Heart Rate Service, 0x180D in the Bluetooth SIG base UUID. */
    val HRM_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    val NAME_PREFIXES: List<String> = listOf("HRM", "Garmin", "Polar")

    /**
     * @param name the advertised device name, null when the packet carried none
     * @param serviceUuids the advertised service UUIDs, null when absent
     */
    fun matches(name: String?, serviceUuids: List<UUID>?): Boolean {
        val advertisesHrm = serviceUuids?.contains(HRM_SERVICE_UUID) == true
        val nameMatches = name != null && NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
        return advertisesHrm || nameMatches
    }
}
