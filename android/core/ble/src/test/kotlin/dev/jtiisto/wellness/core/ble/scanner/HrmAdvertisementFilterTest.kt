package dev.jtiisto.wellness.core.ble.scanner

import dev.jtiisto.wellness.core.ble.model.BleDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The callback-side filter, which is the whole reason the scan itself is
 * unfiltered: a platform `ScanFilter` on the service UUID misses straps that
 * only advertise it in the scan response, and that is most of them.
 */
class HrmAdvertisementFilterTest {

    private val someOtherService: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    @Test
    @DisplayName("advertising the Heart Rate Service is enough on its own")
    fun serviceUuidMatches() {
        assertTrue(HrmAdvertisementFilter.matches(null, listOf(HrmAdvertisementFilter.HRM_SERVICE_UUID)))
        assertTrue(
            HrmAdvertisementFilter.matches(
                "Some Sensor",
                listOf(someOtherService, HrmAdvertisementFilter.HRM_SERVICE_UUID),
            ),
        )
    }

    @Test
    @DisplayName("a known name is enough on its own, whatever it advertises")
    fun namePrefixMatches() {
        assertTrue(HrmAdvertisementFilter.matches("HRM-Pro 123456", null))
        assertTrue(HrmAdvertisementFilter.matches("Garmin HRM", emptyList()))
        // Polar stays on the list: the offline-sync path was not ported, but a
        // Polar strap speaks the same standard HRM characteristic live.
        assertTrue(HrmAdvertisementFilter.matches("Polar H10", listOf(someOtherService)))
    }

    @Test
    @DisplayName("the name match ignores case")
    fun namePrefixIsCaseInsensitive() {
        assertTrue(HrmAdvertisementFilter.matches("hrm-dual", null))
        assertTrue(HrmAdvertisementFilter.matches("GARMIN HRM-Pro", null))
    }

    @Test
    @DisplayName("a prefix has to be a prefix — not a substring")
    fun nameMustStartWithThePrefix() {
        assertFalse(HrmAdvertisementFilter.matches("My HRM", null))
    }

    @Test
    @DisplayName("everything else is ignored, including a nameless stranger")
    fun nonMatchesAreRejected() {
        assertFalse(HrmAdvertisementFilter.matches(null, null))
        assertFalse(HrmAdvertisementFilter.matches(null, emptyList()))
        assertFalse(HrmAdvertisementFilter.matches("Wireless Earbuds", listOf(someOtherService)))
    }

    @Test
    @DisplayName("a discovered device carries the identity that outlives the scan")
    fun discoveredDeviceWrapsTheIdentity() {
        val found = DiscoveredDevice(BleDevice(address = "AA:BB:CC:DD:EE:FF", name = "HRM-Pro"), rssi = -58)

        assertEquals("AA:BB:CC:DD:EE:FF", found.address)
        assertEquals("HRM-Pro", found.name)
        assertEquals(-58, found.rssi)
        // RSSI is the only thing that changes between two sightings of one
        // strap, so two of them are not the same discovery.
        assertEquals(found, found.copy())
        assertTrue(found != found.copy(rssi = -70))
        assertTrue(found.toString().contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    @DisplayName("an unnamed advertisement still reports its address")
    fun discoveredDeviceWithoutAName() {
        val found = DiscoveredDevice(BleDevice(address = "AA:BB"), rssi = -90)

        assertEquals(null, found.name)
        assertEquals("AA:BB", found.address)
    }
}
