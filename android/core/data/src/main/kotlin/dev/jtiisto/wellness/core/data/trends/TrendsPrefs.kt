package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.db.TrendsMetaDao
import dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Trends tab's remembered view state: which range, which sub-screen, and
 * the last exercise / tracker / lab panel that was looked at.
 *
 * The PWA keeps these in `localStorage`; here they live in `trends_meta` under
 * the same `ui.` prefix the journal preferences use, so a glance at the table
 * says which rows are local-only.
 *
 * Writes go through a mutex even though each one touches a single key. Two
 * taps in flight at once would otherwise land in whichever order the DAO
 * happened to schedule them, and the last one tapped is the one the user
 * expects to survive.
 *
 * @param session the server-switch fence. `trends_meta` is a wiped table and
 *   these values are not purely local: a selection reconciled against a slice
 *   the *previous* server returned would otherwise land in the emptied table
 *   and point the new server's charts at an exercise it has never heard of.
 */
class TrendsPrefs(
    private val dao: TrendsMetaDao,
    private val session: ServerSessionGate,
) {

    private val writeMutex = Mutex()

    val range: Flow<String> = dao.observe(KEY_RANGE).map { it ?: DEFAULT_RANGE }

    val screen: Flow<String> = dao.observe(KEY_SCREEN).map { it ?: DEFAULT_SCREEN }

    /** Null until something has been picked; the screen then falls back to the first item. */
    val exercise: Flow<String?> = dao.observe(KEY_EXERCISE)

    val tracker: Flow<String?> = dao.observe(KEY_TRACKER)

    val labPanel: Flow<String?> = dao.observe(KEY_LAB_PANEL)

    suspend fun setRange(value: String) = put(KEY_RANGE, value)

    suspend fun setScreen(value: String) = put(KEY_SCREEN, value)

    suspend fun setExercise(value: String) = put(KEY_EXERCISE, value)

    suspend fun setTracker(value: String) = put(KEY_TRACKER, value)

    suspend fun setLabPanel(value: String) = put(KEY_LAB_PANEL, value)

    /**
     * A self-contained mark: the value is already in hand, so only the write
     * itself is fenced.
     *
     * A refusal is swallowed rather than raised. Every caller is a tap handler
     * or a post-load reconciliation on a UI scope, and the switch that refused
     * this write is about to wipe the row it would have written — crashing the
     * tab on the way out would be the only visible consequence.
     */
    private suspend fun put(key: String, value: String) = writeMutex.withLock {
        try {
            session.withWriteLease { dao.put(key, value) }
        } catch (_: ServerSessionClosedException) {
            Unit
        }
    }

    companion object {
        const val KEY_RANGE = "ui.range"
        const val KEY_SCREEN = "ui.screen"
        const val KEY_EXERCISE = "ui.exercise"
        const val KEY_TRACKER = "ui.tracker"
        const val KEY_LAB_PANEL = "ui.labPanel"

        const val DEFAULT_RANGE = "12w"
        const val DEFAULT_SCREEN = "overview"
    }
}
