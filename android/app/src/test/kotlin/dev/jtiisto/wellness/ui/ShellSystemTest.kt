package dev.jtiisto.wellness.ui

import dev.jtiisto.wellness.core.ui.theme.DarkPalette
import dev.jtiisto.wellness.core.ui.theme.LightPalette
import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which page each tab is drawn on while the two design systems coexist.
 *
 * The Scaffold paints one canvas for whichever destination is active, so this
 * mapping is the thing standing between a tab switch and a frame of the other
 * system's paper. It is asserted rather than eyeballed because the failure is
 * a flash — visible on a device, invisible in a diff.
 */
class ShellSystemTest {

    @Test
    @DisplayName("the route→system table matches this independently pinned copy")
    fun everyRouteResolves() {
        // Pinned here rather than read back from the destination table: a
        // comparison against the implementation's own field would stay green
        // for any wrong assignment. Migrating a tab to Logbook means editing
        // BOTH the destination table and this map, deliberately.
        val expected = mapOf(
            "journal" to ShellSystem.GRAPHITE,
            "coach" to ShellSystem.GRAPHITE, // flips in the coach rendering phase
            "trends" to ShellSystem.GRAPHITE,
            "analysis" to ShellSystem.GRAPHITE,
            "tools" to ShellSystem.GRAPHITE,
        )
        assertEquals(expected.keys, topLevelDestinations.map { it.route }.toSet(), "route set")
        expected.forEach { (route, system) ->
            assertEquals(system, shellSystemFor(route), route)
        }
    }

    @Test
    @DisplayName("the shell opens on Journal, and the nav graph starts there too")
    fun startTabIsJournal() {
        assertEquals("journal", startTab.route)
        assertEquals(topLevelDestinations.first(), startTab)
    }

    @Test
    @DisplayName("an unsettled route answers with the start tab, not with the shell's own system")
    fun unknownRouteFallsBackToStartTab() {
        // The first frame composes before the nav host commits a back stack
        // entry: answering Logbook here would show one frame of paper behind
        // Journal, which is exactly the flash the per-destination canvas exists
        // to prevent.
        assertEquals(startTab.system, shellSystemFor(null))
        assertEquals(startTab.system, shellSystemFor("not-a-tab"))
        assertEquals(startTab.system, shellSystemFor(""))
    }

    @Test
    @DisplayName("Coach is still Graphite: its composables move to Logbook in their own round")
    fun coachStaysGraphiteUntilItsRound() {
        // Pinned so the flip is a deliberate edit here rather than a side
        // effect: Logbook locals under Graphite-styled composables render
        // tokens that mean something else, not a degraded version of them.
        assertEquals(ShellSystem.GRAPHITE, shellSystemFor("coach"))
    }

    @Test
    @DisplayName("Logbook destinations get paper and ink, in both modes")
    fun logbookChrome() {
        assertEquals(LogbookLight.paper, ShellSystem.LOGBOOK.chrome(isDark = false).canvas)
        assertEquals(LogbookLight.ink, ShellSystem.LOGBOOK.chrome(isDark = false).content)
        assertEquals(LogbookDark.paper, ShellSystem.LOGBOOK.chrome(isDark = true).canvas)
        assertEquals(LogbookDark.ink, ShellSystem.LOGBOOK.chrome(isDark = true).content)
    }

    @Test
    @DisplayName("Graphite destinations keep the canvas and ink they had before the shell moved")
    fun graphiteChrome() {
        assertEquals(LightPalette.canvas, ShellSystem.GRAPHITE.chrome(isDark = false).canvas)
        assertEquals(LightPalette.textPrimary, ShellSystem.GRAPHITE.chrome(isDark = false).content)
        assertEquals(DarkPalette.canvas, ShellSystem.GRAPHITE.chrome(isDark = true).canvas)
        assertEquals(DarkPalette.textPrimary, ShellSystem.GRAPHITE.chrome(isDark = true).content)
    }

    @Test
    @DisplayName("the two systems never share a canvas, so a mis-mapped tab is visible rather than subtle")
    fun systemsAreDistinguishable() {
        listOf(false, true).forEach { isDark ->
            assertNotEquals(
                ShellSystem.LOGBOOK.chrome(isDark).canvas,
                ShellSystem.GRAPHITE.chrome(isDark).canvas,
                "isDark=$isDark",
            )
        }
    }
}
