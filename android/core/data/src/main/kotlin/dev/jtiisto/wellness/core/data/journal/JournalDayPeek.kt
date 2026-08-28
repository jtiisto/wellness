package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalDaySnapshot
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.coroutines.CancellationException
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
}
