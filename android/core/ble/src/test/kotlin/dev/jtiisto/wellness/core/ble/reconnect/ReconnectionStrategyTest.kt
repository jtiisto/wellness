package dev.jtiisto.wellness.core.ble.reconnect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectionStrategyTest {

    @Test
    fun `first delay equals initial delay`() {
        val strategy = ReconnectionStrategy(
            ReconnectionConfig(initialDelay = 1.seconds, multiplier = 2.0)
        )
        assertEquals(1.seconds, strategy.nextDelay())
    }

    @Test
    fun `delays increase exponentially`() {
        val strategy = ReconnectionStrategy(
            ReconnectionConfig(initialDelay = 1.seconds, multiplier = 2.0, maxDelay = 60.seconds)
        )

        assertEquals(1.seconds, strategy.nextDelay())   // 1 * 2^0
        assertEquals(2.seconds, strategy.nextDelay())   // 1 * 2^1
        assertEquals(4.seconds, strategy.nextDelay())   // 1 * 2^2
        assertEquals(8.seconds, strategy.nextDelay())   // 1 * 2^3
    }

    @Test
    fun `delay is capped at maxDelay`() {
        val strategy = ReconnectionStrategy(
            ReconnectionConfig(initialDelay = 1.seconds, multiplier = 2.0, maxDelay = 5.seconds)
        )

        strategy.nextDelay() // 1s
        strategy.nextDelay() // 2s
        strategy.nextDelay() // 4s
        assertEquals(5.seconds, strategy.nextDelay()) // would be 8s, capped at 5s
        assertEquals(5.seconds, strategy.nextDelay()) // still capped
    }

    @Test
    fun `reset sets attempt count to zero`() {
        val strategy = ReconnectionStrategy(
            ReconnectionConfig(initialDelay = 500.milliseconds, multiplier = 2.0)
        )
        strategy.nextDelay()
        strategy.nextDelay()
        assertEquals(2, strategy.currentAttempt)

        strategy.reset()
        assertEquals(0, strategy.currentAttempt)
        assertEquals(500.milliseconds, strategy.nextDelay())
    }

    @Test
    fun `hasAttemptsRemaining respects maxAttempts`() {
        val strategy = ReconnectionStrategy(
            ReconnectionConfig(maxAttempts = 3)
        )

        assertTrue(strategy.hasAttemptsRemaining)
        strategy.nextDelay()
        strategy.nextDelay()
        strategy.nextDelay()
        assertFalse(strategy.hasAttemptsRemaining)
    }

    @Test
    fun `default config bounds attempts so retries cannot loop forever`() {
        val strategy = ReconnectionStrategy()

        repeat(ReconnectionConfig.DEFAULT_MAX_ATTEMPTS) {
            assertTrue(strategy.hasAttemptsRemaining)
            strategy.nextDelay()
        }
        assertFalse(strategy.hasAttemptsRemaining)
    }

    @Test
    fun `the default schedule climbs one second to the thirty second ceiling`() {
        val strategy = ReconnectionStrategy()

        val delays = List(7) { strategy.nextDelay() }

        assertEquals(
            listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds, 30.seconds),
            delays,
        )
    }
}
