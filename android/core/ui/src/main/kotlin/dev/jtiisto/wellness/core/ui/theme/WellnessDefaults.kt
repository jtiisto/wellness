package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The component-role table, in one place.
 *
 * Stock Material components take their colours from the M3 scheme, and the M3
 * scheme deliberately carries no module identity — so every control that should
 * wear the module's accent is told to, here, rather than at thirty callsites
 * that would each drift on their own.
 */
object WellnessDefaults {

    // Text fields are not here: [WellnessDenseField] owns that role table
    // entry, and it reads the palette directly rather than through an M3
    // `TextFieldColors` its BasicTextField decoration could not consume.

    // Four factories retired with the journal's Logbook round (2026-08-18):
    // `checkboxColors`, `sliderColors`, `filterChipColors` and
    // `filterChipBorder`. Journal was their only consumer, and Logbook has no
    // Material checkbox, no chip and no accent to tint a slider with — the ink
    // mark, the weekday mark row and explicit ink slider colours replaced them.
    // Trends, Analysis and Tools keep everything below.

    /** The one filled control per screen: the module's own colour, ink on top. */
    @Composable
    fun accentButtonColors(): ButtonColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return ButtonDefaults.buttonColors(
            containerColor = accent.fill,
            contentColor = accent.ink,
            disabledContainerColor = palette.band,
            disabledContentColor = palette.textFaint,
        )
    }

    /** A solid semantic fill — a fired hook, a failed one. */
    @Composable
    fun semanticButtonColors(container: Color): ButtonColors {
        val palette = WellnessTheme.palette
        return ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = palette.onSemanticFill,
            disabledContainerColor = container.copy(alpha = 0.5f),
            disabledContentColor = palette.onSemanticFill.copy(alpha = 0.7f),
        )
    }

    @Composable
    fun accentOutlinedButtonColors(): ButtonColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return ButtonDefaults.outlinedButtonColors(
            contentColor = accent.text,
            disabledContentColor = palette.textFaint,
        )
    }

    @Composable
    fun accentTextButtonColors(): ButtonColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return ButtonDefaults.textButtonColors(
            contentColor = accent.text,
            disabledContentColor = palette.textFaint,
        )
    }

    @Composable
    fun accentTonalIconButtonColors(): IconButtonColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = accent.softFill,
            contentColor = accent.text,
            disabledContainerColor = palette.band,
            disabledContentColor = palette.textFaint,
        )
    }
}
