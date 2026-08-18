package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ui.R

/**
 * Barlow Condensed SemiBold — the only display face.
 *
 * One static cut, because the system only ever sets it at 600 and in caps:
 * workout titles and section labels. It is never used for prose.
 */
private val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)

/**
 * Inter, the prose face — five instances cut from two variable files.
 *
 * Both files carry `wght` (100–900) and `opsz` (14–32). Each entry below is a
 * `wght` instance: Compose derives the variation setting from the entry's
 * [FontWeight], so these are five real weights rather than five references to
 * one default cut. `opsz` is deliberately left alone at its default of 14 —
 * Inter's "Text" optical size, drawn for exactly the 12.5–14.5sp range the
 * Inter roles live in.
 */
private val Inter = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_italic_variable, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.inter_italic_variable, FontWeight.Medium, FontStyle.Italic),
)

/**
 * IBM Plex Mono — every numeral in the app.
 *
 * Tabular by construction, so no `tnum` feature setting is needed anywhere;
 * the sans faces deliberately do *not* get one, since prose set in tabular
 * figures reads as a spreadsheet.
 */
private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * The Logbook ramp: eight roles across three faces.
 *
 * The division is the design's third principle — **numbers are mono, words are
 * sans, headings are condensed caps** — so the face a string is set in already
 * says what kind of thing it is. Picking a role by size instead of by kind is
 * how that breaks.
 *
 * Two roles carry a documented second form rather than a ninth entry:
 * [body] italic is the marginalia and empty-state voice (program → user), and
 * [meta] at [FontWeight.Medium] is the *value* half of a meta line, its labels
 * staying at 400. Both are `copy()` at the callsite.
 *
 * Uppercasing is not expressible in a `TextStyle` — the caps roles ([display],
 * [section], [eyebrow], [tableHeader]) are set from already-uppercased strings.
 */
@Immutable
data class LogbookTypography(
    /** Workout titles. Caps. */
    val display: TextStyle,
    /** Section labels, over a 1.5dp ink underline. Caps. */
    val section: TextStyle,
    /** Exercise names. */
    val name: TextStyle,
    /** Prose and user notes; italic for marginalia and empty states. */
    val body: TextStyle,
    /** Set-table cells, schemes. */
    val data: TextStyle,
    /** Meta lines: labels at 400, values at 500. */
    val meta: TextStyle,
    /** Eyebrows, legend, note labels, hints. Caps. */
    val eyebrow: TextStyle,
    /** Set-table column heads. Caps. */
    val tableHeader: TextStyle,
)

val LogbookType = LogbookTypography(
    display = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        // Leading equal to the size: condensed caps have no descenders to
        // clear, and the extra air would break the header block apart.
        lineHeight = 34.sp,
    ),
    section = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.02.sp, // +6%
    ),
    name = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
    ),
    body = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    data = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
    ),
    meta = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
    ),
    eyebrow = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.36.sp, // +13%
    ),
    tableHeader = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.2.sp, // +12%
    ),
)

/**
 * Flat by intent: 2dp is the whole curve budget.
 *
 * Paper does not have rounded corners, and a drop shadow inside the screen
 * would contradict the one-surface rule — so nothing here softens further and
 * nothing anywhere sets an elevation.
 */
object LogbookShapes {
    /** Tally marks, set-completion marks, the calendar popup, the snackbar. */
    val soft = RoundedCornerShape(2.dp)

    /** Rules, brackets, fields, table cells — structure is square. */
    val square = RoundedCornerShape(0.dp)
}

/**
 * The spacing scale.
 *
 * [row] and [group] are half-steps of the [grid] rather than multiples: the
 * log's density is set by the row rhythm, and 12dp crowds it while 16dp loses it.
 */
object LogbookSpace {
    /** The base unit. Every spacing that is not [row] or [group] is a multiple. */
    val grid = 4.dp

    /** Vertical padding inside an exercise or set-table row. */
    val row = 14.dp

    /** Air around a group boundary — the space that does the grouping. */
    val group = 18.dp

    /** Every `rule` and `ruleStrong` line in the system. */
    val hairline = 1.dp

    /** The ink line under a section label — the one stroke heavier than a hairline. */
    val sectionUnderline = 1.5.dp

    /** Android law beats paper flatness: interactive targets never go under this. */
    val touchTarget = 48.dp
}

/**
 * The M3 slots, filled from the ramp so stock components inherit the faces.
 *
 * The mono dialect is deliberately absent: M3 spends its `label` and `body`
 * slots on ordinary prose, and installing [LogbookTypography.data] or
 * [LogbookTypography.eyebrow] there would set every stock caption in tracked
 * mono caps. Mono is reached for explicitly, at the callsites that mean
 * numbers — the same reasoning that keeps Graphite's `micro` out of `labelSmall`.
 *
 * The four intermediate sizes M3 wants and the ramp does not name are derived
 * by `copy()` rather than invented as new roles.
 */
internal val LogbookM3Typography = Typography(
    displayLarge = LogbookType.display,
    displayMedium = LogbookType.display,
    displaySmall = LogbookType.display,
    headlineLarge = LogbookType.display.copy(fontSize = 26.sp, lineHeight = 28.sp),
    headlineMedium = LogbookType.display.copy(fontSize = 22.sp, lineHeight = 24.sp),
    headlineSmall = LogbookType.section.copy(fontSize = 20.sp, lineHeight = 23.sp),
    titleLarge = LogbookType.section.copy(fontSize = 19.sp, lineHeight = 22.sp),
    titleMedium = LogbookType.section,
    titleSmall = LogbookType.section.copy(fontSize = 15.sp, lineHeight = 18.sp),
    bodyLarge = LogbookType.body.copy(fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = LogbookType.body,
    bodySmall = LogbookType.body.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = LogbookType.name,
    labelMedium = LogbookType.name.copy(fontSize = 13.sp, lineHeight = 17.sp),
    labelSmall = LogbookType.name.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
)

/** Every M3 shape slot lands on the same 2dp — there is no larger radius to grade to. */
internal val LogbookM3Shapes = Shapes(
    extraSmall = LogbookShapes.soft,
    small = LogbookShapes.soft,
    medium = LogbookShapes.soft,
    large = LogbookShapes.soft,
    extraLarge = LogbookShapes.soft,
)

val LocalLogbookTypography = staticCompositionLocalOf { LogbookType }
