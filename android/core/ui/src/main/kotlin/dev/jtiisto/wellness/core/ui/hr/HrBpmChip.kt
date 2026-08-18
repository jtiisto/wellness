package dev.jtiisto.wellness.core.ui.hr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.DarkPalette
import dev.jtiisto.wellness.core.ui.theme.LightPalette
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WellnessPalette
import dev.jtiisto.wellness.core.ui.theme.WellnessShape

/**
 * The live heart rate, as small as it can be and still be read at arm's length.
 *
 * A number and a tone dot, and nothing else — no icon, no unit, no device name,
 * and in Logbook not even a container: the top bar is paper, and a pill on it
 * would be a second surface on a page that has one. Everything the chip omits is
 * one tap away in the sheet behind it.
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
            HrToneDot(tone = display.tone, color = display.tone.logbookColor(), size = CHIP_DOT_SIZE)
            Text(
                text = display.bpmText,
                style = LogbookTheme.type.data,
                color = LogbookTheme.palette.ink,
            )
        }
    }
}

/**
 * The tone as a colour on a Graphite surface.
 *
 * Semantic tokens rather than the module accent: this says whether the *data* is
 * arriving, which is the same question on any tab, and the accent is spent on
 * identity and primary actions elsewhere.
 *
 * The pair is picked from the system's dark-mode setting rather than from
 * `LocalWellnessPalette`, because this function is now read from both design
 * systems and a Logbook subtree provides no Graphite palette at all — the local
 * would answer with its `DarkPalette` default and put a dark-theme green on
 * light paper. `WellnessTheme` derives its own palette exactly this way, so
 * under Graphite the answer is unchanged.
 */
@Composable
@ReadOnlyComposable
fun HrTone.color(): Color = colorOn(semanticPalette())

/**
 * The tone as a colour on Logbook paper.
 *
 * Logbook owns no semantic tokens — a failed hook is an ink glyph, not a red one
 * — so the tone borrows Graphite's, resolved against the *Logbook* palette's own
 * mode. That is the documented exception rather than a leak: a heart rate is an
 * instrument reading, and the one thing worth colouring on the page is whether
 * the instrument is still reading.
 */
@Composable
@ReadOnlyComposable
fun HrTone.logbookColor(): Color =
    colorOn(if (LogbookTheme.palette.isDark) DarkPalette else LightPalette)

/**
 * The one tone→semantic table, read by both systems.
 *
 * Internal rather than private so the mapping is pinned by a test instead of by
 * two composables that only a device can compare.
 */
internal fun HrTone.colorOn(palette: WellnessPalette): Color = when (this) {
    HrTone.LIVE -> palette.success
    HrTone.WAITING -> palette.warning
    HrTone.LOST -> palette.error
}

@Composable
@ReadOnlyComposable
private fun semanticPalette(): WellnessPalette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

/**
 * The status dot the chip, the capture sheet and the strap section share.
 *
 * [color] is a parameter because the two design systems resolve the same tone
 * differently — see [color] and [logbookColor] — and the dot itself is the same
 * circle either way.
 */
@Composable
fun HrToneDot(
    tone: HrTone,
    modifier: Modifier = Modifier,
    color: Color = tone.color(),
    size: Dp = DOT_SIZE,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(WellnessShape.pill)
            .background(color),
    )
}

private val DOT_SIZE = 8.dp

/** Smaller than the sheet's: it sits beside 12.5sp mono rather than beside prose. */
private val CHIP_DOT_SIZE = 6.dp
private val CHIP_GAP = 6.dp
