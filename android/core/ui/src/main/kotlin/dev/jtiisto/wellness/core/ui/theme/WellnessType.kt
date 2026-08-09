package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Roboto Flex, the system variable font.
 *
 * Zero APK cost and indistinguishable from Inter at UI sizes, which is what the
 * ramp was drawn against. The weights below are variable-axis values, not the
 * nine standard steps: on a device without the variable family Compose falls
 * back to the platform sans-serif and each weight snaps to its nearest
 * available cut, which degrades the texture but never the layout.
 */
private val RobotoFlex = FontFamily(
    listOf(400, 450, 500, 650, 700, 750).map { weight ->
        Font(familyName = DeviceFontFamilyName("Roboto Flex"), weight = FontWeight(weight))
    },
)

/**
 * The Graphite Signal ramp.
 *
 * Eight styles, each with a job. [micro] is the taxonomy dialect — uppercase and
 * widely tracked — and is spent only on labels that name a *kind* of thing
 * (exposure, superset, rx). It is never decoration.
 */
@Immutable
data class WellnessTypography(
    /** Hero numerals. */
    val display: TextStyle,
    /** Tile values. */
    val stat: TextStyle,
    /** Day names, module titles. */
    val headline: TextStyle,
    /** Card and section titles. */
    val title: TextStyle,
    val body: TextStyle,
    val secondary: TextStyle,
    /** Buttons, chips. */
    val label: TextStyle,
    /** UPPERCASE taxonomy labels only. */
    val micro: TextStyle,
)

/**
 * Tabular figures.
 *
 * The default for every numeric slot — set grids, targets, progress pills,
 * tiles, captions — because a value that changes while you watch it must not
 * shuffle the glyphs around it.
 */
private const val TABULAR = "tnum"

val WellnessType = WellnessTypography(
    display = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(750),
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.32).sp,
        fontFeatureSettings = TABULAR,
    ),
    stat = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(750),
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = TABULAR,
    ),
    headline = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(700),
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    title = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(650),
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(450),
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    secondary = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(450),
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    label = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(500),
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = TABULAR,
    ),
    micro = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = FontWeight(650),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.66.sp,
        fontFeatureSettings = TABULAR,
    ),
)

/** Italic at 55% — the "not yet yours" convention for ghosts and uncommitted values. */
const val GHOST_ALPHA = 0.55f

/**
 * The M3 slots, filled from the ramp so stock components inherit the face.
 *
 * `labelSmall` deliberately does *not* get [WellnessTypography.micro]: M3 spends
 * that slot on ordinary small captions, and uppercasing them would put the
 * taxonomy dialect everywhere.
 */
internal val WellnessM3Typography = Typography(
    displayLarge = WellnessType.display,
    displayMedium = WellnessType.display,
    displaySmall = WellnessType.display,
    headlineLarge = WellnessType.headline,
    headlineMedium = WellnessType.headline,
    headlineSmall = WellnessType.headline,
    titleLarge = WellnessType.headline,
    titleMedium = WellnessType.title,
    titleSmall = WellnessType.title.copy(fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = WellnessType.body,
    bodyMedium = WellnessType.secondary,
    bodySmall = WellnessType.secondary.copy(fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = WellnessType.label,
    labelMedium = WellnessType.label.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = WellnessType.label.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight(500)),
)

/**
 * Crisp corners. Nothing rounds past 12dp — a softer card would read as a
 * different product.
 */
object WellnessShape {
    /** Cards, buttons, rows, tiles. */
    val card = RoundedCornerShape(8.dp)
    val input = RoundedCornerShape(6.dp)
    /** Sheets, popovers — anything that floats. */
    val floating = RoundedCornerShape(12.dp)
    val pill = CircleShape

    /** The welded card's two ends. */
    val weldedTop = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    val weldedBottom = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
}

internal val WellnessM3Shapes = Shapes(
    extraSmall = WellnessShape.input,
    small = WellnessShape.card,
    medium = WellnessShape.card,
    large = WellnessShape.floating,
    extraLarge = WellnessShape.floating,
)

/** The PWA's spacing scale, kept. */
object WellnessSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** Card padding, screen padding. */
    val card = 16.dp
    /** Minimum comfortable tap size. */
    val touchTarget = 48.dp
    /** Every hairline in the system. */
    val hairline = 1.dp
}

val LocalWellnessTypography = staticCompositionLocalOf { WellnessType }
