package dev.jtiisto.wellness.ui

import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The page every tab is drawn on.
 *
 * This was `ShellSystemTest`, and for five rounds it pinned a route→design-system
 * table while Logbook replaced Graphite Signal one destination at a time: journal
 * and coach flipped first, then trends, then analysis and tools together, each
 * flip a deliberate edit in both the destination table and this file's copy of
 * it. Round 4's retirement removed the second system, so the mapping it guarded
 * has one possible answer and the tests that asserted the two systems differ
 * became statements about a thing that no longer exists.
 *
 * What is left is worth keeping for the reason the original was written: the
 * Scaffold's canvas is what a tab switch shows for the frame before the new
 * screen draws, and getting it wrong is a flash — visible on a device, invisible
 * in a diff.
 */
class ShellChromeTest {

    @Test
    @DisplayName("every destination is drawn on Logbook paper with Logbook ink, in both modes")
    fun chromeIsPaperAndInk() {
        assertEquals(LogbookLight.paper, shellChrome(isDark = false).canvas)
        assertEquals(LogbookLight.ink, shellChrome(isDark = false).content)
        assertEquals(LogbookDark.paper, shellChrome(isDark = true).canvas)
        assertEquals(LogbookDark.ink, shellChrome(isDark = true).content)
    }

    @Test
    @DisplayName("the five tabs, in their order")
    fun theRouteSet() {
        // Pinned rather than derived: the nav graph, the bottom bar and the
        // launch window all read this list, and a route renamed in one place
        // only is a tab that navigates nowhere.
        assertEquals(
            listOf("journal", "coach", "trends", "analysis", "tools"),
            topLevelDestinations.map { it.route },
        )
    }

    @Test
    @DisplayName("the shell opens on Journal, and the nav graph starts there too")
    fun startTabIsJournal() {
        // Also what `values/colors.xml` has to agree with: the launch window is
        // painted before Compose has a frame, and it paints the start
        // destination's page. Every destination being paper now makes that
        // agreement automatic — the window is Logbook `paper` whichever tab
        // opens first — but the start tab is still the thing it tracks.
        assertEquals("journal", startTab.route)
        assertEquals(topLevelDestinations.first(), startTab)
    }
}
