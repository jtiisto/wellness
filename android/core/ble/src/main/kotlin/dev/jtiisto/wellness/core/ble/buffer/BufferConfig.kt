package dev.jtiisto.wellness.core.ble.buffer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When [IntervalBuffer] hands a batch to its sink: every [flushInterval], or as
 * soon as [maxBufferSize] rows have accumulated, whichever comes first. The
 * defaults are pulse-bridge's, device-proven over long captures, and they match
 * the cadence `HrSyncStore` uploads on.
 */
data class BufferConfig(
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    val maxBufferSize: Int = DEFAULT_MAX_BUFFER_SIZE,
) {
    companion object {
        val DEFAULT_FLUSH_INTERVAL: Duration = 10.seconds
        const val DEFAULT_MAX_BUFFER_SIZE: Int = 200
    }
}
