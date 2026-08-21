package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The scrolling window, as geometry.
 *
 * The window is forty-five seconds wide across three hundred and sixty logical
 * units, which makes **one unit exactly 125 ms** — every x assertion here is
 * that arithmetic, and it is worth stating once rather than deriving in each
 * test. The now-line lands at 240, two thirds of the width, and the spec's
 * `+10 s` label at 320, well inside the right edge.
 *
 * What this file is really pinning is three refusals: the trace does not draw a
 * line across a silence, a beat is not judged against a band that was not
 * running when it happened, and the axis does not move.
 */
class HrTraceModelTest {

    /** Milliseconds per logical unit: `45_000 / 360`. */
    private val msPerUnit = 125L

    private fun segment(durationSec: Int, min: Int? = null, max: Int? = null, label: String? = null) =
        PlanSegmentDto(durationSec = durationSec, hrMin = min, hrMax = max, label = label)

    /**
     * Warmup then work, with bounds chosen so the axis comes out round: the
     * extent 120–160 pads to 90–190, a hundred bpm over a hundred and fifty
     * units, so y is exactly `1.5 × (190 − bpm)`.
     */
    private val twoStep = listOf(
        segment(300, min = 120, max = 140, label = "warmup"),
        segment(240, min = 140, max = 160, label = "work"),
    )

    private fun statusAt(
        segments: List<PlanSegmentDto>?,
        elapsedMs: Long,
        plannedDurationSec: Int? = null,
        extensionSec: Int = 0,
    ) = guidanceStatus(
        timeline = guidanceTimeline(segments, plannedDurationSec),
        run = GuidanceRun(startedAtMs = T0, extensionSec = extensionSec),
        nowMs = T0 + elapsedMs,
    )

    private fun modelAt(
        elapsedMs: Long,
        samples: List<TraceSample> = emptyList(),
        segments: List<PlanSegmentDto>? = twoStep,
        plannedDurationSec: Int? = null,
        extensionSec: Int = 0,
    ): HrTraceModel {
        val timeline = guidanceTimeline(segments, plannedDurationSec)
        return hrTraceModel(
            samples = samples,
            status = guidanceStatus(timeline, GuidanceRun(T0, extensionSec), T0 + elapsedMs),
            domain = bpmDomain(timeline),
            nowMs = T0 + elapsedMs,
        )
    }

    // ---- the window's shape -------------------------------------------------------------

    @Test
    @DisplayName("thirty seconds of history put the now-line at two thirds, with the lookahead labelled inside it")
    fun windowGeometry() {
        val model = modelAt(elapsedMs = 600_000)

        assertEquals(240.0, model.nowX)
        assertEquals(TRACE_LOGICAL_WIDTH * TRACE_NOW_FRACTION, model.nowX, 1e-9)
        assertEquals(listOf("now", "+10s"), model.timeTicks.map { it.label })
        assertEquals(240.0, model.timeTicks[0].x)
        assertEquals(320.0, model.timeTicks[1].x)
    }

    @Test
    @DisplayName("the lookahead is half the history, which is what two thirds forces it to be")
    fun lookaheadIsForcedByTheNowLine() {
        assertEquals(15, TRACE_FUTURE_SEC)
        assertEquals(45, TRACE_SPAN_SEC)
    }

    // ---- placing the beats -----------------------------------------------------------------

