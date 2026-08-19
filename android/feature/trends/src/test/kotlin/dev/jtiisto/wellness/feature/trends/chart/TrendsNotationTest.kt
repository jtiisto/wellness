package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.WeekMark
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chrome's derivations: captions, focus marks, streaks, the flag.
 *
 * The series vocabulary — what each tone is drawn in, and the swatch that keys
 * it — is [ChartInk]'s, and is pinned by `ChartInkTest`.
 */
class TrendsNotationTest {

    // ---- header ------------------------------------------------------------

    @Test
    @DisplayName("the eyebrow says the window in words")
    fun eyebrowNamesTheWindow() {
        assertEquals("Trends · Last 4 weeks", trendsEyebrow("4w"))
        assertEquals("Trends · Last 12 weeks", trendsEyebrow("12w"))
        assertEquals("Trends · Last 6 months", trendsEyebrow("6m"))
        assertEquals("Trends · All time", trendsEyebrow("all"))
    }

    @Test
    @DisplayName("an id this build does not know reads as itself, never as a wrong window")
    fun unknownRangeSaysItself() {
        // `rangeStart` treats an unknown id as All; naming it "All time" here
        // would make the eyebrow the one place the page told a different story
        // from the query it actually sent.
        assertEquals("2y", rangeCaption("2y"))
    }

    @Test
    @DisplayName("the display title is the sub-screen, and an unknown one falls back to the tab")
    fun titleIsTheSubScreen() {
        assertEquals("Health", screenTitle("health"))
        assertEquals("Overview", screenTitle("overview"))
        assertEquals("Trends", screenTitle("not-a-screen"))
    }

    // ---- streaks and badges ------------------------------------------------

    @Test
    @DisplayName("streaks read as mono, with no flame")
    fun streaksAreMono() {
        assertEquals("run 3 · best 14", streakLine(3, 14))
        assertFalse(streakLine(0, 0).contains("🔥"))
    }

    @Test
    @DisplayName("the PR badge loses its trophy at the render site, keeping the builder pinned")
    fun prBadgeDropsTheEmoji() {
        assertEquals("3 PRs in 30d", prBadgeText(prTileModel(prSummary(count = 3))!!.badge))
        // Idempotent, so a builder that stops emitting the glyph needs no change
        // here.
        assertEquals("3 PRs in 30d", prBadgeText("3 PRs in 30d"))
    }

    // ---- focus ribbons -----------------------------------------------------

    @Test
    @DisplayName("a focus day draws the journal's own mark")
    fun focusMarksAreTheJournalGrammar() {
        assertEquals(WeekMark.FILLED_DOT, focusMark("met"))
        assertEquals(WeekMark.HALF_DOT, focusMark("partial"))
        assertEquals(WeekMark.SLASHED_DOT, focusMark("missed"))
    }

    @Test
    @DisplayName("off is a dash, not a miss — nothing was asked that day")
    fun offScheduleIsADash() {
        assertEquals(WeekMark.DASH, focusMark("off"))
    }

    @Test
    @DisplayName("a status this build cannot read leaves the day open rather than judged")
    fun unknownStatusIsOpen() {
        assertEquals(WeekMark.OPEN_DOT, focusMark("paused"))
        assertEquals(WeekMark.OPEN_DOT, focusMark(""))
    }

    @Test
    @DisplayName("the ribbon is tallied aloud in the marks' own words")
    fun ribbonIsSpokenAsATally() {
        val spoken = describeFocusRibbon(listOf("met", "met", "missed", "off", "partial"))
        assertEquals("Last 5 days: 2 done, 1 partial, 1 missed, 1 off schedule", spoken)
    }

    @Test
    @DisplayName("a tally names only what is there, and an empty ribbon says nothing")
    fun tallyOmitsZeros() {
        assertEquals("Last 2 days: 2 done", describeFocusRibbon(listOf("met", "met")))
        assertNull(describeFocusRibbon(emptyList()))
    }

    // ---- labs --------------------------------------------------------------

    @Test
    @DisplayName("the strip's flag is read off its own last dot")
    fun flagFollowsTheLastDot() {
        assertTrue(latestIsFlagged(plotEndingIn(PlotTone.WARN)))
        assertFalse(latestIsFlagged(plotEndingIn(PlotTone.VALUE)))
        assertFalse(latestIsFlagged(PlotModel(height = 10.0)))
    }

    private fun plotEndingIn(tone: PlotTone) = PlotModel(
        height = 10.0,
        dots = listOf(
            PlotDot(0.0, 0.0, 2.dp, PlotTone.VALUE),
            PlotDot(1.0, 1.0, 2.dp, tone),
        ),
    )
}
