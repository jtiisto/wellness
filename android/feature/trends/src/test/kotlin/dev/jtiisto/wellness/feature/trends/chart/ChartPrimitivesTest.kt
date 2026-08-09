package dev.jtiisto.wellness.feature.trends.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The range window, sparse tick selection, and the two rendering rules that
 * decide what a number looks like on screen.
 */
class ChartPrimitivesTest {

    // ---- range window ------------------------------------------------------

    @Test
    @DisplayName("rangeStart: each range subtracts its own day count from today")
    fun rangeStartPerRange() {
        assertEquals("2026-07-11", rangeStart("4w", "2026-08-08"))
        assertEquals("2026-05-16", rangeStart("12w", "2026-08-08"))
        assertEquals("2026-02-07", rangeStart("6m", "2026-08-08"))
    }

    @Test
    @DisplayName("rangeStart: All has no lower bound")
    fun rangeStartAll() {
        assertNull(rangeStart("all", "2026-08-08"))
    }

    @Test
    @DisplayName("rangeStart: an unrecognised id degrades to All rather than throwing")
    fun rangeStartUnknownId() {
        // A preference written by a future build is a thing this one has to survive.
        assertNull(rangeStart("2y", "2026-08-08"))
        assertNull(rangeStart("", "2026-08-08"))
    }

    @Test
    @DisplayName("rangeStart crosses month and year boundaries by the calendar")
    fun rangeStartBoundaries() {
        assertEquals("2025-12-04", rangeStart("4w", "2026-01-01"))
        // 2028 is a leap year: 28 days back from March 1 lands on February 2.
        assertEquals("2028-02-02", rangeStart("4w", "2028-03-01"))
    }

    @Test
    @DisplayName("RANGES carries the four ids the persisted preference can hold")
    fun rangeInventory() {
        assertEquals(listOf("4w", "12w", "6m", "all"), RANGES.map { it.id })
        assertEquals(listOf(28, 84, 182, null), RANGES.map { it.days })
    }

    // ---- spread ------------------------------------------------------------

    @Test
    @DisplayName("spread: a list no longer than n is taken whole")
    fun spreadShortList() {
        assertEquals(listOf(0, 1, 2), spread(size = 3, n = 5))
        assertEquals(listOf(0, 1, 2, 3, 4), spread(size = 5, n = 5))
    }

    @Test
    @DisplayName("spread: first and last are always included")
    fun spreadIncludesEnds() {
        val picked = spread(size = 40, n = 5)
        assertEquals(0, picked.first())
        assertEquals(39, picked.last())
        assertEquals(listOf(0, 10, 20, 29, 39), picked)
    }

    @Test
    @DisplayName("spread: repeated indices collapse, so the result can be shorter than n")
    fun spreadDedupesIndices() {
        // 6 items into 5 slots: rounding picks 0, 1, 3, 4, 5 — no collision.
        assertEquals(listOf(0, 1, 3, 4, 5), spread(size = 6, n = 5))
        // 4 items into 3 slots: 0, 2, 3.
        assertEquals(listOf(0, 2, 3), spread(size = 4, n = 3))
    }

    @Test
    @DisplayName("spread: degenerate inputs")
    fun spreadDegenerate() {
        assertEquals(emptyList<Int>(), spread(size = 0, n = 5))
        assertEquals(emptyList<Int>(), spread(size = 5, n = 0))
        assertEquals(listOf(0), spread(size = 9, n = 1))
    }

    @Test
    @DisplayName("spread dedupes by INDEX, so equal-valued ticks both survive")
    fun spreadKeepsEqualValues() {
        // The trap deviation 10 exists for: two distinct positions whose labels
        // happen to match must both be picked, which value-equality would undo.
        val labels = listOf("07-01", "07-01", "07-01", "07-01", "07-01", "07-01")
        assertEquals(5, spread(labels.size, 5).size)
    }

    // ---- number rendering ---------------------------------------------------

    @Test
    @DisplayName("jsNumberString: an integral value has no decimal part")
    fun numberStringIntegral() {
        assertEquals("1", jsNumberString(1.0))
        assertEquals("0", jsNumberString(0.0))
        assertEquals("-4", jsNumberString(-4.0))
        assertEquals("1000", jsNumberString(1000.0))
    }

    @Test
    @DisplayName("jsNumberString: fractions keep their digits and a '.' separator")
    fun numberStringFractional() {
        assertEquals("4.57", jsNumberString(4.57))
        assertEquals("0.5", jsNumberString(0.5))
        assertEquals("-2.5", jsNumberString(-2.5))
        assertEquals("72.3", jsNumberString(72.3))
    }

    @Test
    @DisplayName("jsNumberString: negative zero prints as zero")
    fun numberStringNegativeZero() {
        // Reachable: round2 of a tiny negative coordinate lands here, and a
        // path reading "M -0 12" is a rendering bug people file tickets about.
        assertEquals("0", jsNumberString(-0.0))
    }

    @Test
    @DisplayName("formatNum is the same rendering under a display-side name")
    fun formatNumIsTheSame() {
        for (value in listOf(1.0, -0.0, 4.57, -2.5, 1234.5)) {
            assertEquals(jsNumberString(value), formatNum(value))
        }
    }

    // ---- y axis --------------------------------------------------------------

    @Test
    @DisplayName("axisTicks: a label repeating the previous one is suppressed, its gridline is not")
    fun axisTicksSuppressDuplicateLabels() {
        // An integer format over a 0..1.5 domain yields "0, 1, 1" — the third
        // tick keeps its gridline and loses its text.
        val ticks = axisTicks(0.0, 1.5, 4) { jsNumberString(jsRound(it).toDouble()) }
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), ticks.map { it.value })
        assertEquals(listOf("0", "1", null, "2"), ticks.map { it.label })
    }

    @Test
    @DisplayName("axisTicks: distinct labels all survive")
    fun axisTicksKeepDistinctLabels() {
        val ticks = axisTicks(0.0, 40.0, 4)
        assertEquals(listOf("0", "10", "20", "30", "40"), ticks.map { it.label })
    }

    @Test
    @DisplayName("axisTicks: a degenerate domain still draws one tick")
    fun axisTicksDegenerate() {
        val ticks = axisTicks(7.0, 7.0, 4)
        assertEquals(1, ticks.size)
        assertEquals("7", ticks.single().label)
    }

    @Test
    @DisplayName("TREND_SCREENS matches the ids the persisted screen preference holds")
    fun screenInventory() {
        assertEquals(
            listOf("overview", "strength", "cardio", "journal", "health"),
            TREND_SCREENS.map { it.id },
        )
        assertTrue(TREND_SCREENS.all { it.label.isNotBlank() })
    }
}
