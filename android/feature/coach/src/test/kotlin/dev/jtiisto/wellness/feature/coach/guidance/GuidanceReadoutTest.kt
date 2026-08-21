package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The last handful of decisions between the model and the canvas.
 *
 * Small, and every one of them a place the instrument could be wrong in a way
 * nobody would see: a header reading a beat the chart has already dropped, a
 * caption floating above an open band, a strip block filled as though it had
 * been ridden. The painter has no branches, so these are where the branches
 * went.
 */
class GuidanceReadoutTest {

    private fun segment(durationSec: Int, min: Int? = null, max: Int? = null, label: String? = null) =
        PlanSegmentDto(durationSec = durationSec, hrMin = min, hrMax = max, label = label)

    /** Warmup then work, so a window can span the boundary between them. */
    private val twoStep = listOf(
        segment(300, min = 120, max = 140, label = "warmup"),
        segment(240, min = 145, max = 165, label = "work"),
    )

    private fun read(
        samples: List<TraceSample>,
        elapsedMs: Long,
        segments: List<PlanSegmentDto>? = twoStep,
    ): Pair<HrTraceModel, TraceReading> {
        val timeline = guidanceTimeline(segments, plannedDurationSec = null)
        val status = guidanceStatus(timeline, GuidanceRun(startedAtMs = T0), T0 + elapsedMs)
        val model = hrTraceModel(
            samples = samples,
            status = status,
            domain = bpmDomain(timeline),
            nowMs = T0 + elapsedMs,
        )
        return model to currentReading(model, status, T0 + elapsedMs)
    }

    // ---- which beat the header is reading ------------------------------------------

    @Test
    @DisplayName("no samples is no reading — never a zero")
    fun emptyWindowHasNoReading() {
        val (_, reading) = read(samples = emptyList(), elapsedMs = 60_000)

        assertNull(reading.bpm)
        assertNull(reading.segment)
        assertEquals("—", bpmReadout(reading.bpm).drawn)
    }

    @Test
    @DisplayName("the newest beat is the reading, and it brings the band it was ridden against")
    fun newestBeatIsTheReading() {
        val elapsed = 60_000L
        val (_, reading) = read(
            samples = listOf(
                TraceSample(T0 + elapsed - 2_000, bpm = 128),
                TraceSample(T0 + elapsed - 500, bpm = 131),
            ),
            elapsedMs = elapsed,
        )

        assertEquals(131, reading.bpm)
        assertEquals("warmup", reading.segment?.label)
    }

    @Test
    @DisplayName("a beat older than the gap threshold is not a current reading")
    fun staleBeatIsNotTheReading() {
        val elapsed = 60_000L
        val (_, reading) = read(
            samples = listOf(TraceSample(T0 + elapsed - TRACE_GAP_THRESHOLD_MS - 1, bpm = 133)),
            elapsedMs = elapsed,
        )

        assertNull(reading.bpm)
        // The boundary itself is still current: three seconds exactly is the
        // silence that breaks the line, not one that has broken it.
        val (_, onTheEdge) = read(
            samples = listOf(TraceSample(T0 + elapsed - TRACE_GAP_THRESHOLD_MS, bpm = 133)),
            elapsedMs = elapsed,
        )
        assertEquals(133, onTheEdge.bpm)
    }

    @Test
    @DisplayName("the segment is the one running at the beat, not the one running now")
    fun segmentIsTakenAtTheBeat() {
        // Two seconds past the warmup's end: the current segment is the work
        // interval, but the last beat landed in the warmup.
        val elapsed = 302_000L
        val (_, reading) = read(
            samples = listOf(TraceSample(T0 + 299_000, bpm = 137)),
            elapsedMs = elapsed,
        )

        assertEquals("warmup", reading.segment?.label)
    }

