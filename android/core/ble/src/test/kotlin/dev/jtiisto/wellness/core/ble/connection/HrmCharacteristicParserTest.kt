package dev.jtiisto.wellness.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HrmCharacteristicParserTest {

    @Test
    fun `parse empty array returns null`() {
        assertNull(HrmCharacteristicParser.parse(byteArrayOf()))
    }

    @Test
    fun `parse 8-bit HR without RR intervals`() {
        // Flags: 0x00 (8-bit HR, no RR, no energy expended)
        // HR: 72
        val data = byteArrayOf(0x00, 72)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(72, result.heartRateBpm)
        assertTrue(result.rrIntervalsMs.isEmpty())
        assertNull(result.energyExpendedKj)
    }

    @Test
    fun `parse 16-bit HR without RR intervals`() {
        // Flags: 0x01 (16-bit HR)
        // HR: 300 = 0x012C (little-endian: 0x2C, 0x01)
        val data = byteArrayOf(0x01, 0x2C, 0x01)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(300, result.heartRateBpm)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parse 8-bit HR with single RR interval`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 72
        // RR: 853 in 1/1024s = 0x0355 → little-endian: 0x55, 0x03
        // Expected ms: (853 * 1000) / 1024 = 833
        val data = byteArrayOf(0x10, 72, 0x55, 0x03)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(72, result.heartRateBpm)
        assertEquals(listOf(833), result.rrIntervalsMs)
    }

    @Test
    fun `parse 8-bit HR with multiple RR intervals`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 65
        // RR1: 1024 in 1/1024s = 0x0400 → 1000ms
        // RR2: 512 in 1/1024s = 0x0200 → 500ms
        val data = byteArrayOf(0x10, 65, 0x00, 0x04, 0x00, 0x02)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(65, result.heartRateBpm)
        assertEquals(listOf(1000, 500), result.rrIntervalsMs)
    }

    @Test
    fun `parse 16-bit HR with RR intervals`() {
        // Flags: 0x11 (16-bit HR, RR present)
        // HR: 150 = 0x0096 → little-endian: 0x96, 0x00
        // RR: 512 in 1/1024s = 0x0200 → 500ms
        val data = byteArrayOf(0x11, 0x96.toByte(), 0x00, 0x00, 0x02)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(150, result.heartRateBpm)
        assertEquals(listOf(500), result.rrIntervalsMs)
    }

    @Test
    fun `parse with energy expended present and RR intervals`() {
        // Flags: 0x18 (8-bit HR, energy expended present, RR present)
        // HR: 80
        // Energy: 42 = 0x002A → little-endian: 0x2A, 0x00
        // RR: 1024 in 1/1024s = 0x0400 → 1000ms
        val data = byteArrayOf(0x18, 80, 0x2A, 0x00, 0x00, 0x04)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(80, result.heartRateBpm)
        assertEquals(42, result.energyExpendedKj)
        assertEquals(listOf(1000), result.rrIntervalsMs)
    }

    @Test
    fun `parse with energy expended present but no RR intervals`() {
        // Flags: 0x08 (8-bit HR, energy expended present, no RR)
        // HR: 90
        // Energy: 100 = 0x0064 → little-endian: 0x64, 0x00
        val data = byteArrayOf(0x08, 90, 0x64, 0x00)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(90, result.heartRateBpm)
        assertEquals(100, result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parse with sensor contact supported and detected`() {
        // Flags: 0x06 (8-bit HR, sensor contact supported + detected)
        // HR: 70
        val data = byteArrayOf(0x06, 70)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(70, result.heartRateBpm)
        assertEquals(true, result.sensorContactDetected)
    }

    @Test
    fun `parse with sensor contact supported but not detected`() {
        // Flags: 0x04 (8-bit HR, sensor contact supported, not detected)
        // HR: 0
        val data = byteArrayOf(0x04, 0)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(0, result.heartRateBpm)
        assertEquals(false, result.sensorContactDetected)
    }

    @Test
    fun `parse without sensor contact feature returns null contact`() {
        val data = byteArrayOf(0x00, 72)
        val result = HrmCharacteristicParser.parse(data)!!

        assertNull(result.sensorContactDetected)
    }

    @Test
    fun `parse truncated 16-bit HR returns null`() {
        // Flags: 0x01 (16-bit HR) but only one byte of HR data
        val data = byteArrayOf(0x01, 72)
        assertNull(HrmCharacteristicParser.parse(data))
    }

    @Test
    fun `parse truncated 8-bit HR returns null`() {
        // Flags only — the packet claims a heart rate it does not carry
        assertNull(HrmCharacteristicParser.parse(byteArrayOf(0x00)))
    }

    @Test
    fun `parse truncated energy expended field is ignored`() {
        // Flags: 0x08 (energy expended present) with one byte where two are
        // needed — the heart rate is still good, so the packet is still useful
        val data = byteArrayOf(0x08, 90, 0x64)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(90, result.heartRateBpm)
        assertNull(result.energyExpendedKj)
    }

    @Test
    fun `parse three RR intervals in one notification`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 140
        // RR1: 512 → 500ms, RR2: 480 → 468ms, RR3: 496 → 484ms
        val data = byteArrayOf(
            0x10, 140.toByte(),
            0x00, 0x02, // 512
            0xE0.toByte(), 0x01, // 480
            0xF0.toByte(), 0x01, // 496
        )
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(140, result.heartRateBpm)
        assertEquals(listOf(500, 468, 484), result.rrIntervalsMs)
    }

    @Test
    fun `parse handles odd trailing byte gracefully`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 72
        // One complete RR + one trailing byte (incomplete RR)
        val data = byteArrayOf(0x10, 72, 0x00, 0x04, 0xFF.toByte())
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(listOf(1000), result.rrIntervalsMs) // only complete RR pairs
    }
}
