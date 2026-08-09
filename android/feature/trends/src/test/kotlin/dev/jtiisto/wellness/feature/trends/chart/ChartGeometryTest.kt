package dev.jtiisto.wellness.feature.trends.chart

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The PWA's `test/js/trends-chart-logic.test.js`, transcribed case for case.
 *
 * Twenty-one flat tests over there, twenty-one here, in the same order and with
 * the same fixtures — they are synthetic geometry numbers, so they carry over
 * verbatim. No visual assertions: these pin data → geometry and nothing else.
 *
 * The extra block at the end has no JS counterpart. It pins the rounding
 * semantics the port depends on, which JavaScript gets for free from
 * `Math.round` and Kotlin only gets from the right one of three rounding
 * functions.
 */
class ChartGeometryTest {

    // ---- scales ------------------------------------------------------------

    @Test
    @DisplayName("linearScale: maps domain to range linearly")
    fun linearScaleMapsLinearly() {
        val s = linearScale(0.0, 10.0, 0.0, 100.0)
        assertEquals(0.0, s(0.0))
        assertEquals(50.0, s(5.0))
        assertEquals(100.0, s(10.0))
    }

    @Test
    @DisplayName("linearScale: inverted range (SVG y) works")
    fun linearScaleInvertedRange() {
        val s = linearScale(0.0, 10.0, 200.0, 0.0)
        assertEquals(200.0, s(0.0))
        assertEquals(0.0, s(10.0))
    }

    @Test
    @DisplayName("linearScale: degenerate domain maps to range midpoint")
    fun linearScaleDegenerateDomain() {
        val s = linearScale(5.0, 5.0, 0.0, 100.0)
        assertEquals(50.0, s(5.0))
        assertEquals(50.0, s(999.0))
    }

    @Test
    @DisplayName("dayIndex: local-date day offsets, month/year boundaries exact")
    fun dayIndexBoundaries() {
        assertEquals(0, dayIndex("2026-07-07", "2026-07-07"))
        assertEquals(6, dayIndex("2026-07-07", "2026-07-01"))
        assertEquals(1, dayIndex("2026-03-01", "2026-02-28")) // non-leap
        assertEquals(1, dayIndex("2027-01-01", "2026-12-31"))
        assertEquals(-6, dayIndex("2026-07-01", "2026-07-07")) // negative ok
    }

    @Test
    @DisplayName("niceTicks: 1/2/5 steps, ticks inside the domain")
    fun niceTicksSteps() {
        assertEquals(listOf(0.0, 50.0, 100.0), niceTicks(0.0, 100.0, 4)) // step 50 (2.5 -> 5 ceil)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), niceTicks(0.0, 40.0, 4))
        val ticks = niceTicks(83.0, 117.0, 4)
        assertTrue(ticks.all { it in 83.0..117.0 }, "ticks escaped the domain: $ticks")
        assertTrue(ticks.size >= 2)
    }

