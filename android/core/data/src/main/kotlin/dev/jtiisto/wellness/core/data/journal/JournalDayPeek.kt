package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalDaySnapshot
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

/**
 * One day's journal, read straight off the DAO for a surface with no app around
 * it.
 *
 * The home-screen widget's tally is the caller. It cannot go through
 * [JournalSyncStore] — that store is built with a `JournalApi`, which wants a
 * `ServerConfig`, which is `ServerBootstrap.requireConfig()` and **throws**
 * before the boot decision has been made. A launcher may be the very thing that
 * created this process, so the render path resolves only singles whose whole
 * dependency graph is pre-resolution-safe: a DAO and the shared [Json], and
 * nothing else. (`TrendsCachePeek` exists for the same reason, one module over.)
 *
 * This class decides nothing. Every question about what counts as done today —
 * expected-on-schedule, met, partial, avoidance, observation — is [categoryRollup]'s,
 * already tested against the PWA's own suite; all this does is put one read in
 * front of it.
 */
class JournalDayPeek(
    private val journalDao: JournalDao,
    private val json: Json,
) {

    /**
     * [today]'s rollup over every tracker, or null when there is no day to draw.
     *
     * **The two failure modes answer differently, and the difference is the
     * contract:**
     *
     * - **null — the day could not be read, or asks nothing of you.** A DAO read
     *   that throws is reported as null (a launcher render has no error surface,
     *   and an unopenable database is not something a home screen can act on),
     *   and so is [categoryRollup]'s own null for a day with no tracker expected
     *   on it. The widget omits the element rather than drawing a zero.
     * - **throw — the day's data is corrupt.** A tracker or entry row this build
     *   cannot decode propagates, and the caller must surface it.
     *
     * The asymmetry is deliberate, and the earlier version had it backwards.
     * Skipping an undecodable tracker looks like the forgiving choice and is the
     * dangerous one: it shrinks the *denominator*, so a corrupt row that was
     * expected today silently turns "5 of 6 done" into "5 of 5 done" — a
     * confident wrong answer, which is the one thing a glanceable surface must
     * never produce. Honest absence beats wrong presence. Entry decoding was
     * always going to propagate for the identical reason (a dropped entry
     * mis-judges its tracker as not-yet); the two now agree.
     *
     * [CancellationException] is always rethrown untouched — a cancelled render
     * is not a failed one.
     */
    suspend fun rollup(today: DateString): CategoryRollup? {
        // One transaction, not two reads: a sync committing between a tracker
        // list and an entry list would hand this a day assembled out of two
        // different generations of itself, and nothing in the tally would look
        // wrong. See `JournalDaySnapshot`.
        val snapshot: JournalDaySnapshot = try {
            journalDao.daySnapshot(today)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (readFailure: Throwable) {
            return null
        }

        val trackers = snapshot.trackers.map { decodeTracker(it, json) }
        val dayLog = snapshot.entries.associate { it.trackerId to it.toDto(json) }
        return categoryRollup(trackers, today, dayLog)
    }

    /**
     * [rollup], observed — the widget collects this inside its Glance session,
     * so ticking a tracker in Journal redraws the home screen the moment the
     * row lands in Room instead of waiting for the session to die and reload
     * (a Glance session recomposes `provideContent` without re-running
     * `provideGlance`; anything captured outside the composition is frozen for
     * the session's life, which on a device read as a tally that ignored the
     * app).
     *
     * Two observables combined, not the transactional [JournalDao.daySnapshot]:
     * Room's flows cannot share one transaction, so an emission *can* pair a
     * tracker list and an entry list from either side of a sync commit — the
     * very race the one-shot closes. Here it is accepted: the next emission
     * self-corrects in the same breath, and the Journal screen itself lives on
     * the identical combine. The one-shot stays the choice wherever a single
     * read must stand alone.
     *
     * Failure contract matches [rollup] where a flow can express it: corrupt
     * rows **throw through the flow** (same denominator argument), and a DAO
     * that cannot be read errors the flow — the collector owns the
     * no-error-surface rule.
     */
    fun rollupFlow(today: DateString): Flow<CategoryRollup?> =
        combine(journalDao.observeTrackers(), journalDao.observeDay(today)) { trackerRows, entryRows ->
            val trackers = trackerRows.map { decodeTracker(it, json) }
            val dayLog = entryRows.associate { it.trackerId to it.toDto(json) }
            categoryRollup(trackers, today, dayLog)
        }
}
