package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.db.TrendsMetaDao
import dev.jtiisto.wellness.core.data.db.TrendsMetaEntity
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Trends tab's remembered view state.
 *
 * The defaults are the interesting part: a first launch has no rows at all, and
 * the screen has to open on *something* rather than on an empty range that
 * fetches nothing.
 */
class TrendsPrefsTest {

    private val dao = FakeTrendsMetaDao()
    private val session = ServerSessionGate()
    private val prefs = TrendsPrefs(dao, session)

    @Test
    @DisplayName("a first launch defaults to the 12-week overview")
    fun defaults() = runTest {
        assertEquals("12w", prefs.range.first())
        assertEquals("overview", prefs.screen.first())
    }

    @Test
    @DisplayName("the selections have no default — the screen falls back to the first item instead")
    fun selectionsStartUnset() = runTest {
        assertNull(prefs.exercise.first())
        assertNull(prefs.tracker.first())
        assertNull(prefs.labPanel.first())
    }

    @Test
    @DisplayName("every setter writes through and reads back")
    fun writeThrough() = runTest {
        prefs.setRange("4w")
        prefs.setScreen("health")
        prefs.setExercise("fixture-press")
        prefs.setTracker("fixture-tracker-alpha")
        prefs.setLabPanel("Fixture Panel One")

        assertEquals("4w", prefs.range.first())
        assertEquals("health", prefs.screen.first())
        assertEquals("fixture-press", prefs.exercise.first())
        assertEquals("fixture-tracker-alpha", prefs.tracker.first())
        assertEquals("Fixture Panel One", prefs.labPanel.first())
    }

    @Test
    @DisplayName("writes land under the ui. prefix, which is what marks them device-local")
    fun keysAreUiPrefixed() = runTest {
        prefs.setRange("6m")
        prefs.setScreen("cardio")
        prefs.setExercise("fixture-press")
        prefs.setTracker("fixture-tracker-alpha")
        prefs.setLabPanel("Fixture Panel One")

        assertEquals(
            setOf("ui.range", "ui.screen", "ui.exercise", "ui.tracker", "ui.labPanel"),
            dao.rows.keys,
        )
        assertTrue(dao.rows.keys.all { it.startsWith("ui.") })
    }

    @Test
    @DisplayName("re-selecting overwrites rather than accumulating")
    fun overwrite() = runTest {
        prefs.setRange("4w")
        prefs.setRange("all")

        assertEquals("all", prefs.range.first())
        assertEquals(1, dao.rows.size)
    }

    @Test
    @DisplayName("one preference's write leaves the others alone")
    fun keysAreIndependent() = runTest {
        prefs.setRange("4w")
        prefs.setTracker("fixture-tracker-alpha")

        prefs.setRange("6m")

        assertEquals("6m", prefs.range.first())
        assertEquals("fixture-tracker-alpha", prefs.tracker.first())
        assertEquals("overview", prefs.screen.first())
    }

    @Test
    @DisplayName("a write after the session closes is refused — `trends_meta` is a wiped table")
    fun postCloseWriteIsRefused() = runTest {
        session.close()

        prefs.setExercise("fixture-press")

        // The selection is not purely local: it names an exercise the PREVIOUS
        // server's slice returned, and landing it in the emptied table would
        // point the new server's charts at something it has never heard of.
        assertTrue(dao.rows.isEmpty(), "a post-wipe selection reached trends_meta")
    }

    @Test
    @DisplayName("the refusal is swallowed: a reconciliation after a switch must not take the tab down")
    fun refusalDoesNotEscape() = runTest {
        prefs.setRange("4w")
        session.close()

        // Every caller is a tap handler or a post-load reconciliation on a UI
        // scope, and the write it lost is a row the switch is about to delete.
        prefs.setRange("6m")

        assertEquals("4w", dao.rows[TrendsPrefs.KEY_RANGE])
    }
}

/** In-memory stand-in: Room itself only ever runs in the instrumented tests. */
internal class FakeTrendsMetaDao : TrendsMetaDao() {

    val rows = mutableMapOf<String, String>()

    private val revisions = MutableStateFlow(0)

    override fun observe(key: String): Flow<String?> = revisions.map { rows[key] }

    override suspend fun get(key: String): String? = rows[key]

    override suspend fun upsert(row: TrendsMetaEntity) {
        rows[row.key] = row.value
        revisions.value += 1
    }
}
