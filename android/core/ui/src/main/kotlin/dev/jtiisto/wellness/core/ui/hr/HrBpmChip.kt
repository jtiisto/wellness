package dev.jtiisto.wellness.core.ui.hr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.LiveSignalColors
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.liveSignalColors

/**
 * The live heart rate, as small as it can be and still be read at arm's length.
 *
 * A number and a tone dot, and nothing else — no icon, no unit, no device name,
 * and not even a container: the top bar is paper, and a pill on it would be a
 * second surface on a page that has one. Everything the chip omits is one tap
 * away in the sheet behind it.
 *
 * Colour is the one extra channel it spends, and it spends it on the only thing
 * that changes what the number means: whether the strap is still connected. That
 * is the design system's documented live-signal exception — an instrument
 * reading, not decoration — so the dot keeps its tone while the value sets in
 * ink like every other number on the page.
 *
 * The chip is small; its tap target is not. The 48dp box around it is what makes
 * it reachable from a top bar.
 */
@Composable
fun HrBpmChip(display: HrCaptureDisplay, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = HrCaptureCopy.chipDescription(display)
    Box(
        modifier = modifier
            .sizeIn(minWidth = LogbookSpace.touchTarget, minHeight = LogbookSpace.touchTarget)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HrToneDot(tone = display.tone, size = CHIP_DOT_SIZE)
            Text(
                text = display.bpmText,
                style = LogbookTheme.type.data,
                color = LogbookTheme.palette.ink,
            )
        }
    }
}

/**
 * The one tone→colour table.
 *
 * Internal rather than private so the mapping is pinned by a test instead of by
 * a composable only a device can inspect.
 */
internal fun HrTone.colorOn(colors: LiveSignalColors): Color = when (this) {
    HrTone.LIVE -> colors.live
    HrTone.WAITING -> colors.waiting
    HrTone.LOST -> colors.attention
}

@Composable
@ReadOnlyComposable
fun HrTone.toneColor(): Color = colorOn(liveSignalColors())

/**
 * The status dot the chip, the capture sheet and the strap section share.
 *
 * A circle is the shape whatever else the page is doing — it is the one mark in
 * the system whose meaning is its colour, so it stays round rather than taking
 * the paper's 2dp corners.
 */
@Composable
fun HrToneDot(
    tone: HrTone,
    modifier: Modifier = Modifier,
    size: Dp = DOT_SIZE,
) {
    val color = tone.toneColor()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

private val DOT_SIZE = 8.dp

/** Smaller than the sheet's: it sits beside 12.5sp mono rather than beside prose. */
private val CHIP_DOT_SIZE = 6.dp
private val CHIP_GAP = 6.dp
