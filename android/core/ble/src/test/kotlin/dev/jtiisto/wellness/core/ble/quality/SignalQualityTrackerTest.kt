package dev.jtiisto.wellness.core.ble.quality

import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class SignalQualityTrackerTest {

    private var nowMs = 0L
    private val tracker = SignalQualityTracker(clock = { nowMs })

    private fun sample(
        rrs: List<Int>,
        hr: Int = 75,
        gap: Boolean = false,
    ) = HeartRateSample(
        deviceId = "AA:BB",
        receivedAtMs = nowMs,
        heartRateBpm = hr,
        rrIntervalsMs = rrs,
        isGapBefore = gap,
    )

    /** Feed a clean beat every ~rr ms for the given wall-clock duration. */
    private fun feedClean(durationMs: Long, rr: Int = 800) {
        val end = nowMs + durationMs
        while (nowMs < end) {
            nowMs += rr
            tracker.add(sample(listOf(rr)))
        }
    }

    @Test
    fun `measuring before enough data`() {
        nowMs = 1000
        tracker.add(sample(listOf(800)))
        assertEquals(SignalQualityLevel.MEASURING, tracker.quality().level)
    }

    @Test
    fun `clean stream reads GOOD with high coverage`() {
        feedClean(60_000)
        val q = tracker.quality()
        assertEquals(SignalQualityLevel.GOOD, q.level)
        assertTrue(q.rrCoveragePercent >= 95)
    }

    @Test
    fun `heavy RR dropout degrades below GOOD`() {
        // Every other notification is HR-only (no RR) — ~50% coverage
        val end = 60_000L
        while (nowMs < end) {
            nowMs += 800
            tracker.add(sample(listOf(800)))
            nowMs += 800
            tracker.add(sample(emptyList())) // HR-only, no RR reported
        }
        val q = tracker.quality()
        assertTrue(q.level == SignalQualityLevel.POOR || q.level == SignalQualityLevel.FAIR)
        assertTrue(q.rrCoveragePercent < 95)
    }

    @Test
    fun `recent gap forces POOR`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        nowMs += 800
        tracker.add(sample(listOf(800), gap = true))
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `ectopic burst raises artifact fraction`() {
        feedClean(40_000)
        // Inject a run of wildly varying RRs (ectopics)
        repeat(60) {
            nowMs += 800
            val rr = if (it % 2 == 0) 400 else 1400
            tracker.add(sample(listOf(rr)))
        }
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `a break mid-window drops coverage below GOOD (continuity)`() {
        // Clean, one HR-only break, then clean again — offline splits this into
        // two runs and won't trust it; the longest run covers only ~half
        feedClean(40_000)
        nowMs += 800
        tracker.add(sample(emptyList())) // HR-only break in the middle
        feedClean(40_000)
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `a gap keeps the window POOR well past 10 seconds`() {
        feedClean(30_000)
        nowMs += 800
        tracker.add(sample(listOf(800), gap = true))
        // 30 s later the gap is still inside the 120 s window -> still POOR,
        // matching the offline "any gap in window = untrusted" rule
        feedClean(30_000)
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `compressed timeline above 105 percent coverage is not GOOD`() {
        // Timestamps advance at half the RR -> summed RR ~200% of wall clock
        val end = 60_000L
        val start = nowMs
        while (nowMs < start + end) {
            nowMs += 400 // half of the 800 ms RR
            tracker.add(sample(listOf(800)))
        }
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `bundled multi-RR notifications are not misread as omissions`() {
        // One 1000 ms notification carrying two 500 ms RRs, repeatedly — a
        // naive receipt-time check would flag every packet as an omission
        val end = 60_000L
        val start = nowMs
        while (nowMs < start + end) {
            nowMs += 1000
            tracker.add(sample(listOf(500, 500), hr = 120))
        }
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `prolonged silence reads POOR not MEASURING`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        // Sensor goes silent long enough to trim the whole window — we HAD a
        // signal, so this is a degraded outage, not initial collection
        nowMs += 200_000
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `timestamp-detected omission breaks the run without a gap flag`() {
        feedClean(40_000)
        // Skip a beat: advance the clock ~2 RR before the next beat, no gap flag
        nowMs += 1700 // > 1.5 * 800
        tracker.add(sample(listOf(800)))
        feedClean(40_000)
        // Offline would split the run at the omission -> not a full clean window
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `isolated long ectopic is corrected and does not tank coverage`() {
        feedClean(40_000)
        // One spurious 8000 ms RR — offline substitutes a neighbour before
        // summing coverage, so live must not let it inflate coverage to POOR
        nowMs += 800
        tracker.add(sample(listOf(8000)))
        feedClean(40_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `silence degrades a previously GOOD signal`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        // No new beats; the clock advances (as the ViewModel tick would) —
        // the covered run shrinks relative to the growing window
        nowMs += 90_000
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `a zero RR sentinel breaks the run`() {
        feedClean(40_000)
        nowMs += 800
        tracker.add(sample(listOf(0)))
        feedClean(40_000)
        assertNotEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `reset returns to MEASURING`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        tracker.reset()
        assertEquals(SignalQualityLevel.MEASURING, tracker.quality().level)
    }

    @Test
    fun `old beats are trimmed out of the window`() {
        feedClean(60_000)
        // Jump forward well past the window with no new data, then add a few
        nowMs += 300_000
        repeat(25) {
            nowMs += 800
            tracker.add(sample(listOf(800)))
        }
        // Only the recent clean beats remain -> still GOOD, not skewed by old data
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    // ---- ⚠ PARITY tripwires ------------------------------------------------
    // The Python side of this pair is ~/dev/health/wellness
    // test/hr/analysis/test_parity_constants.py, which pins the same numbers
    // against src/hr_analysis/quality.py. If one of these fails, the other
    // repo's copy of the rule has to move with it — see the class KDoc.

    @Test
    fun `the DFA window matches pipeline WINDOW_MS`() {
        assertEquals(120_000L, SignalQualityTracker.DEFAULT_WINDOW_MS)
    }

    @Test
    fun `the ectopic threshold bites where quality-py documents it`() {
        // Mirrors test_ectopic_threshold_bites_where_it_is_documented: a beat
        // deviating from the local median by just under 20% is kept, just over
        // is an artifact. Read here through the level, which is the only thing
        // the tracker exposes: with ten beats in the window one artifact is
        // 10% of it, past the 5% ceiling GOOD requires.
        assertEquals(SignalQualityLevel.GOOD, levelWithOneDeviatingBeat(0.19))
        assertNotEquals(SignalQualityLevel.GOOD, levelWithOneDeviatingBeat(0.21))
    }

    /**
     * Ten clean 800 ms beats with the seventh deviating by [deviation], read
     * through a tracker whose minimums are lowered to fit that window.
     */
    private fun levelWithOneDeviatingBeat(deviation: Double): SignalQualityLevel {
        var clock = 0L
        val short = SignalQualityTracker(
            clock = { clock },
            minMeasureMs = 5_000,
            minBeats = 10,
        )
        repeat(10) { index ->
            val rr = if (index == 6) (800 * (1 + deviation)).roundToInt() else 800
            clock += rr
            short.add(
                HeartRateSample(
                    deviceId = "AA:BB",
                    receivedAtMs = clock,
                    heartRateBpm = 75,
                    rrIntervalsMs = listOf(rr),
                ),
            )
        }
        return short.quality().level
    }
}
