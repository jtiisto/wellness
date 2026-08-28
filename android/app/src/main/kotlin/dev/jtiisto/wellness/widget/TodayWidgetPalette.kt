package dev.jtiisto.wellness.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

/**
 * The Logbook's five inks, spelled out for a surface that cannot read the theme.
 *
 * These are **duplicated from `LogbookPalette` deliberately, not carelessly**:
 * Glance renders into RemoteViews in the launcher's process, where there is no
 * composition and therefore no `LocalLogbookPalette` to read — a widget must
 * ship its colours as literals or ship no colours at all. Light and dark are
 * both given here because the day/night pair swaps on the host without a
 * recomposition, which is the only dark-mode mechanism this surface has.
 *
 * The duplication is made safe by `TodayWidgetPaletteTest`, which asserts every
 * value against the field it was copied from, so a re-cut ink in `LogbookLight`
 * or `LogbookDark` fails the build here rather than leaving a home screen
 * quietly a shade behind the app.
 *
 * Five tokens is the whole list. No plate, no accent, nothing chromatic: on this
 * surface as in the app, judgment and completion are carried by shape and fill.
 */

// ---- The raw pairs: the pin targets ----------------------------------------

/** The page. The only surface on the widget — nothing nests on top of it. */
internal val WIDGET_PAPER_DAY = Color(0xFFFBFAF7)
internal val WIDGET_PAPER_NIGHT = Color(0xFF141517)

/** Headline, debt line, the bang, and every mark in a judged state. */
internal val WIDGET_INK_DAY = Color(0xFF17191B)
internal val WIDGET_INK_NIGHT = Color(0xFFEDECE7)

/** Eyebrows, the `h:mm` unit, strain, freshness, the cached badge. */
internal val WIDGET_INK_SOFT_DAY = Color(0xFF6A6D73)
internal val WIDGET_INK_SOFT_NIGHT = Color(0xFF7D8288)

/** Not-yet dots, the pending glyph and its `-:--`. Recedes on purpose. */
internal val WIDGET_INK_FAINT_DAY = Color(0xFFA6A9AD)
internal val WIDGET_INK_FAINT_NIGHT = Color(0xFF55585D)

/** The 1dp hairline between the tally and the sleep block. */
internal val WIDGET_RULE_DAY = Color(0xFFE7E5DE)
internal val WIDGET_RULE_NIGHT = Color(0xFF2A2C2F)

// ---- What the content draws with -------------------------------------------

internal val WIDGET_PAPER = ColorProvider(day = WIDGET_PAPER_DAY, night = WIDGET_PAPER_NIGHT)
internal val WIDGET_INK = ColorProvider(day = WIDGET_INK_DAY, night = WIDGET_INK_NIGHT)
internal val WIDGET_INK_SOFT =
    ColorProvider(day = WIDGET_INK_SOFT_DAY, night = WIDGET_INK_SOFT_NIGHT)
internal val WIDGET_INK_FAINT =
    ColorProvider(day = WIDGET_INK_FAINT_DAY, night = WIDGET_INK_FAINT_NIGHT)
internal val WIDGET_RULE = ColorProvider(day = WIDGET_RULE_DAY, night = WIDGET_RULE_NIGHT)
