package dev.jtiisto.wellness.core.ble.reconnect

import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exponential backoff for strap reconnects: 1 s doubling to a 30 s ceiling,
 * fifteen attempts. Stateful and single-threaded by design — one connection
 * owns one strategy, and [reset] on a confirmed data flow is what makes the
 * attempt budget mean "failures in a row" rather than "failures ever".
 */
class ReconnectionStrategy(
    private val config: ReconnectionConfig = ReconnectionConfig(),
) {
    private var attemptCount = 0

    val currentAttempt: Int get() = attemptCount

    val hasAttemptsRemaining: Boolean
        get() = attemptCount < config.maxAttempts

    fun nextDelay(): Duration {
        val delayMs = config.initialDelay.inWholeMilliseconds *
            config.multiplier.pow(attemptCount)
        val cappedMs = min(delayMs.toLong(), config.maxDelay.inWholeMilliseconds)
        attemptCount++
        return cappedMs.milliseconds
    }

    fun reset() {
        attemptCount = 0
    }
}
