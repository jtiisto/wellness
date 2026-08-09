package dev.jtiisto.wellness.core.ui.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The scrub contract, pinned before Phase 6 builds a chart on it.
 *
 * Snapping is the part users feel and the part that is invisible in a
 * screenshot: a tie resolved differently on two consecutive frames is a tooltip
 * that flickers while the finger holds still.
 */
class ChartScrubStateTest {

    private fun stateWith(vararg anchors: Float) = ChartScrubState().apply {
        updateAnchors(anchors.toList().toFloatArray())
    }

    @Test
    @DisplayName("scrubbing snaps to the nearest point")
    fun snapsToNearest() {
        val state = stateWith(0f, 100f, 200f, 300f)

        state.scrubTo(0f)
        assertEquals(0, state.activeIndex)

        state.scrubTo(140f)
        assertEquals(1, state.activeIndex)

        state.scrubTo(160f)
        assertEquals(2, state.activeIndex)

        state.scrubTo(299f)
        assertEquals(3, state.activeIndex)
    }

    @Test
    @DisplayName("exactly halfway resolves to the lower index, every time")
    fun tiesGoLow() {
        val state = stateWith(0f, 100f, 200f)
        state.scrubTo(50f)
        assertEquals(0, state.activeIndex)
        state.scrubTo(150f)
        assertEquals(1, state.activeIndex)
        // Same input, same answer: the tie rule is not order-dependent.
        state.scrubTo(50f)
        assertEquals(0, state.activeIndex)
    }

    @Test
    @DisplayName("past either end, the endpoint is the answer")
    fun clampsToEndpoints() {
        val state = stateWith(10f, 20f, 30f)
        state.scrubTo(-500f)
        assertEquals(0, state.activeIndex)
        state.scrubTo(5_000f)
        assertEquals(2, state.activeIndex)
    }

    @Test
    @DisplayName("duplicate x positions resolve to the lowest of them")
    fun duplicateAnchors() {
        val state = stateWith(0f, 50f, 50f, 50f, 100f)
        state.scrubTo(50f)
        assertEquals(1, state.activeIndex)
    }

    @Test
    @DisplayName("a single point absorbs every scrub")
    fun singlePoint() {
        val state = stateWith(42f)
        state.scrubTo(-1f)
        assertEquals(0, state.activeIndex)
        state.scrubTo(999f)
        assertEquals(0, state.activeIndex)
    }

    @Test
    @DisplayName("an empty series is a no-op: nothing to scrub, nothing to pin")
    fun emptySeriesIsInert() {
        val state = ChartScrubState()
        state.updateAnchors(FloatArray(0))
        state.scrubTo(100f)
        state.togglePinAt(100f)
        assertNull(state.activeIndex)
        assertNull(state.pinnedIndex)
        assertNull(state.displayIndex)
    }

    @Test
    @DisplayName("emptying a series clears a pin that no longer has a point")
    fun emptyingClearsState() {
        val state = stateWith(0f, 100f)
        state.togglePinAt(100f)
        state.scrubTo(0f)
        state.updateAnchors(FloatArray(0))
        assertNull(state.activeIndex)
        assertNull(state.pinnedIndex)
    }

    @Test
    @DisplayName("a shorter series pulls the pin back to the last point")
    fun shrinkingClampsThePin() {
        val state = stateWith(0f, 100f, 200f, 300f)
        state.togglePinAt(300f)
        assertEquals(3, state.pinnedIndex)
        state.updateAnchors(floatArrayOf(0f, 100f))
        assertEquals(1, state.pinnedIndex)
    }

    /** Clamped by its own assignment, so it gets its own case. */
    @Test
    @DisplayName("a shorter series pulls the live scrub back to the last point")
    fun shrinkingClampsTheScrub() {
        val state = stateWith(0f, 100f, 200f, 300f)
        state.scrubTo(300f)
        assertEquals(3, state.activeIndex)
        state.updateAnchors(floatArrayOf(0f, 100f))
        assertEquals(1, state.activeIndex)
        assertNull(state.pinnedIndex, "clamping a scrub must not invent a pin")
    }

    @Test
    @DisplayName("a shorter series clamps a scrub and a pin independently")
    fun shrinkingClampsBothIndices() {
        val state = stateWith(0f, 100f, 200f, 300f)
        state.togglePinAt(0f)
        state.scrubTo(300f)
        state.updateAnchors(floatArrayOf(0f, 100f))
        assertEquals(0, state.pinnedIndex, "an in-range pin is left where it was")
        assertEquals(1, state.activeIndex)
    }

    @Test
    @DisplayName("tapping pins, tapping the same point unpins, tapping another moves the pin")
    fun pinLifecycle() {
        val state = stateWith(0f, 100f, 200f)

        state.togglePinAt(100f)
        assertEquals(1, state.pinnedIndex)

        state.togglePinAt(105f)
        assertNull(state.pinnedIndex, "the same point toggles off")

        state.togglePinAt(100f)
        state.togglePinAt(200f)
        assertEquals(2, state.pinnedIndex)

        state.clearPin()
        assertNull(state.pinnedIndex)
    }

    @Test
    @DisplayName("ending a scrub releases the finger's point but never the pin")
    fun endScrubKeepsThePin() {
        val state = stateWith(0f, 100f, 200f)
        state.togglePinAt(0f)
        state.scrubTo(200f)

        assertEquals(2, state.activeIndex)
        assertEquals(2, state.displayIndex, "the live scrub outranks the pin")

        state.endScrub()
        assertNull(state.activeIndex)
        assertEquals(0, state.pinnedIndex)
        assertEquals(0, state.displayIndex)
    }
}
