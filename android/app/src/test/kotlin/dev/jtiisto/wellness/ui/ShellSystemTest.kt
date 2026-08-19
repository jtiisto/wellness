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
            "journal" to ShellSystem.LOGBOOK, // flipped with its rendering phase
            "coach" to ShellSystem.LOGBOOK, // flipped with its rendering phase
            "trends" to ShellSystem.LOGBOOK, // flipped with its rendering phase
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
    @DisplayName("Coach is Logbook, and stays there: its composables read no Graphite tokens")
    fun coachIsLogbook() {
        // Pinned separately from the table above so a revert is as deliberate as
        // the flip was. Coach's composables now read `LogbookTheme` directly —
        // under `WellnessTheme` they would find the Logbook locals unprovided
        // and fall back to defaults, which is tokens meaning something else
        // rather than a degraded version of them. Analysis and Tools are the
        // ones still waiting for their round.
        assertEquals(ShellSystem.LOGBOOK, shellSystemFor("coach"))
    }

    @Test
    @DisplayName("Trends is Logbook, and its charts resolve their whole vocabulary from that palette")
    fun trendsIsLogbook() {
        // The third deliberate second pin. Trends carries the trap the other two
        // did not: `ChartTheme` and `PlotColors` used to answer from
        // `LocalWellnessPalette`, which has a silent *dark* Graphite default —
        // so a half-done flip would not have failed, it would have drawn
        // amber-on-graphite chrome over paper, plausible and wrong. Both now
        // resolve from `LogbookTheme.palette`, which is only correct while this
        // destination is Logbook.
        assertEquals(ShellSystem.LOGBOOK, shellSystemFor("trends"))
    }

    @Test
    @DisplayName("Journal is Logbook, and the start destination therefore opens on paper")
    fun journalIsLogbook() {
        // The same deliberate second pin coach got, and it carries more weight
        // here: journal is the *start* tab, so this assignment is also what the
        // launch window's `colors.xml` has to agree with. Flipping one without
        // the other is a frame of the wrong paper on every cold start — visible
        // on a device, invisible in a diff.
        assertEquals(ShellSystem.LOGBOOK, shellSystemFor("journal"))
        assertEquals(ShellSystem.LOGBOOK, startTab.system)
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
