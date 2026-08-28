package dev.jtiisto.wellness.widget

import androidx.glance.color.ColorProvider
import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The drift pin under a deliberate duplication.
 *
 * Glance renders in the launcher's process with no composition behind it, so
 * `LocalLogbookPalette` is unreachable and the widget's colours have to be
 * literals. Literals copied from a palette are the classic way two surfaces of
 * one app end up a shade apart — nobody notices, because nobody sees them side
 * by side. So the copy is made *checkable*: re-cut an ink in `LogbookLight` or
 * `LogbookDark` and this file fails the build until the widget follows.
 *
 * Ten assertions for ten values, spelled out rather than looped, in the style
 * `ShellChromeTest` pins the shell's canvas: what is being guarded is a copy,
 * and a loop over a table would only guard the table against itself.
 */
class TodayWidgetPaletteTest {

    @Test
    @DisplayName("the widget's paper is the Logbook's paper, in both modes")
    fun paper() {
        assertEquals(LogbookLight.paper, WIDGET_PAPER_DAY)
        assertEquals(LogbookDark.paper, WIDGET_PAPER_NIGHT)
    }

    @Test
    @DisplayName("ink, the headline and every judged mark")
    fun ink() {
        assertEquals(LogbookLight.ink, WIDGET_INK_DAY)
        assertEquals(LogbookDark.ink, WIDGET_INK_NIGHT)
    }

    @Test
    @DisplayName("inkSoft, which dark has its own cut of")
    fun inkSoft() {
        // Dark is not light's value reused: #6A6D73 misses the text floor on
        // dark paper, which is why there are two constants here and not one.
        assertEquals(LogbookLight.inkSoft, WIDGET_INK_SOFT_DAY)
        assertEquals(LogbookDark.inkSoft, WIDGET_INK_SOFT_NIGHT)
    }

    @Test
    @DisplayName("inkFaint, the receding tier the pending glyph is drawn in")
    fun inkFaint() {
        assertEquals(LogbookLight.inkFaint, WIDGET_INK_FAINT_DAY)
        assertEquals(LogbookDark.inkFaint, WIDGET_INK_FAINT_NIGHT)
    }

    @Test
    @DisplayName("rule, the one hairline on the page")
    fun rule() {
        // `rule`, never `ruleStrong`: the widget's separator divides two blocks
        // of one page, which is exactly what the app spends the lighter one on.
        assertEquals(LogbookLight.rule, WIDGET_RULE_DAY)
        assertEquals(LogbookDark.rule, WIDGET_RULE_NIGHT)
    }

    @Test
    @DisplayName("each provider carries its own pair, day first")
    fun providersCarryTheirPairs() {
        // Five near-identical constructions is where a day/night swap or a
        // borrowed neighbour would hide, and neither shows up in the values
        // above — this is the line that would catch it.
        assertEquals(ColorProvider(day = WIDGET_PAPER_DAY, night = WIDGET_PAPER_NIGHT), WIDGET_PAPER)
        assertEquals(ColorProvider(day = WIDGET_INK_DAY, night = WIDGET_INK_NIGHT), WIDGET_INK)
        assertEquals(
            ColorProvider(day = WIDGET_INK_SOFT_DAY, night = WIDGET_INK_SOFT_NIGHT),
            WIDGET_INK_SOFT,
        )
        assertEquals(
            ColorProvider(day = WIDGET_INK_FAINT_DAY, night = WIDGET_INK_FAINT_NIGHT),
            WIDGET_INK_FAINT,
        )
        assertEquals(ColorProvider(day = WIDGET_RULE_DAY, night = WIDGET_RULE_NIGHT), WIDGET_RULE)
    }
}
