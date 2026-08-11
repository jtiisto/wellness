package dev.jtiisto.wellness.core.ble.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BleDeviceTest {

    @Test
    fun `a device is identified by its address`() {
        // The scanner reports the same strap repeatedly and the known-device
        // store keys on it, so equality has to be the address, not the object
        assertEquals(BleDevice("AA:BB", "HRM 200"), BleDevice("AA:BB", "HRM 200"))
        assertNotEquals(BleDevice("AA:BB", "HRM 200"), BleDevice("AA:CC", "HRM 200"))
    }

    @Test
    fun `an advertisement without a name leaves the name null`() {
        assertNull(BleDevice("AA:BB").name)
    }
}
