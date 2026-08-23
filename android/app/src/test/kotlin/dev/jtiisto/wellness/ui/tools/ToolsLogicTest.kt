package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * How the server list renders, and what the destructive dialogs promise.
 *
 * The copy is asserted verbatim because three of these sentences are the only
 * warning a user gets before their local data is destroyed; a warning that has
 * drifted from what the button does is worse than none.
 */
class ToolsLogicTest {

    private val builtInUrl = "https://built-in/wellness"

    private fun profile(id: Long, nickname: String, active: Boolean = false) =
        ServerProfileEntity(id = id, nickname = nickname, url = "https://host-$id/wellness", isActive = active)

    @Test
    @DisplayName("the built-in server is always the first row")
    fun builtInIsFirst() {
        val rows = ToolsLogic.rows(listOf(profile(1, "Laptop")), builtInUrl)

        assertEquals(ToolsCopy.BUILT_IN, rows.first().nickname)
        assertEquals(builtInUrl, rows.first().url)
        assertTrue(rows.first().isBuiltIn)
    }

    @Test
    @DisplayName("the built-in row has no id, which is what makes it undeletable")
    fun builtInHasNoId() {
        val rows = ToolsLogic.rows(emptyList(), builtInUrl)

        // Not a flag the UI has to remember to check: there is simply no row to
        // delete or edit.
        assertEquals(null, rows.single().id)
    }

    @Test
    @DisplayName("the built-in row is active exactly when no saved profile is")
    fun builtInActiveWhenNoneAre() {
        assertTrue(ToolsLogic.rows(listOf(profile(1, "Laptop")), builtInUrl).first().isActive)
        assertFalse(ToolsLogic.rows(listOf(profile(1, "Laptop", active = true)), builtInUrl).first().isActive)
    }

    @Test
    @DisplayName("saved profiles follow in ascending id order, whatever order they arrive in")
    fun ascendingIdOrder() {
        val rows = ToolsLogic.rows(
            listOf(profile(3, "Third"), profile(1, "First"), profile(2, "Second")),
            builtInUrl,
        )

        // Insertion order, so a row never moves under a finger about to tap it.
        assertEquals(listOf(ToolsCopy.BUILT_IN, "First", "Second", "Third"), rows.map { it.nickname })
    }

    @Test
    @DisplayName("duplicate nicknames are kept: two names for one host is a reasonable thing to want")
    fun duplicatesAreKept() {
        val rows = ToolsLogic.rows(listOf(profile(1, "Same"), profile(2, "Same")), builtInUrl)

        assertEquals(3, rows.size)
    }

    @Test
    @DisplayName("exactly one row is ever marked active")
    fun oneActiveRow() {
        val rows = ToolsLogic.rows(
            listOf(profile(1, "A"), profile(2, "B", active = true), profile(3, "C")),
            builtInUrl,
        )

        assertEquals(listOf("B"), rows.filter { it.isActive }.map { it.nickname })
    }

    @Test
    @DisplayName("the header names the active profile, or the built-in server")
    fun activeNickname() {
        assertEquals(ToolsCopy.BUILT_IN, ToolsLogic.activeNickname(emptyList()))
        assertEquals(ToolsCopy.BUILT_IN, ToolsLogic.activeNickname(listOf(profile(1, "Laptop"))))
        assertEquals("Laptop", ToolsLogic.activeNickname(listOf(profile(1, "Laptop", active = true))))
    }

    @Test
    @DisplayName("the delete dialog for an inactive profile promises only a list edit")
    fun deleteCopy() {
        assertEquals("Delete server?", ToolsCopy.DELETE_TITLE)
        assertEquals("""Remove "Laptop" from the list?""", ToolsCopy.deleteBody("Laptop"))
    }

    @Test
    @DisplayName("the switch dialog names the data loss, the target and the close")
    fun switchCopy() {
        assertEquals("Switch server?", ToolsCopy.SWITCH_TITLE)
        assertEquals(
            "This wipes ALL local data on this device — including any unsynced changes — " +
                """and loads everything from "Laptop" instead. The app will close; reopen it to continue.""",
            ToolsCopy.switchBody("Laptop"),
        )
    }

    @Test
    @DisplayName("deleting the ACTIVE profile discloses both the deletion and the data loss in one sentence")
    fun deleteActiveCopy() {
        // Splitting this into two confirmations would let someone accept the
        // delete without ever being told the data goes with it.
        val body = ToolsCopy.deleteActiveBody("Laptop")

        assertEquals(
            """Delete "Laptop"? This also wipes all local data loaded from it and returns to """ +
                "the built-in server. The app will close; reopen it to continue.",
            body,
        )
        assertTrue(body.contains("wipes all local data"))
        assertTrue(body.contains("built-in server"))
    }

    @Test
    @DisplayName("the empty state talks about saved servers, since the built-in one is always listed")
    fun emptyStateCopy() {
        assertEquals("No saved servers.", ToolsCopy.EMPTY_PROFILES)
    }

    // ---- the build row's label ---------------------------------------------

    @Test
    @DisplayName("the build label is the stamped instant, human-readable in the given locale and zone")
    fun buildLabelFormatsTheStamp() {
        // 2030-01-03T12:00:00Z — the far-future fixture convention, at UTC so
        // the expectation is exact.
        assertEquals(
            "Built Jan 3, 2030, 12:00\u202FPM",
            ToolsCopy.buildLabel("1893672000000", Locale.US, ZoneId.of("UTC")),
        )
        // Whitespace from the file read is not a parse failure.
        assertEquals(
            "Built Jan 3, 2030, 12:00\u202FPM",
            ToolsCopy.buildLabel("1893672000000\n", Locale.US, ZoneId.of("UTC")),
        )
    }

    @Test
    @DisplayName("a stamp that will not parse degrades to unknown — a wrong time is worse than none")
    fun buildLabelDegrades() {
        assertEquals("Build unknown", ToolsCopy.buildLabel(null, Locale.US, ZoneId.of("UTC")))
        assertEquals("Build unknown", ToolsCopy.buildLabel("", Locale.US, ZoneId.of("UTC")))
        assertEquals("Build unknown", ToolsCopy.buildLabel("not-a-stamp", Locale.US, ZoneId.of("UTC")))
    }
}
