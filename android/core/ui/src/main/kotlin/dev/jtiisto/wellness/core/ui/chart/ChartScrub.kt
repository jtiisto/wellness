package dev.jtiisto.wellness.core.ui.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import kotlin.math.abs

/**
 * Scrub and pin, in one gesture.
 *
 * A single `pointerInput` handles both, because two competing detectors on the
 * same chart would each have to decide what the other is doing. Horizontal
 * movement past the touch slop becomes a scrub and starts consuming events; a
 * release before that is a tap that toggles the pin. Nothing is consumed until
 * the slop is crossed, so a vertical drag still scrolls the page the chart
 * lives on — a chart you cannot scroll past is a trap.
 *
 * **Untested on the JVM, deliberately.** [ChartScrubState] carries all the
 * arithmetic and is covered; what happens here — slop, cancellation, tap versus
 * drag, who consumes what when a scrolling parent is competing — needs a real
 * pointer stream, so it is verified on a device and nowhere else. The Tools
 * tab's demo card exists to make that verification a ten-second job.
 */
fun Modifier.chartScrub(state: ChartScrubState): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travelled = 0f
        var scrubbing = false
        var tap = true

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!scrubbing && change.isConsumed) {
                // Something above us — a scrolling parent — took the gesture.
                tap = false
                break
            }
            if (!change.pressed) break

            travelled += change.positionChange().x
            if (!scrubbing && abs(travelled) > slop) {
                scrubbing = true
                tap = false
            }
            if (scrubbing) {
                state.scrubTo(change.position.x)
                change.consume()
            }
        }

        when {
            scrubbing -> state.endScrub()
            tap -> state.togglePinAt(down.position.x)
        }
    }
}

/**
 * The floating readout for the scrubbed point.
 *
 * A `Popup` rather than an overlay inside the chart so it can extend past the
 * plot area, and focusable only once the point is *pinned* — a focusable popup
 * during a drag would take the very events that are driving the drag. Dismiss
 * (a tap outside, or system back) clears the pin, which is the only way out
 * once the finger is gone.
 *
 * [anchor] is the scrubbed point in the chart's own coordinates; the tooltip
 * centres on it and slides back inside the window at either edge, so the last
 * point on a full-width chart reads as well as the first.
 */
@Composable
fun ChartScrubTooltip(
    state: ChartScrubState,
    label: String,
    values: List<Pair<String, String>>,
    anchor: IntOffset,
    modifier: Modifier = Modifier,
) {
    val palette = LogbookTheme.palette
    val pinnedOnly = state.activeIndex == null && state.pinnedIndex != null
    val margin = with(LocalDensity.current) { LogbookSpace.grid.roundToPx() * 2 }
    val positionProvider = remember(anchor, margin) { ScrubTooltipPosition(anchor, margin) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { state.clearPin() },
        properties = PopupProperties(focusable = pinnedOnly),
    ) {
        Surface(
            modifier = modifier.padding(LogbookSpace.grid * 2),
            color = palette.paper,
            contentColor = palette.ink,
            shape = LogbookShapes.soft,
            // The calendar-popup rule: a hairline edge and no shadow. A popup
            // floats above the screen, but it is still paper, and paper in this
            // system is bounded by a rule rather than lifted off the page.
            border = BorderStroke(LogbookSpace.hairline, palette.ruleStrong),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = LogbookSpace.grid * 2, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = LogbookTheme.type.eyebrow,
                    color = palette.inkSoft,
                )
                for ((series, value) in values) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2)) {
                        Text(
                            text = series,
                            style = LogbookTheme.type.meta,
                            color = palette.inkSoft,
                        )
                        Text(
                            text = value,
                            style = LogbookTheme.type.meta,
                            color = palette.ink,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Centre the tooltip over the scrubbed point, then keep it on screen.
 *
 * Compose only knows the popup's measured size at position time, which is
 * exactly what a fixed offset cannot account for: at the last anchor of a
 * full-width chart, a left-aligned tooltip hangs off the right edge. Clamping
 * against the window slides it back — the same motion as flipping it to the
 * anchor's other side, without a second layout pass.
 */
private class ScrubTooltipPosition(
    private val anchor: IntOffset,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centred = anchorBounds.left + anchor.x - popupContentSize.width / 2
        val rightLimit = windowSize.width - popupContentSize.width - marginPx
        val bottomLimit = windowSize.height - popupContentSize.height - marginPx
        return IntOffset(
            x = centred.coerceIn(marginPx, maxOf(marginPx, rightLimit)),
            y = (anchorBounds.top + anchor.y).coerceIn(marginPx, maxOf(marginPx, bottomLimit)),
        )
    }
}