    @Test
    @DisplayName("the spoken verdict and the drawn bang are one fact, at a boundary too")
    fun verdictAgreesWithTheInk() {
        val elapsed = 302_000L
        // 137 is inside the warmup's 120–140 and below the work interval's 145
        // floor. Judged against "now" it would be a breach; judged against the
        // band it was ridden in it is not, and the ink says so.
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + 299_000, bpm = 137)),
            elapsedMs = elapsed,
        )

        assertFalse(model.currentOutOfBand)
        assertNull(bandBreach(reading.bpm, reading.segment))
        assertTrue(traceDescription(reading.bpm, reading.segment).endsWith("within target."))
    }

    @Test
    @DisplayName("a beat over its ceiling lights the bang and says which way it went")
    fun breachAgreesWithTheInk() {
        val elapsed = 60_000L
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + elapsed - 400, bpm = 152)),
            elapsedMs = elapsed,
        )

        assertTrue(model.currentOutOfBand)
        assertEquals("Above target", bandBreach(reading.bpm, reading.segment)?.spoken)
        assertNotNull(model.points.lastOrNull()?.takeIf { it.outOfBand })
    }

    @Test
    @DisplayName("a segmentless ride reads a number and judges nothing")
    fun segmentlessReadingHasNoBand() {
        val elapsed = 60_000L
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + elapsed - 400, bpm = 118)),
            elapsedMs = elapsed,
            segments = null,
        )

        assertEquals(118, reading.bpm)
        assertNull(reading.segment)
        assertFalse(model.currentOutOfBand)
        assertEquals("Heart rate 118 beats per minute", bpmReadout(reading.bpm).spoken)
    }

    @Test
    @DisplayName("a beat that has scrolled out of the window is not the reading either")
    fun beatOutsideTheWindowIsDropped() {
        // Older than the window's own history, so the model never placed it.
        val elapsed = 120_000L
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + elapsed - 40_000, bpm = 130)),
            elapsedMs = elapsed,
        )

        assertTrue(model.points.isEmpty())
        assertNull(reading.bpm)
    }

    @Test
    @DisplayName("a beat stamped ahead of now still reads — header and chart must agree about it")
    fun futureStampedBeatStillReads() {
        // A clock correction can stamp the newest beat ahead of the tick. The
        // model draws it in the lookahead; the header must read the same beat
        // rather than blanking over a "future" the rider cannot see.
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + 62_000, bpm = 131)),
            elapsedMs = 60_000,
        )

        assertEquals(131, reading.bpm)
        assertEquals(model.currentOutOfBand, bandBreach(reading.bpm, reading.segment) != null)
    }

    @Test
    @DisplayName("in READY a beat has a number and no verdict — nothing is out of a band that has not begun")
    fun readyReadingHasNoSegment() {
        val timeline = guidanceTimeline(twoStep, plannedDurationSec = null)
        val status = guidanceStatus(timeline, GuidanceRun(), T0)
        val model = hrTraceModel(
            samples = listOf(TraceSample(T0 - 1_000, bpm = 150)),
            status = status,
            domain = bpmDomain(timeline),
            nowMs = T0,
        )

        val reading = currentReading(model, status, T0)

        // 150 would breach the warmup's ceiling if judged — it must not be.
        assertEquals(150, reading.bpm)
        assertNull(reading.segment)
        assertFalse(model.currentOutOfBand)
        assertNull(bandBreach(reading.bpm, reading.segment))
    }

    @Test
    @DisplayName("the boundary beat is judged against the arriving segment, in the header as on the chart")
    fun boundaryBeatReadsTheArrivingSegment() {
        // 155 at exactly the 300s step: over the warmup's ceiling, inside the
        // work band — the verdict flips with the segment, on both surfaces.
        val (model, reading) = read(
            samples = listOf(TraceSample(T0 + 300_000, bpm = 155)),
            elapsedMs = 301_000,
        )

        assertEquals("work", reading.segment?.label)
        assertNull(bandBreach(reading.bpm, reading.segment))
        assertFalse(model.currentOutOfBand)
    }

    // ---- where a caption sits ---------------------------------------------------------

    @Test
    @DisplayName("a band with a ceiling hangs its caption under it")
    fun captionHangsUnderTheCeiling() {
        assertEquals(45.0, bandCaptionY(yTop = 40.0, yBot = 90.0, inset = 5.0, height = 150.0))
    }

    @Test
    @DisplayName("an open-topped band puts its caption above the floor it does have")
    fun captionSitsAboveAnOpenBandsFloor() {
        assertEquals(85.0, bandCaptionY(yTop = null, yBot = 90.0, inset = 5.0, height = 150.0))
    }

    @Test
    @DisplayName("a band with no edge at all still lands somewhere on the canvas")
    fun captionFallsBackToTheInset() {
        assertEquals(5.0, bandCaptionY(yTop = null, yBot = null, inset = 5.0, height = 150.0))
    }

    @Test
    @DisplayName("a caption near a domain edge keeps its inset instead of riding the hairline")
    fun captionClampsInsideThePlot() {
        // A ceiling at the very top of the plot: unclamped, the baseline would
        // land above the canvas; the painter's box-clamp would then park it at
        // zero, on the hairline itself.
        assertEquals(5.0, bandCaptionY(yTop = -3.0, yBot = 60.0, inset = 5.0, height = 150.0))
        // A floor at the very bottom, mirrored.
        assertEquals(145.0, bandCaptionY(yTop = null, yBot = 152.0, inset = 5.0, height = 150.0))
    }

    // ---- which bands are captioned, and which beats are marked ------------------------

    @Test
    @DisplayName("only approaching bands carry captions — the held one is the header's TARGET")
    fun onlyApproachingBandsAreCaptioned() {
        val now = T0 + 302_000
        val (model, _) = read(
            samples = listOf(TraceSample(now, bpm = 150)),
            elapsedMs = 302_000,
        )

        // Two seconds into `work`, `warmup` is behind and `work` is held; only
        // a band still ahead may caption itself.
        assertTrue(captionedBands(model).all { it.ahead })
    }

    @Test
    @DisplayName("the newest beat is filled in band, open with the bang while out — never both dots")
    fun marksSayWhatTheNewestBeatIs() {
        val now = T0 + 60_000
        val inBand = read(samples = listOf(TraceSample(now, bpm = 130)), elapsedMs = 60_000).first
        val outOfBand = read(samples = listOf(TraceSample(now, bpm = 150)), elapsedMs = 60_000).first

        assertEquals(listOf(TraceMarkKind.FILLED_NEWEST), traceMarks(inBand).map { it.kind })
        assertEquals(
            listOf(TraceMarkKind.OPEN_OUT_OF_BAND, TraceMarkKind.BANG),
            traceMarks(outOfBand).map { it.kind },
        )
    }

    @Test
    @DisplayName("an empty window draws no marks at all")
    fun emptyWindowDrawsNoMarks() {
        val (model, _) = read(samples = emptyList(), elapsedMs = 60_000)

        assertEquals(emptyList<TraceMark>(), traceMarks(model))
    }

    // ---- the strip's blocks -----------------------------------------------------------

    @Test
    @DisplayName("the hardest segment takes the tallest bar and the easiest the shortest")
    fun barHeightRanksTheSegments() {
        val strip = 26.0

        val easiest = stripBar(intensity = 0.0, stripHeight = strip)
        val hardest = stripBar(intensity = 1.0, stripHeight = strip)

        assertEquals(strip * STRIP_BAR_MIN_FRACTION, easiest.height, 1e-9)
        assertEquals(strip * STRIP_BAR_MAX_FRACTION, hardest.height, 1e-9)
        assertTrue(hardest.height > easiest.height)
    }

    @Test
    @DisplayName("bars are centred, so a row of mixed heights reads as one line")
    fun barsAreCentred() {
        val strip = 26.0

        for (intensity in listOf(0.0, 0.5, 1.0)) {
            val bar = stripBar(intensity, strip)
            assertEquals(bar.y, strip - (bar.y + bar.height), 1e-9)
        }
    }

    @Test
    @DisplayName("a segment with no band to rank takes the shortest bar rather than a guess")
    fun unrankedSegmentTakesTheShortestBar() {
        val strip = 26.0

        assertEquals(stripBar(0.0, strip), stripBar(null, strip))
    }

    @Test
    @DisplayName("an out-of-range ranking is clamped rather than drawn off the strip")
    fun rankingIsClamped() {
        val strip = 26.0

        assertEquals(stripBar(0.0, strip), stripBar(-1.0, strip))
        assertEquals(stripBar(1.0, strip), stripBar(2.5, strip))
    }

    @Test
    @DisplayName("the four block states take the four treatments, and only the current one is full ink")
    fun stripFills() {
        assertEquals(StripFill.SOLID_FAINT, stripFill(StripState.DONE))
        assertEquals(StripFill.SOLID_INK, stripFill(StripState.CURRENT))
        assertEquals(StripFill.OUTLINE, stripFill(StripState.AHEAD))
        assertEquals(StripFill.DASHED_OUTLINE, stripFill(StripState.EXTENSION))
    }

    @Test
    @DisplayName("appended time is the only block drawn dashed")
    fun onlyTheExtensionIsDashed() {
        val dashed = StripState.entries.filter { stripFill(it) == StripFill.DASHED_OUTLINE }

        assertEquals(listOf(StripState.EXTENSION), dashed)
    }
}
