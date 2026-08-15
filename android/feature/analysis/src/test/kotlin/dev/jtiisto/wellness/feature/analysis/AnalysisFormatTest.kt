package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Timestamps and the progress clock.
 *
 * The rule that matters most here is that neither function can throw. A single
 * corrupt string in a cached history — a row written by an older build, a
 * truncated payload — would otherwise take the whole History screen down, and
 * the one bad row is the only thing actually wrong.
 */
class AnalysisFormatTest {

    private val logged = mutableListOf<String>()
    private val debugLog = mockk<DebugLog>().also { mock ->
        every { mock.log(any(), any(), any()) } answers { logged += secondArg<String>() }
    }

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val utc: ZoneId = ZoneId.of("UTC")

    // ---- formatTimestamp ---------------------------------------------------

    @Test
    @DisplayName("a UTC instant renders in the injected zone, en-US")
    fun formatsInInjectedZone() {
        val iso = "2031-03-04T14:05:00Z"

        assertEquals("Mar 4, 2031, 2:05 PM", AnalysisFormat.formatTimestamp(iso, utc))
        assertEquals("Mar 4, 2031, 9:05 AM", AnalysisFormat.formatTimestamp(iso, newYork))
    }

    @Test
    @DisplayName("the server's microsecond precision parses")
    fun formatsMicrosecondPrecision() {
        assertEquals(
            "Mar 4, 2031, 9:15 AM",
            AnalysisFormat.formatTimestamp("2031-03-04T09:15:00.123456Z", utc),
        )
    }

    @Test
    @DisplayName("midnight and noon read as 12, not 0")
    fun formatsTwelveHourEdges() {
        assertEquals("Mar 4, 2031, 12:00 AM", AnalysisFormat.formatTimestamp("2031-03-04T00:00:00Z", utc))
        assertEquals("Mar 4, 2031, 12:30 PM", AnalysisFormat.formatTimestamp("2031-03-04T12:30:00Z", utc))
    }

    @Test
    @DisplayName("a DST spring-forward boundary lands on the right side of the jump")
    fun formatsAcrossDstBoundary() {
        // 2031-03-09 07:00Z is 02:00 EST, the instant New York skips to 03:00 EDT.
        assertEquals("Mar 9, 2031, 3:00 AM", AnalysisFormat.formatTimestamp("2031-03-09T07:00:00Z", newYork))
        assertEquals("Mar 9, 2031, 1:59 AM", AnalysisFormat.formatTimestamp("2031-03-09T06:59:00Z", newYork))
    }

    @Test
    @DisplayName("null and blank render as nothing, quietly — there is no error to report")
    fun missingTimestampRendersEmpty() {
        assertEquals("", AnalysisFormat.formatTimestamp(null, utc, debugLog))
        assertEquals("", AnalysisFormat.formatTimestamp("", utc, debugLog))
        assertEquals("", AnalysisFormat.formatTimestamp("   ", utc, debugLog))
        assertTrue(logged.isEmpty(), "an absent completed_at is normal, not a fault")
    }

    @Test
    @DisplayName("a malformed timestamp renders as nothing and is logged, never thrown")
    fun malformedTimestampIsSurvivable() {
        assertEquals("", AnalysisFormat.formatTimestamp("not-a-date", utc, debugLog))
        assertEquals("", AnalysisFormat.formatTimestamp("2031-13-45T99:99:99Z", utc, debugLog))
        assertEquals("", AnalysisFormat.formatTimestamp("2031-03-04", utc, debugLog))
        assertEquals(3, logged.size)
    }

    // ---- elapsedSeconds ----------------------------------------------------

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    @DisplayName("elapsed counts whole seconds from created_at")
    fun elapsedCountsWholeSeconds() {
        val created = "2031-03-04T09:15:00Z"

        assertEquals(0, AnalysisFormat.elapsedSeconds(created, at(created)))
        assertEquals(0, AnalysisFormat.elapsedSeconds(created, at(created) + 999))
        assertEquals(1, AnalysisFormat.elapsedSeconds(created, at(created) + 1_000))
        assertEquals(59, AnalysisFormat.elapsedSeconds(created, at(created) + 59_000))
        assertEquals(3_600, AnalysisFormat.elapsedSeconds(created, at(created) + 3_600_000))
    }

    @Test
    @DisplayName("clock skew clamps to zero rather than counting up from a negative number")
    fun elapsedClampsNegativeSkew() {
        val created = "2031-03-04T09:15:00Z"

        assertEquals(0, AnalysisFormat.elapsedSeconds(created, at(created) - 30_000))
    }

    @Test
    @DisplayName("a missing or malformed created_at yields zero, logged, never thrown")
    fun elapsedSurvivesBadInput() {
        assertEquals(0, AnalysisFormat.elapsedSeconds(null, 1_000_000, debugLog))
        assertEquals(0, AnalysisFormat.elapsedSeconds("", 1_000_000, debugLog))
        assertEquals(0, AnalysisFormat.elapsedSeconds("not-a-date", 1_000_000, debugLog))
        assertEquals(1, logged.size, "only the malformed value is a fault worth logging")
    }

    // ---- formatElapsed -----------------------------------------------------

    @Test
    @DisplayName("under a minute is bare seconds")
    fun formatsSeconds() {
        assertEquals("0s", AnalysisFormat.formatElapsed(0))
        assertEquals("1s", AnalysisFormat.formatElapsed(1))
        assertEquals("59s", AnalysisFormat.formatElapsed(59))
    }

    @Test
    @DisplayName("a minute and over splits, and the seconds part is kept even at zero")
    fun formatsMinutes() {
        assertEquals("1m 0s", AnalysisFormat.formatElapsed(60))
        assertEquals("1m 1s", AnalysisFormat.formatElapsed(61))
        assertEquals("6m 40s", AnalysisFormat.formatElapsed(400))
    }

    @Test
    @DisplayName("there is no hour case: ninety minutes reads as ninety minutes")
    fun formatsWithoutHours() {
        assertEquals("60m 0s", AnalysisFormat.formatElapsed(3_600))
        assertEquals("90m 0s", AnalysisFormat.formatElapsed(5_400))
    }

    @Test
    @DisplayName("a negative duration cannot be rendered as one")
    fun formatsNegativeAsZero() {
        assertEquals("0s", AnalysisFormat.formatElapsed(-5))
    }
}
