package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.db.TrendsMetaDao
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
 */
class TrendsPrefs(private val dao: TrendsMetaDao) {

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

    private suspend fun put(key: String, value: String) = writeMutex.withLock { dao.put(key, value) }

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
