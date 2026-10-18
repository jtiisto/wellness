package dev.jtiisto.wellness.core.ble.connection

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The disconnect status in the debug log, which is the number that tells a
 * radio problem from the strap ending the link from the phone ending it.
 */
class GattStatusTest {

    @Test
    @DisplayName("the common disconnect reasons carry decimal, hex and a name")
    fun namedStatuses() {
        assertEquals("0 (0x00, success)", GattStatus.describe(0))
        assertEquals("8 (0x08, connection timeout)", GattStatus.describe(8))
        assertEquals("19 (0x13, remote terminated)", GattStatus.describe(19))
        assertEquals("22 (0x16, local host terminated)", GattStatus.describe(22))
        assertEquals("34 (0x22, LMP response timeout)", GattStatus.describe(34))
        assertEquals("133 (0x85, stack error)", GattStatus.describe(133))
    }

    @Test
    @DisplayName("an unnamed status still carries both bases and invents no name")
    fun unnamedStatus() {
        assertEquals("62 (0x3E)", GattStatus.describe(62))
        assertEquals("1 (0x01)", GattStatus.describe(1))
        assertEquals("257 (0x101)", GattStatus.describe(257))
    }

    @Test
    @DisplayName("connection states are named by their BluetoothProfile value")
    fun states() {
        assertEquals("0 (disconnected)", GattStatus.describeState(0))
        assertEquals("1 (connecting)", GattStatus.describeState(1))
        assertEquals("2 (connected)", GattStatus.describeState(2))
        assertEquals("3 (disconnecting)", GattStatus.describeState(3))
        assertEquals("9", GattStatus.describeState(9))
    }

    @Test
    @DisplayName("the literals agree with the framework constants they stand in for")
    fun literalsMatchFramework() {
        // Compile-time constants, inlined by the compiler — no Android runtime needed.
        assertEquals(0, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, BluetoothProfile.STATE_CONNECTING)
        assertEquals(2, BluetoothProfile.STATE_CONNECTED)
        assertEquals(3, BluetoothProfile.STATE_DISCONNECTING)
        assertEquals(0, BluetoothGatt.GATT_SUCCESS)
    }
}
