package dev.jtiisto.wellness.core.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Which point of a chart the finger is on, and which one is pinned.
 *
 * Deliberately index-based and geometry-free: the state knows where the points
 * are *on screen* — nothing about dates, values, scales or series. Phase 6's
 * chart geometry hands it the x positions it computed and reads back an index,
 * so the two can be tested and changed independently.
 *
 * Anchors are assumed monotonically non-decreasing, as a plotted x axis is.
 */
@Stable
class ChartScrubState {

    /** The point under the finger right now, or null when nothing is being scrubbed. */
    var activeIndex: Int? by mutableStateOf(null)
        private set

    /** The point left showing after a tap. Survives the finger lifting. */
    var pinnedIndex: Int? by mutableStateOf(null)
        private set

    /** What the tooltip should show: the live scrub wins over the pin. */
    val displayIndex: Int? get() = activeIndex ?: pinnedIndex

    private var anchors: FloatArray = FloatArray(0)

    /**
     * Adopt the point positions, in pixels, from the layout that just measured.
     *
     * An empty series clears everything: a tooltip pinned to point 4 of a chart
     * that no longer has one is worse than no tooltip.
     */
    fun updateAnchors(xPositionsPx: FloatArray) {
        anchors = xPositionsPx.copyOf()
        if (anchors.isEmpty()) {
            activeIndex = null
            pinnedIndex = null
            return
        }
        val last = anchors.lastIndex
        activeIndex = activeIndex?.coerceAtMost(last)
        pinnedIndex = pinnedIndex?.coerceAtMost(last)
    }

    /** Snap to the point nearest [xPx]. Past either end, that is the endpoint. */
    fun scrubTo(xPx: Float) {
        activeIndex = nearestIndex(xPx)
    }

    /** The finger left, or the gesture was cancelled. The pin is not affected. */
    fun endScrub() {
        activeIndex = null
    }

    /** A tap: pin the nearest point, or unpin it if it was already pinned. */
    fun togglePinAt(xPx: Float) {
        val index = nearestIndex(xPx) ?: return
        pinnedIndex = if (pinnedIndex == index) null else index
    }

    fun clearPin() {
        pinnedIndex = null
    }

    /**
     * The nearest anchor, ties going to the lowest index.
     *
     * Exactly halfway between two points is a real case — it is where the
     * finger sits when someone is trying to read the gap — and it has to
     * resolve the same way every frame, or the tooltip flickers between two
     * values while the finger holds still.
     */
    private fun nearestIndex(xPx: Float): Int? {
        if (anchors.isEmpty()) return null
        var best = 0
        var bestDistance = abs(anchors[0] - xPx)
        for (index in 1..anchors.lastIndex) {
            val distance = abs(anchors[index] - xPx)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }
}

@Composable
fun rememberChartScrubState(): ChartScrubState = remember { ChartScrubState() }