    @Test
    @DisplayName("a beat lands at its own timestamp: the oldest at the left edge, the newest on the now-line")
    fun samplesMapByTimestamp() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 30_000, bpm = 130),
                TraceSample(now - 15_000, bpm = 135),
                TraceSample(now, bpm = 140),
            ),
        )

        assertEquals(listOf(0.0, 120.0, 240.0), model.points.map { it.x })
    }

    @Test
    @DisplayName("a beat older than the window is dropped rather than drawn off the edge")
    fun samplesOutsideTheWindowAreDropped() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 30_001, bpm = 128),
                TraceSample(now - 30_000, bpm = 130),
            ),
        )

        assertEquals(listOf(130), model.points.map { it.bpm })
    }

    @Test
    @DisplayName("the right edge is the lookahead's end: a stamp there draws at the edge, one past it is dropped")
    fun rightEdgeIsTheLookaheadEnd() {
        // A stamp ahead of the now-line can only be clock skew, but the edge
        // rule must still be exact: inside the span draws, past it is dropped —
        // the same inclusive rule the left edge already pins.
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now, bpm = 140),
                TraceSample(now + 15_000, bpm = 142),
                TraceSample(now + 15_001, bpm = 144),
            ),
        )

        assertEquals(listOf(140, 142), model.points.map { it.bpm })
        assertEquals(360.0, model.points.last().x)
    }

    @Test
    @DisplayName("bpm becomes y against a fixed axis, and a reading off the top clamps to the edge")
    fun bpmMapsToTheAxis() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 3_000, bpm = 160),
                TraceSample(now - 2_000, bpm = 120),
                TraceSample(now - 1_000, bpm = 240),
                TraceSample(now, bpm = 40),
            ),
        )

        assertEquals(BpmDomain(90, 190), model.domain)
        assertEquals(listOf(45.0, 105.0, 0.0, 150.0), model.points.map { it.y })
    }

    // ---- gaps ----------------------------------------------------------------------------

    @Test
    @DisplayName("a silence breaks the line: the trace is two runs, not one drawn across the hole")
    fun aGapSplitsThePolyline() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 20_000, bpm = 130),
                TraceSample(now - 19_000, bpm = 131),
                TraceSample(now - 8_000, bpm = 129),
                TraceSample(now - 7_000, bpm = 130),
            ),
        )

        assertEquals(2, model.polylines.size)
        assertEquals(listOf(130, 131), model.polylines[0].points.map { it.bpm })
        assertEquals(listOf(129, 130), model.polylines[1].points.map { it.bpm })
    }

    @Test
    @DisplayName("three seconds is still one line; a millisecond more is a gap — the connection's own threshold")
    fun theGapThresholdIsTheConnectionsOwn() {
        val now = T0 + 600_000
        fun runsAcross(stepMs: Long) = modelAt(
            elapsedMs = 600_000,
            samples = listOf(TraceSample(now - 10_000, 130), TraceSample(now - 10_000 + stepMs, 131)),
        ).polylines.size

        assertEquals(3_000L, TRACE_GAP_THRESHOLD_MS)
        assertEquals(1, runsAcross(3_000))
        assertEquals(2, runsAcross(3_001))
    }

    @Test
    @DisplayName("a clock corrected backwards mid-capture breaks the line rather than running it back on itself")
    fun aBackwardsStepIsADiscontinuity() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 9_000, bpm = 130),
                TraceSample(now - 10_000, bpm = 131),
            ),
        )

        assertEquals(2, model.polylines.size)
    }

    @Test
    @DisplayName("two beats sharing a millisecond stay on one line — the ring stores them verbatim")
    fun aZeroStepIsNotAGap() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(TraceSample(now - 5_000, 130), TraceSample(now - 5_000, 131)),
        )

        assertEquals(1, model.polylines.size)
    }

    @Test
    @DisplayName("a lone beat between two silences is still a beat")
    fun aRunOfOneSurvives() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(
                TraceSample(now - 25_000, 128),
                TraceSample(now - 15_000, 130),
                TraceSample(now - 5_000, 132),
            ),
        )

        assertEquals(listOf(1, 1, 1), model.polylines.map { it.points.size })
    }

    @Test
    @DisplayName("an empty window draws nothing at all rather than an empty line")
    fun noSamplesNoPolylines() {
        val model = modelAt(elapsedMs = 600_000)

        assertTrue(model.polylines.isEmpty())
        assertTrue(model.points.isEmpty())
        assertFalse(model.currentOutOfBand)
    }

    // ---- out of band, per timestamp ---------------------------------------------------------

    @Test
    @DisplayName("a beat is judged against the band that was running when it happened, not the one running now")
    fun outOfBandUsesTheSamplesOwnSegment() {
        // Two seconds after the step from warmup (120–140) to work (140–160):
        // the window still shows the warmup, and 155 was over its ceiling then
        // even though it is inside the band being held now.
        val now = T0 + 302_000
        val model = modelAt(
            elapsedMs = 302_000,
            samples = listOf(
                TraceSample(now - 10_000, bpm = 155),
                TraceSample(now, bpm = 155),
            ),
        )

        assertEquals(listOf(true, false), model.points.map { it.outOfBand })
    }

    @Test
    @DisplayName("the boundary itself belongs to the arriving segment, to the millisecond")
    fun outOfBandAtTheExactBoundary() {
        // holdsAt is start-inclusive, end-exclusive: the last millisecond of the
        // warmup (120–140) judges 155 against the warmup; the boundary
        // millisecond judges it against work (140–160). One beat apart, two
        // verdicts, both correct.
        val model = modelAt(
            elapsedMs = 302_000,
            samples = listOf(
                TraceSample(T0 + 299_999, bpm = 155),
                TraceSample(T0 + 300_000, bpm = 155),
            ),
        )

        assertEquals(listOf(true, false), model.points.map { it.outOfBand })
    }

    @Test
    @DisplayName("a beat exactly on a bound is inside the band it is holding")
    fun boundsAreInclusive() {
        // Mid-way through `work`, whose band is 140–160.
        val now = T0 + 400_000
        val model = modelAt(
            elapsedMs = 400_000,
            samples = listOf(
                TraceSample(now - 3_000, bpm = 140),
                TraceSample(now - 2_000, bpm = 160),
                TraceSample(now - 1_000, bpm = 161),
                TraceSample(now, bpm = 139),
            ),
        )

        assertEquals(listOf(false, false, true, true), model.points.map { it.outOfBand })
    }

    @Test
    @DisplayName("a beat ridden before the timeline started belongs to no segment and earns no mark")
    fun beatsBeforeTheStartAreNotJudged() {
        val model = hrTraceModel(
            samples = listOf(TraceSample(T0 - 10_000, bpm = 200)),
            status = guidanceStatus(guidanceTimeline(twoStep, null), GuidanceRun(T0), T0 + 5_000),
            domain = bpmDomain(guidanceTimeline(twoStep, null)),
            nowMs = T0 + 5_000,
        )

        assertEquals(listOf(false), model.points.map { it.outOfBand })
    }

    @Test
    @DisplayName("the bang follows the newest beat, and goes out when the strap stops confirming it")
    fun currentOutOfBandNeedsARecentBeat() {
        val now = T0 + 400_000
        fun bangWith(ageMs: Long) = modelAt(
            elapsedMs = 400_000,
            samples = listOf(TraceSample(now - ageMs, bpm = 190)),
        ).currentOutOfBand

        assertTrue(bangWith(1_000))
        assertTrue(bangWith(3_000))
        assertFalse(bangWith(3_001))
    }

    @Test
    @DisplayName("a beat back inside the band puts the bang out even while older beats are still marked")
    fun currentOutOfBandFollowsTheNewestBeat() {
        val now = T0 + 400_000
        val model = modelAt(
            elapsedMs = 400_000,
            samples = listOf(TraceSample(now - 2_000, bpm = 190), TraceSample(now, bpm = 150)),
        )

        assertEquals(listOf(true, false), model.points.map { it.outOfBand })
        assertFalse(model.currentOutOfBand)
    }

    // ---- bands ---------------------------------------------------------------------------

    @Test
    @DisplayName("the band steps at the boundary, and the segment still to come is marked as ahead")
    fun bandsStepAndApproach() {
        // Five seconds before the step: the warmup band runs from the left edge
        // to 280, and the work band from there to the window's right edge.
        val model = modelAt(elapsedMs = 295_000)

        assertEquals(2, model.bands.size)
        assertEquals(0.0, model.bands[0].x0)
        assertEquals(280.0, model.bands[0].x1)
        assertFalse(model.bands[0].ahead)
        assertEquals(280.0, model.bands[1].x0)
        assertEquals(360.0, model.bands[1].x1)
        assertTrue(model.bands[1].ahead)
    }

    @Test
    @DisplayName("a segment outside the window draws no band at all")
    fun bandsOutsideTheWindowAreDropped() {
        assertEquals(1, modelAt(elapsedMs = 100_000).bands.size)
    }

    @Test
    @DisplayName("the band's edges are the segment's bounds, and a missing bound is a missing edge")
    fun bandEdgesAreOptional() {
        val oneSided = listOf(segment(600, min = 140, label = "floor"), segment(600, max = 160))
        val timeline = guidanceTimeline(oneSided, null)
        val model = hrTraceModel(
            samples = emptyList(),
            status = guidanceStatus(timeline, GuidanceRun(T0), T0 + 595_000),
            domain = bpmDomain(timeline),
            nowMs = T0 + 595_000,
        )

        // Floor-only is open-topped; ceiling-only is open-bottomed. The axis is
        // 110–190 over 150 units, so a bound at 140 sits at 93.75.
        assertEquals(BpmDomain(110, 190), bpmDomain(timeline))
        assertNull(model.bands[0].yTop)
        assertEquals(93.75, model.bands[0].yBot)
        assertNull(model.bands[1].yBot)
        assertEquals(56.25, model.bands[1].yTop)
        assertEquals("floor ≥140", model.bands[0].caption?.drawn)
        assertEquals("floor, at least 140 beats per minute", model.bands[0].caption?.spoken)
        assertEquals("≤160", model.bands[1].caption?.drawn)
    }

    @Test
    @DisplayName("a boundless segment draws no band, so nothing implies a target it does not have")
    fun boundlessSegmentsDrawNoBand() {
        val model = modelAt(elapsedMs = 10_000, segments = listOf(segment(600)))

        assertTrue(model.bands.isEmpty())
    }

    @Test
    @DisplayName("a segmentless guide draws the trace against nothing, which is the honest picture")
    fun segmentlessDrawsNoBand() {
        val now = T0 + 600_000
        val model = modelAt(
            elapsedMs = 600_000,
            samples = listOf(TraceSample(now, bpm = 136)),
            segments = null,
            plannedDurationSec = 2_400,
        )

        assertTrue(model.bands.isEmpty())
        assertEquals(UNTARGETED_BPM_DOMAIN, model.domain)
        assertFalse(model.points.single().outOfBand)
    }

    @Test
    @DisplayName("a boundary inside the window is marked; ones outside it are not")
    fun boundariesAreWindowed() {
        val model = modelAt(elapsedMs = 295_000)

        assertEquals(listOf(1), model.boundaries.map { it.segmentIndex })
        assertEquals(280.0, model.boundaries.single().x)
    }

    // ---- the axis -------------------------------------------------------------------------

    @Test
    @DisplayName("the axis pads the timeline's own extent and snaps to round numbers")
    fun domainIsPaddedAndSnapped() {
        assertEquals(BpmDomain(90, 190), bpmDomain(guidanceTimeline(twoStep, null)))
    }

    @Test
    @DisplayName("a narrow timeline is widened to a span worth drawing, symmetrically")
    fun narrowDomainsAreWidened() {
        val single = guidanceTimeline(listOf(segment(600, min = 140)), null)

        val domain = bpmDomain(single)

        assertEquals(BpmDomain(110, 170), domain)
        assertTrue(domain.span >= TRACE_DOMAIN_MIN_SPAN_BPM)
    }

    @Test
    @DisplayName("the axis never reaches below zero, whatever a hand-edited plan asks for")
    fun domainIsNotNegative() {
        assertEquals(0, bpmDomain(guidanceTimeline(listOf(segment(60, max = 5)), null)).lo)
    }

    @Test
    @DisplayName("a guide with no timeline gets the fixed untargeted axis rather than one fitted to the data")
    fun untargetedDomain() {
        assertEquals(UNTARGETED_BPM_DOMAIN, bpmDomain(GuidanceTimeline.OPEN))
        assertEquals(UNTARGETED_BPM_DOMAIN, bpmDomain(guidanceTimeline(null, 2_400)))
    }

    @Test
    @DisplayName("appended minutes move the end of the timeline, never the axis under it")
    fun extensionDoesNotMoveTheAxis() {
        assertEquals(
            modelAt(elapsedMs = 60_000, segments = listOf(segment(1_800, min = 122, max = 138))).domain,
            modelAt(
                elapsedMs = 1_900_000,
                segments = listOf(segment(1_800, min = 122, max = 138)),
                extensionSec = 600,
            ).domain,
        )
    }

    @Test
    @DisplayName("ticks are round bpm values inside the axis, and every one gets a rule")
    fun ticksAreRoundAndInside() {
        assertEquals(listOf(100, 150), bpmTicks(BpmDomain(90, 190)))
        assertEquals(listOf(100, 120, 140, 160), bpmTicks(BpmDomain(90, 170)))
        assertEquals(listOf(100, 150, 200), bpmTicks(UNTARGETED_BPM_DOMAIN))

        val model = modelAt(elapsedMs = 600_000)
        assertEquals(listOf(100, 150), model.gridlines.map { it.bpm })
        assertEquals(listOf(135.0, 60.0), model.gridlines.map { it.y })
    }

    @Test
    @DisplayName("a degenerate axis still labels itself rather than dividing by zero")
    fun degenerateDomainStillTicks() {
        assertEquals(listOf(140), bpmTicks(BpmDomain(140, 140)))
    }

    // ---- the session strip -------------------------------------------------------------------

    @Test
    @DisplayName("the strip is one block per segment, filled to the cursor")
    fun stripBlocksAndCursor() {
        val thirds = listOf(
            segment(60, min = 100, label = "a"),
            segment(60, min = 150, label = "b"),
            segment(60, min = 200, label = "c"),
        )

        val strip = guidanceStrip(statusAt(thirds, elapsedMs = 90_000))!!

        assertEquals(listOf(0.0, 120.0, 240.0), strip.blocks.map { it.x0 })
        assertEquals(listOf(120.0, 240.0, 360.0), strip.blocks.map { it.x1 })
        assertEquals(
            listOf(StripState.DONE, StripState.CURRENT, StripState.AHEAD),
            strip.blocks.map { it.state },
        )
        assertEquals(listOf("a", "b", "c"), strip.blocks.map { it.label })
        assertEquals(180.0, strip.cursorX)
    }

    @Test
    @DisplayName("a segment's height ranks its band against the rest of the timeline")
    fun stripIntensityRanksTheBands() {
        val thirds = listOf(
            segment(60, min = 100),
            segment(60, min = 150),
            segment(60, min = 200),
        )

        val strip = guidanceStrip(statusAt(thirds, elapsedMs = 0))!!

        assertEquals(listOf(0.0, 0.5, 1.0), strip.blocks.map { it.intensity })
    }

    @Test
    @DisplayName("a timeline asking the same thing throughout draws equal blocks, not full-height ones")
    fun stripIntensityOfAFlatTimeline() {
        val flat = listOf(segment(60, min = 130, max = 140), segment(60, min = 130, max = 140))

        val strip = guidanceStrip(statusAt(flat, elapsedMs = 0))!!

        assertEquals(listOf(0.5, 0.5), strip.blocks.map { it.intensity })
    }

    @Test
    @DisplayName("appended time is its own block: the plan's segments keep the length the plan gave them")
    fun stripDrawsTheExtensionSeparately() {
        val thirds = listOf(segment(60, min = 100), segment(60, min = 150), segment(60, min = 200))

        val strip = guidanceStrip(statusAt(thirds, elapsedMs = 90_000, extensionSec = 60))!!

        assertEquals(4, strip.blocks.size)
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0), strip.blocks.map { it.x0 })
        assertEquals(StripState.EXTENSION, strip.blocks.last().state)
        assertEquals(360.0, strip.blocks.last().x1)
        assertEquals(135.0, strip.cursorX)
    }

    @Test
    @DisplayName("a segmentless strip is one block: the session, and whatever was added to it")
    fun segmentlessStrip() {
        val strip = guidanceStrip(
            statusAt(segments = null, elapsedMs = 60_000, plannedDurationSec = 2_400, extensionSec = 300),
        )!!

        assertEquals(2, strip.blocks.size)
        assertEquals(StripState.CURRENT, strip.blocks[0].state)
        assertEquals(320.0, strip.blocks[0].x1)
        assertEquals(StripState.EXTENSION, strip.blocks[1].state)
        assertNull(strip.blocks[0].intensity)
    }

    @Test
    @DisplayName("a finished ride fills the strip and parks the cursor at the end")
    fun stripWhenDone() {
        val thirds = listOf(segment(60, min = 100), segment(60, min = 150), segment(60, min = 200))

        val strip = guidanceStrip(statusAt(thirds, elapsedMs = 500_000))!!

        assertTrue(strip.blocks.all { it.state == StripState.DONE })
        assertEquals(360.0, strip.cursorX)
    }

    @Test
    @DisplayName("an open-ended ride has no proportion to draw, so there is no strip")
    fun openEndedHasNoStrip() {
        assertNull(guidanceStrip(statusAt(segments = null, elapsedMs = 60_000)))
    }
}
