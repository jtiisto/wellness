package dev.jtiisto.wellness.core.ble.reconnect

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ReconnectionConfig(
    val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    val multiplier: Double = DEFAULT_MULTIPLIER,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    companion object {
        val DEFAULT_INITIAL_DELAY: Duration = 1.seconds
        const val DEFAULT_MULTIPLIER: Double = 2.0
        val DEFAULT_MAX_DELAY: Duration = 30.seconds

        // Bounded so a dead connection can't retry silently forever; the
        // capture service's inactivity timeout remains the outer safety net
        const val DEFAULT_MAX_ATTEMPTS: Int = 15
    }
}
