package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The signal ring's arc maths and the sentence a screen reader gets instead.
 *
 * The composable draws whatever these return, so the cases worth pinning are the
 * ones a glance at a screen would never catch: the circle closing exactly, the
 * lone slice that must not have a gap bitten out of it, and a category big
 * enough that the gaps would otherwise consume the ring.
 */
class SignalRingTest {

    private val gap = 6f
    private val fullCircle = 360f
    private val twelveOClock = -90f
    private val tolerance = 0.001f

    // ---- ringSlices -----------------------------------------------------------

    @Test
    @DisplayName("ringSlices: no habits draws nothing")
    fun noHabits() {
        assertTrue(ringSlices(0, 0, 0).isEmpty())
    }

    @Test
    @DisplayName("ringSlices: a lone slice is a full circle, gap-free, from 12 o'clock")
    fun singleSlice() {
        assertEquals(
            listOf(RingSlice(twelveOClock, fullCircle, RingRole.MET)),
            ringSlices(met = 1, partial = 0, notYet = 0),
        )
        assertEquals(
            listOf(RingSlice(twelveOClock, fullCircle, RingRole.NOT_YET)),
            ringSlices(met = 0, partial = 0, notYet = 1),
        )
    }

    @Test
    @DisplayName("ringSlices: two slices split the circle with a gap either side")
    fun twoSlices() {
        val slices = ringSlices(met = 1, partial = 0, notYet = 1)
        val sweep = (fullCircle - 2 * gap) / 2
        assertEquals(
            listOf(
                RingSlice(twelveOClock, sweep, RingRole.MET),
                RingSlice(twelveOClock + sweep + gap, sweep, RingRole.NOT_YET),
            ),
            slices,
        )
    }

    @Test
    @DisplayName("ringSlices: slices are state-sorted met → partial → not-yet")
    fun stateSorted() {
        val roles = ringSlices(met = 2, partial = 1, notYet = 3).map { it.role }
        assertEquals(
            listOf(
                RingRole.MET,
                RingRole.MET,
                RingRole.PARTIAL,
                RingRole.NOT_YET,
                RingRole.NOT_YET,
                RingRole.NOT_YET,
            ),
            roles,
        )
    }

    @Test
    @DisplayName("ringSlices: sweeps are equal and sweeps + gaps close the circle")
    fun circleCloses() {
        for (count in 2..12) {
            val slices = ringSlices(met = count, partial = 0, notYet = 0)
            assertEquals(count, slices.size)
            val first = slices.first().sweepDeg
            slices.forEach { assertEquals(first, it.sweepDeg, tolerance, "equal shares at n=$count") }
            assertEquals(
                fullCircle,
                slices.sumOf { it.sweepDeg.toDouble() }.toFloat() + count * gap,
                tolerance,
                "sweeps + gaps == 360° at n=$count",
            )
        }
    }

    @Test
    @DisplayName("ringSlices: slices start where the previous one's gap ends")
    fun slicesAreContiguous() {
        val slices = ringSlices(met = 1, partial = 2, notYet = 1)
        slices.zipWithNext { previous, next ->
            assertEquals(previous.startDeg + previous.sweepDeg + gap, next.startDeg, tolerance)
        }
        assertEquals(twelveOClock, slices.first().startDeg, tolerance)
        val last = slices.last()
        assertEquals(twelveOClock + fullCircle - gap, last.startDeg + last.sweepDeg, tolerance)
    }

    @Test
    @DisplayName("ringSlices: past 60 slices the gap yields so the arcs stay positive")
    fun hugeCategory() {
        val slices = ringSlices(met = 40, partial = 0, notYet = 60)
        assertEquals(100, slices.size)
        slices.forEach { assertTrue(it.sweepDeg > 0f, "a slice must still be an arc") }
        val span = slices.last().startDeg + slices.last().sweepDeg - slices.first().startDeg
        assertTrue(span <= fullCircle + tolerance, "the ring never laps itself: $span")
    }

    // ---- describeCategoryRollup -----------------------------------------------

    @Test
    @DisplayName("describeCategoryRollup: all three classes read as one sentence")
    fun allThreeClasses() {
        assertEquals(
            "2 of 3 done, avoidances held, 1 noted",
            describeCategoryRollup(
                CategoryRollup(
                    habitsMet = 2,
                    habitsNotYet = 1,
                    avoidances = 2,
                    observationsExpected = 3,
                    observationsNoted = 1,
                ),
            ),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: absent classes are absent from the sentence")
    fun degradesBySubtraction() {
        assertEquals(
            "1 of 2 done",
            describeCategoryRollup(CategoryRollup(habitsMet = 1, habitsPartial = 1)),
        )
        assertEquals(
            "avoidances held",
            describeCategoryRollup(CategoryRollup(avoidances = 3)),
        )
        assertEquals(
            "0 of 2 noted",
            describeCategoryRollup(CategoryRollup(observationsExpected = 2)),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: observations name their total only when alone")
    fun observationDenominator() {
        assertEquals(
            "2 of 2 noted",
            describeCategoryRollup(CategoryRollup(observationsExpected = 2, observationsNoted = 2)),
        )
        assertEquals(
            "1 of 1 done, 2 noted",
            describeCategoryRollup(
                CategoryRollup(habitsMet = 1, observationsExpected = 4, observationsNoted = 2),
            ),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: avoidances are held, broken, or counted")
    fun avoidancePhrasing() {
        assertEquals("avoidance held", describeCategoryRollup(CategoryRollup(avoidances = 1)))
        assertEquals(
            "avoidance broken",
            describeCategoryRollup(CategoryRollup(avoidances = 1, avoidancesBroken = 1)),
        )
        assertEquals(
            "avoidances broken",
            describeCategoryRollup(CategoryRollup(avoidances = 2, avoidancesBroken = 2)),
        )
        assertEquals(
            "1 of 3 avoidances broken",
            describeCategoryRollup(CategoryRollup(avoidances = 3, avoidancesBroken = 1)),
        )
    }
}
