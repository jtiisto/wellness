package dev.jtiisto.wellness.core.ble.connection

/**
 * Parses the Heart Rate Measurement characteristic (UUID 0x2A37) per the
 * Bluetooth GATT Heart Rate Service specification.
 *
 * Byte layout:
 * - Byte 0: Flags
 *   - Bit 0: HR format (0 = UINT8, 1 = UINT16)
 *   - Bit 1-2: Sensor contact status
 *   - Bit 3: Energy expended present
 *   - Bit 4: RR-interval present
 * - Byte 1 (or 1-2): Heart rate value
 * - Optional: Energy expended (UINT16)
 * - Optional: RR-interval values (UINT16 each, 1/1024 second resolution)
 *
 * Returns null only for a packet too short to hold the heart rate it claims;
 * everything else degrades — a trailing half RR is dropped, an energy field
 * that runs off the end is ignored — because a strap that sends one malformed
 * notification is still worth listening to.
 */
object HrmCharacteristicParser {

    data class ParsedHeartRate(
        val heartRateBpm: Int,
        val rrIntervalsMs: List<Int>,
        val energyExpendedKj: Int? = null,
        val sensorContactDetected: Boolean? = null,
    )

    fun parse(value: ByteArray): ParsedHeartRate? {
        if (value.isEmpty()) return null

        val flags = value[0].toInt() and 0xFF
        val isHrFormat16Bit = flags and 0x01 != 0
        val sensorContactSupported = flags and 0x04 != 0
        val sensorContactDetected = if (sensorContactSupported) flags and 0x02 != 0 else null
        val energyExpendedPresent = flags and 0x08 != 0
        val rrIntervalPresent = flags and 0x10 != 0

        var offset = 1

        val heartRateBpm: Int
        if (isHrFormat16Bit) {
            if (value.size < offset + 2) return null
            heartRateBpm = (value[offset].toInt() and 0xFF) or
                ((value[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            if (value.size < offset + 1) return null
            heartRateBpm = value[offset].toInt() and 0xFF
            offset += 1
        }

        var energyExpendedKj: Int? = null
        if (energyExpendedPresent) {
            if (value.size >= offset + 2) {
                energyExpendedKj = (value[offset].toInt() and 0xFF) or
                    ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
            }
        }

        val rrIntervalsMs = mutableListOf<Int>()
        if (rrIntervalPresent) {
            while (offset + 1 < value.size) {
                val rrRaw = (value[offset].toInt() and 0xFF) or
                    ((value[offset + 1].toInt() and 0xFF) shl 8)
                // 1/1024 s to ms, integer division as the spec's resolution
                // allows — the analysis works in whole milliseconds throughout
                rrIntervalsMs.add((rrRaw * 1000) / 1024)
                offset += 2
            }
        }

        return ParsedHeartRate(
            heartRateBpm = heartRateBpm,
            rrIntervalsMs = rrIntervalsMs,
            energyExpendedKj = energyExpendedKj,
            sensorContactDetected = sensorContactDetected,
        )
    }
}
