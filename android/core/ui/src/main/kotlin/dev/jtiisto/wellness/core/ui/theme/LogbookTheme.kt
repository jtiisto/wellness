package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The Logbook theme.
 *
 * Four things are provided, and each one is a design rule made mechanical:
 *
 * - [LocalLogbookPalette] and [LocalLogbookTypography], which every bespoke
 *   Logbook callsite reads directly;
 * - a fully populated M3 [ColorScheme], so a stock component nobody restyled
 *   still lands on paper with legible ink instead of baseline Material purple;
 * - the M3 `Typography` and `Shapes` adapters, so the same component inherits
 *   the faces and the 2dp ceiling;
 * - [LocalIndication], replaced with a quiet ink press so nothing in a Logbook
 *   subtree can spread a Material ripple across the paper.
 *
 * Applied once, at the shell's root. It was per-destination for the length of
 * the migration, while Graphite Signal still rendered some tabs; with that
 * system retired there is one theme and one page.
 */
@Composable
fun LogbookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) LogbookDark else LogbookLight
    val colorScheme = remember(palette) { palette.toColorScheme() }
    val indication = remember(palette) { InkPressIndication(palette.ink) }
    CompositionLocalProvider(
        LocalLogbookPalette provides palette,
        LocalLogbookTypography provides LogbookType,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LogbookM3Typography,
            shapes = LogbookM3Shapes,
        ) {
            // Provided INSIDE MaterialTheme's content: M3 re-provides
            // LocalIndication with its own ripple, so an outer provider is
            // silently shadowed and every press ripples anyway.
            CompositionLocalProvider(LocalIndication provides indication, content = content)
        }
    }
}

/**
 * Token access from a composable: `LogbookTheme.palette.paper`.
 *
 * Mirrors `MaterialTheme`'s object-beside-function shape, so a file that reads
 * both reads them alike.
 */
object LogbookTheme {
    val palette: LogbookPalette
        @Composable @ReadOnlyComposable get() = LocalLogbookPalette.current

    val type: LogbookTypography
        @Composable @ReadOnlyComposable get() = LocalLogbookTypography.current
}

/**
 * The M3 adapter.
 *
 * Every slot is filled — an unset one falls back to baseline Material purple,
 * which would surface as a violet cursor or a lilac ripple the first time an
 * un-restyled component rendered. Three mappings carry the design's rules and
 * are the ones to read before changing anything here:
 *
 * - **The surface ladder is flat.** `surface`, `background`, every
 *   `surfaceContainer*`, `surfaceBright` and `surfaceDim` are all [paper].
 *   Logbook has one surface, so M3's elevation ladder has nothing to grade
 *   through; `surfaceTint` is cleared so an elevated `Surface` keeps paper
 *   exactly. Grouping is drawn with rules, never with a lighter box.
 * - **There is no error colour.** Logbook carries no semantic palette — a
 *   failed hook is an ink glyph and a `FAILED` mono label. The `error` slot
 *   therefore holds [ink], so a stock component reaching for it lands on the
 *   page's own ink rather than importing a red the system does not own.
 * - **Containers degrade to [rule], not to paper.** Logbook draws no tonal
 *   fills, but a stock container mapped to paper would vanish outright, which
 *   is worse than being quiet. `rule` is the faintest tint that still reads as
 *   a block and keeps ink on it at 12:1 or better.
 */
private fun LogbookPalette.toColorScheme(): ColorScheme =
    (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        // Ink-filled controls with paper text — the hook-button language.
        primary = ink,
        onPrimary = paper,
        primaryContainer = rule,
        onPrimaryContainer = ink,
        inversePrimary = if (isDark) LogbookLight.ink else LogbookDark.ink,
        secondary = inkSoft,
        onSecondary = paper,
        secondaryContainer = rule,
        onSecondaryContainer = ink,
        tertiary = inkSoft,
        onTertiary = paper,
        tertiaryContainer = rule,
        onTertiaryContainer = ink,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = rule,
        onSurfaceVariant = inkSoft,
        surfaceTint = Color.Transparent,
        // The snackbar is the one inversion in the system: an ink slab in the
        // light theme, a paper one in the dark, taken from the opposite palette
        // so it is the real other side rather than an approximation of it.
        inverseSurface = if (isDark) LogbookLight.paper else LogbookDark.paper,
        inverseOnSurface = if (isDark) LogbookLight.ink else LogbookDark.ink,
        error = ink,
        onError = paper,
        errorContainer = rule,
        onErrorContainer = ink,
        outline = ruleStrong,
        outlineVariant = rule,
        scrim = Color.Black,
        surfaceBright = paper,
        surfaceDim = paper,
        surfaceContainer = paper,
        surfaceContainerHigh = paper,
        surfaceContainerHighest = paper,
        surfaceContainerLow = paper,
        surfaceContainerLowest = paper,
        // M3's "fixed" roles hold one value across both themes. In a paper
        // system that means one paper: the dark one, since a fixed slot that
        // ignores the theme should at least be internally consistent. Left at
        // their defaults they would be the baseline purple, one expressive
        // component away from appearing.
        primaryFixed = LogbookDark.rule,
        primaryFixedDim = LogbookDark.ruleStrong,
        onPrimaryFixed = LogbookDark.ink,
        onPrimaryFixedVariant = LogbookDark.inkSoft,
        secondaryFixed = LogbookDark.rule,
        secondaryFixedDim = LogbookDark.ruleStrong,
        onSecondaryFixed = LogbookDark.ink,
        onSecondaryFixedVariant = LogbookDark.inkSoft,
        tertiaryFixed = LogbookDark.rule,
        tertiaryFixedDim = LogbookDark.ruleStrong,
        onTertiaryFixed = LogbookDark.ink,
        onTertiaryFixedVariant = LogbookDark.inkSoft,
    )

/**
 * The press feedback: a flat ink wash for exactly as long as the finger is down.
 *
 * M3's ripple is an expanding bounded animation with its own colour and timing
 * — the loudest thing that would happen on a page whose whole premise is that
 * nothing moves. This draws [ink] at [PRESS_ALPHA] over the pressed content and
 * removes it on release. No animation, no radius, no state layer for hover or
 * focus: those arrive with a keyboard or a mouse, and the shape they should
 * take on paper is a question this round does not need to answer.
 */
@Immutable
private class InkPressIndication(private val ink: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        InkPressNode(interactionSource, ink)

    override fun equals(other: Any?): Boolean = other is InkPressIndication && other.ink == ink

    override fun hashCode(): Int = ink.hashCode()
}

private class InkPressNode(
    private val interactionSource: InteractionSource,
    private val ink: Color,
) : Modifier.Node(), DrawModifierNode {

    private var isPressed = false

    override fun onAttach() {
        coroutineScope.launch {
            // Counted rather than latched: a second pointer going down and up
            // during one press must not clear the wash while the first is held.
            var presses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release -> presses--
                    is PressInteraction.Cancel -> presses--
                    else -> Unit
                }
                val pressed = presses > 0
                if (pressed != isPressed) {
                    isPressed = pressed
                    invalidateDraw()
                }
            }
        }
    }

    override fun onDetach() {
        // The collector dies with the node's scope, so a node detached
        // mid-press would reattach with a fresh count of zero while still
        // drawing the wash. Clear it here; reattaching starts clean.
        isPressed = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (isPressed) {
            drawRect(color = ink.copy(alpha = PRESS_ALPHA), size = size)
        }
    }
}

/** Enough to register as a press on paper, not enough to read as a fill. */
private const val PRESS_ALPHA = 0.10f
