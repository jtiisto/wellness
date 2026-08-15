package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

    @Composable
    fun checkboxColors(): CheckboxColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return CheckboxDefaults.colors(
            checkedColor = accent.fill,
            uncheckedColor = palette.textFaint,
            checkmarkColor = accent.ink,
            disabledCheckedColor = accent.fill.copy(alpha = 0.38f),
            disabledUncheckedColor = palette.textFaint.copy(alpha = 0.38f),
        )
    }

    @Composable
    fun sliderColors(): SliderColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return SliderDefaults.colors(
            thumbColor = accent.fill,
            activeTrackColor = accent.fill,
            inactiveTrackColor = palette.line,
            disabledThumbColor = palette.textFaint,
            disabledActiveTrackColor = palette.line,
            disabledInactiveTrackColor = palette.line,
        )
    }

    /** Selected chips wear the accent wash; at rest they are the band. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun filterChipColors(): SelectableChipColors {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return FilterChipDefaults.filterChipColors(
            containerColor = palette.band,
            labelColor = palette.textSecondary,
            disabledContainerColor = palette.band,
            disabledLabelColor = palette.textFaint,
            selectedContainerColor = accent.softFill,
            selectedLabelColor = accent.text,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun filterChipBorder(enabled: Boolean, selected: Boolean): BorderStroke? {
        val palette = WellnessTheme.palette
        val accent = WellnessTheme.accent
        return FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = palette.line,
            selectedBorderColor = accent.border,
            disabledBorderColor = palette.line,
            disabledSelectedBorderColor = palette.line,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        )
    }

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
