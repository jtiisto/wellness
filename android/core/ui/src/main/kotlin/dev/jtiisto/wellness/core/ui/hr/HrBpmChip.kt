package dev.jtiisto.wellness.core.ui.hr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme

/**
 * The live heart rate, as small as it can be and still be read at arm's length.
 *
 * A number on a band pill and nothing else — no icon, no unit, no device name.
 * The spec is explicit that this is a *chip*, and everything the user might also
 * want to know is one tap away in the sheet behind it. Colour is the only extra
 * channel it spends, and it spends it on the one thing that changes what the
 * number means: whether the strap is still connected.
 *
 * The pill is small; its tap target is not. The 48dp box around it is what makes
 * a 25dp chip in a top bar reachable, and the pill stays visually compact inside
 * it.
 */
@Composable
fun HrBpmChip(display: HrCaptureDisplay, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val description = HrCaptureCopy.chipDescription(display)
    Box(
        modifier = modifier
            .sizeIn(minWidth = WellnessSpace.touchTarget, minHeight = WellnessSpace.touchTarget)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display.bpmText,
            style = WellnessTheme.type.label,
            color = display.tone.color(),
            modifier = Modifier
                .clip(WellnessShape.pill)
                .background(palette.band)
                .padding(horizontal = WellnessSpace.sm, vertical = WellnessSpace.xs),
        )
    }
}

/**
 * The tone as a colour, resolved against the current palette.
 *
 * Semantic tokens rather than the module accent: this says whether the *data* is
 * arriving, which is the same question on any tab, and the accent is spent on
 * identity and primary actions elsewhere.
 */
@Composable
@ReadOnlyComposable
fun HrTone.color(): Color {
    val palette = WellnessTheme.palette
    return when (this) {
        HrTone.LIVE -> palette.success
        HrTone.WAITING -> palette.warning
        HrTone.LOST -> palette.error
    }
}

/** The 8dp status dot the capture sheet and the strap section share. */
@Composable
fun HrToneDot(tone: HrTone, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(DOT_SIZE)
            .clip(WellnessShape.pill)
            .background(tone.color()),
    )
}

private val DOT_SIZE = 8.dp