    @Test
    @DisplayName("niceTicks: degenerate domain returns the single value")
    fun niceTicksDegenerate() {
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0, 4))
    }

    // ---- series ------------------------------------------------------------

    private data class Record(val x: Double, val v: Double?)

    @Test
    @DisplayName("seriesToPoints: skips null accessor values, applies both scales")
    fun seriesToPointsSkipsNulls() {
        val xs = linearScale(0.0, 10.0, 0.0, 100.0)
        val ys = linearScale(0.0, 10.0, 100.0, 0.0)
        val points = seriesToPoints(
            listOf(Record(0.0, 0.0), Record(5.0, null), Record(10.0, 10.0)),
            { it.x },
            { it.v },
            xs,
            ys,
        )
        assertEquals(2, points.size)
        assertEquals(listOf(0.0, 100.0), listOf(points[0].x, points[0].y))
        assertEquals(listOf(100.0, 0.0), listOf(points[1].x, points[1].y))
    }

    @Test
    @DisplayName("linePath: M/L path for 2+ points, empty for fewer")
    fun linePathExactString() {
        assertEquals("", linePath(emptyList()))
        assertEquals("", linePath(listOf(plainPoint(1.0, 2.0))))
        assertEquals(
            "M 1 2 L 3 4.57",
            linePath(listOf(plainPoint(1.0, 2.0), plainPoint(3.0, 4.567))),
        )
    }

    // ---- stepped band ------------------------------------------------------

    @Test
    @DisplayName("steppedBandRects: one rect per segment, clipped to the window")
    fun steppedBandRectsClipped() {
        val xs = linearScale(0.0, 10.0, 0.0, 100.0)
        val ys = linearScale(0.0, 200.0, 200.0, 0.0)
        val rects = steppedBandRects(
            listOf(
                BandSegment(x0 = -5.0, x1 = 4.0, min = 150.0, max = null), // clips left, open top
                BandSegment(x0 = 4.0, x1 = 10.0, min = 100.0, max = 180.0),
            ),
            0.0,
            10.0,
            xs,
            ys,
            0.0,
            200.0,
        )
        assertEquals(2, rects.size)
        // First: x from 0, open max clamps to the top edge (y=0).
        assertEquals(0.0, rects[0].x)
        assertEquals(0.0, rects[0].yTop)
        assertEquals(ys(150.0), rects[0].yBot)
        // Second: min/max both bound.
        assertEquals(ys(180.0), rects[1].yTop)
        assertEquals(ys(100.0), rects[1].yBot)
    }

    @Test
    @DisplayName("steppedBandRects: zero-width segments are dropped (gap = no rect)")
    fun steppedBandRectsDropsZeroWidth() {
        val xs = linearScale(0.0, 10.0, 0.0, 100.0)
        val ys = linearScale(0.0, 10.0, 100.0, 0.0)
        val rects = steppedBandRects(
            listOf(BandSegment(x0 = 12.0, x1 = 15.0, min = 1.0, max = 2.0)),
            0.0,
            10.0,
            xs,
            ys,
            0.0,
            100.0,
        )
        assertEquals(emptyList<BandRect>(), rects)
    }

    // ---- stacked bars ------------------------------------------------------

    @Test
    @DisplayName("stackedBarLayout: segment heights stack to the total, zero segs omitted")
    fun stackedBarLayoutStacks() {
        val xs = linearScale(-0.5, 1.5, 0.0, 100.0)
        val ys = linearScale(0.0, 100.0, 200.0, 0.0)
        val layout = stackedBarLayout(
            listOf(
                StackedWeek("w1", mapOf("a" to 40.0, "b" to 60.0)),
                StackedWeek("w2", mapOf("a" to 0.0, "b" to 30.0)),
            ),
            listOf("a", "b"),
            { index -> xs(index.toDouble()) },
            ys,
            20.0,
        )
        val bar1 = layout[0]
        assertEquals(2, bar1.segs.size)
        val total = bar1.segs.sumOf { it.h }
        assertEquals(jsRound(ys(0.0) - ys(100.0)), jsRound(total))
        // Bottom-up stacking: 'a' sits below 'b' (larger y = lower on screen).
        assertTrue(bar1.segs[0].y > bar1.segs[1].y)
        // Zero value omitted entirely.
        assertEquals(listOf("b"), layout[1].segs.map { it.key })
    }

    // ---- ribbon ------------------------------------------------------------

    @Test
    @DisplayName("ribbonCells: fractions per week; paused/zero-scheduled weeks are muted")
    fun ribbonCellFractions() {
        val xs = linearScale(0.0, 2.0, 0.0, 90.0)
        val cells = ribbonCells(
            listOf(
                RibbonWeek("w1", scheduledDays = 5, met = 4, partialDays = 1, missed = 0, paused = false),
                RibbonWeek("w2", scheduledDays = 0, met = 0, partialDays = 0, missed = 0, paused = true),
            ),
            { index -> xs(index.toDouble()) },
            20.0,
        )
        assertEquals(0.8, cells[0].met)
        assertEquals(0.2, cells[0].partial)
        assertEquals(0.0, cells[0].missed)
        assertFalse(cells[0].muted)
        assertTrue(cells[1].muted)
        assertEquals(0.0, cells[1].met)
    }

    // ---- sparkline / rolling mean / dots ------------------------------------

    @Test
    @DisplayName("sparklinePoints: polyline string; skips nulls; empty for <2 values")
    fun sparklinePointsString() {
        assertEquals("", sparklinePoints(listOf(5.0), 100.0, 20.0))
        val points = sparklinePoints(listOf(0.0, null, 10.0), 100.0, 20.0)
        assertEquals(2, points.split(" ").size)
        assertTrue(points.startsWith("0,20"), points) // min at the bottom
        assertTrue(points.endsWith("100,0"), points) // max at the top
    }

    @Test
    @DisplayName("rollingMean: date-aware trailing window, gaps use present values only")
    fun rollingMeanTrailingWindow() {
        val series = listOf(
            DatedValue("2026-07-01", 10.0),
            DatedValue("2026-07-02", 20.0),
            DatedValue("2026-07-09", 30.0), // 7-day gap: window excludes the first two
        )
        val means = rollingMean(series, 7)
        assertEquals(10.0, means[0].value)
        assertEquals(15.0, means[1].value)
        assertEquals(30.0, means[2].value)
    }

    @Test
    @DisplayName("rollingMean: null values contribute nothing")
    fun rollingMeanIgnoresNulls() {
        val means = rollingMean(
            listOf(DatedValue("2026-07-01", 10.0), DatedValue("2026-07-02", null)),
            7,
        )
        assertEquals(10.0, means[1].value)
    }

    @Test
    @DisplayName("dotSizeScale: sqrt-area scaling between minR and maxR")
    fun dotSizeScaleSqrtArea() {
        val s = dotSizeScale(2.0, 8.0, listOf(10.0, 40.0))
        assertEquals(2.0, s(10.0))
        assertEquals(8.0, s(40.0))
        val mid = s(25.0)
        assertTrue(mid > 2.0 && mid < 8.0, "midpoint radius out of range: $mid")
        // Constant values -> midpoint radius.
        assertEquals(5.0, dotSizeScale(2.0, 8.0, listOf(5.0, 5.0))(5.0))
    }

    @Test
    @DisplayName("coerceNumeric: numbers pass, numeric strings parse, text/booleans/null drop (F1)")
    fun coerceNumericTruthTable() {
        assertEquals(5.0, coerceNumeric(JsonPrimitive(5)))
        assertEquals(0.0, coerceNumeric(JsonPrimitive(0)))
        assertEquals(160.0, coerceNumeric(JsonPrimitive("160")))
        assertEquals(7.5, coerceNumeric(JsonPrimitive(" 7.5 ")))
        assertNull(coerceNumeric(JsonPrimitive("felt tired")))
        assertNull(coerceNumeric(JsonPrimitive("")))
        assertNull(coerceNumeric(JsonPrimitive("   ")))
        assertNull(coerceNumeric(JsonNull))
        assertNull(coerceNumeric(null))
        assertNull(coerceNumeric(JsonPrimitive(true)))
        assertNull(coerceNumeric(JsonPrimitive(Double.NaN)))
        assertNull(coerceNumeric(JsonPrimitive(Double.POSITIVE_INFINITY)))
        // Kotlin parses these where JS would not even try; both must still drop.
        assertNull(coerceNumeric(JsonPrimitive("Infinity")))
        assertNull(coerceNumeric(JsonPrimitive(false)))
        assertNull(coerceNumeric(buildJsonArray { add(JsonPrimitive(1)) }))
        assertNull(coerceNumeric(buildJsonObject { }))
    }

    @Test
    @DisplayName("coerceNumeric keeps a mixed note-era series chartable (F1 regression)")
    fun coerceNumericKeepsMixedSeriesChartable() {
        // One free-text value must not NaN-poison the scale of the numeric rest.
        val values = listOf(JsonPrimitive("felt tired"), JsonPrimitive(5), JsonPrimitive(7))
            .mapNotNull(::coerceNumeric)
        assertEquals(listOf(5.0, 7.0), values)
        val yScale = linearScale(values.min(), values.max(), 100.0, 10.0)
        assertTrue(yScale(5.0).isFinite())
        assertTrue(yScale(7.0).isFinite())
    }

    // ---- daily band segments -------------------------------------------------

    private data class BandItem(val x: Double, val band: BandBounds?)

    @Test
    @DisplayName("dailyBandSegments: merges equal consecutive bands, splits on change (v2 HRV)")
    fun dailyBandSegmentsMerge() {
        val items = listOf(
            BandItem(0.0, BandBounds(25.0, 31.0)),
            BandItem(1.0, BandBounds(25.0, 31.0)),
            BandItem(2.0, BandBounds(26.0, 32.0)), // baseline moved
            BandItem(3.0, BandBounds(26.0, 32.0)),
        )
        assertEquals(
            listOf(
                BandSegment(x0 = 0.0, x1 = 2.0, min = 25.0, max = 31.0),
                BandSegment(x0 = 2.0, x1 = 4.0, min = 26.0, max = 32.0),
            ),
            dailyBandSegments(items, { it.x }, { it.band }),
        )
    }

    @Test
    @DisplayName("dailyBandSegments: null bands are gaps; date gaps with equal bands still merge")
    fun dailyBandSegmentsGaps() {
        val items = listOf(
            BandItem(0.0, BandBounds(25.0, 31.0)),
            BandItem(1.0, null), // watch off — gap
            BandItem(2.0, BandBounds(25.0, 31.0)),
            BandItem(5.0, BandBounds(25.0, 31.0)), // missing days, same baseline
        )
        assertEquals(
            listOf(
                BandSegment(x0 = 0.0, x1 = 1.0, min = 25.0, max = 31.0),
                BandSegment(x0 = 2.0, x1 = 6.0, min = 25.0, max = 31.0),
            ),
            dailyBandSegments(items, { it.x }, { it.band }),
        )
    }

    @Test
    @DisplayName("dailyBandSegments: empty and single-item inputs")
    fun dailyBandSegmentsEdges() {
        assertEquals(
            emptyList<BandSegment>(),
            dailyBandSegments(emptyList<BandItem>(), { it.x }, { it.band }),
        )
        assertEquals(
            listOf(BandSegment(x0 = 4.0, x1 = 5.0, min = 20.0, max = 30.0)),
            dailyBandSegments(listOf(BandItem(4.0, BandBounds(20.0, 30.0))), { it.x }, { it.band }),
        )
    }

    // ---- rounding (no JS counterpart — the port's own hazard) ----------------

    @Test
    @DisplayName("jsRound: ties go up toward +infinity, negatives included")
    fun jsRoundTiesTowardPositiveInfinity() {
        assertEquals(3L, jsRound(2.5))
        assertEquals(2L, jsRound(2.4))
        // kotlin.math.round would answer -3, -2 and 0 here: half-to-even, not
        // half-up, and wrong for every negative delta a stat tile shows.
        assertEquals(-2L, jsRound(-2.5))
        assertEquals(-1L, jsRound(-1.5))
        assertEquals(0L, jsRound(-0.5))
    }

    @Test
    @DisplayName("round2: two decimals with the same tie rule, non-finite passes through")
    fun round2NegativeHalves() {
        assertEquals(4.57, round2(4.567))
        assertEquals(-4.57, round2(-4.567))
        // -1.005 * 100 is -100.49999... in binary, so this is not a tie at all;
        // -0.125 is, and it must land on -0.12 rather than -0.13.
        assertEquals(-0.12, round2(-0.125))
        assertEquals(0.13, round2(0.125))
        assertEquals(0.0, round2(0.0))
        assertTrue(round2(Double.NaN).isNaN())
    }

    @Test
    @DisplayName("linePath serializes negative and integral coordinates the JS way")
    fun linePathNegativeCoordinates() {
        assertEquals(
            "M -1 0 L 2.5 -3.33",
            linePath(listOf(plainPoint(-1.0, -0.0), plainPoint(2.5, -3.3333))),
        )
    }
}
