package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.jtiisto.wellness.core.data.network.DateString

/**
 * Capture sessions: a handful of rows, written three times each at most.
 *
 * **Two ways to write, and the split is not stylistic.** [upsert] creates a row
 * or re-states a resumed one, and it is the only write that may present a whole
 * entity. Every mutation of a session that is already live goes through
 * [anchorWorkout] or [closeSession], which are single `UPDATE` statements
 * touching only their own columns.
 *
 * The reason is that `ServerSessionGate.withWriteLease` admits concurrent
 * writers by design — it fences against a server switch, not against each other.
 * A read-modify-write of the whole row therefore has a window in which another
 * writer's committed change is read, discarded, and written back as it was: an
 * anchor could reopen a closed session, a close could erase a fresh anchor.
 * Narrowing each mutation to the columns it owns removes the window instead of
 * racing inside it.
 *
 * No indices — the table is one row per capture and every read is a scan over a
 * set small enough that an index would cost more on write than it saves.
 *
 * An abstract class rather than an interface so [upsert] is real code a JVM fake
 * inherits, the same reason [CoachDao] is one.
 */
@Dao
abstract class HrSessionDao {

    @Query("SELECT * FROM hr_sessions WHERE sessionId = :sessionId")
    abstract suspend fun find(sessionId: String): HrSessionEntity?

    /**
     * Sessions the server has not acknowledged, oldest first.
     *
     * Quarantined rows are excluded, as they are on the other two tables. The
     * difference is that a session can leave quarantine: [upsert] clears the
     * flag when the row's content changes, so a corrected session reappears
     * here without anything having to remember it was rejected.
     */
    @Query("SELECT * FROM hr_sessions WHERE isSynced = 0 AND isQuarantined = 0 ORDER BY startedAtMs")
    abstract suspend fun needsUpload(): List<HrSessionEntity>

    /**
     * The session a restarted capture service resumes into.
     *
     * Newest rather than only, because a crash between "finish the old session"
     * and "close it" can leave more than one open and the recent one is the one
     * the strap is still feeding.
     */
    @Query("SELECT * FROM hr_sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    abstract suspend fun newestOpen(): HrSessionEntity?

    @Query("SELECT * FROM hr_sessions ORDER BY startedAtMs")
    abstract suspend fun listAll(): List<HrSessionEntity>

    /**
     * Matches [needsUpload], quarantine exclusion included — the flush scheduler
     * reads this to decide whether there is work at all, and a row it will never
     * offer must not keep it awake.
     */
    @Query("SELECT COUNT(*) FROM hr_sessions WHERE isSynced = 0 AND isQuarantined = 0")
    abstract suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM hr_sessions WHERE isQuarantined = 1")
    abstract suspend fun countQuarantined(): Int

    @Upsert
    abstract suspend fun upsertRow(row: HrSessionEntity)

    /**
     * Land an upload's verdict, but **only against the row that was uploaded**.
     *
     * [generation] is the one the batch carried out of [needsUpload], and a
     * session that has been rewritten since — anchored to a workout, or closed —
     * no longer matches, so this no-ops and the row stays pending. Without the
     * guard the response would mark the *new* row synced against a server that
     * only ever received the old one, and the session's final state would never
     * upload at all. Same guard as `CoachDao.clearLogDirty`, for the same race.
     */
    @Query("UPDATE hr_sessions SET isSynced = 1 WHERE sessionId = :sessionId AND dirtyGeneration = :generation")
    abstract suspend fun markSynced(sessionId: String, generation: Long)

    /**
     * The 422 bisect's verdict on one session, guarded identically.
     *
     * A stale quarantine is the worse of the two misses: it would strand a row
     * the server never rejected, and only another content change would free it.
     */
    @Query(
        "UPDATE hr_sessions SET isQuarantined = 1 " +
            "WHERE sessionId = :sessionId AND dirtyGeneration = :generation",
    )
    abstract suspend fun markQuarantined(sessionId: String, generation: Long)

    @Query("DELETE FROM hr_sessions WHERE sessionId = :sessionId")
    abstract suspend fun delete(sessionId: String)

    /**
     * Attach a **still-open** session to a workout, in one statement.
     *
     * `endedAtMs IS NULL` is the whole point. Read the row, copy the anchor onto
     * it and upsert it back, and a close that commits in between is undone: the
     * copy still carries the null `endedAtMs` it was read with, and a finished
     * session silently reopens. Leases do not help — concurrent readers are
     * allowed to hold one at the same time — so the window has to be closed in
     * SQL rather than around it.
     *
     * Anchoring a session that has already ended is meaningless, so it no-ops
     * and the caller is told: **0 means the session was not open**, either
     * because it closed or because the row is gone.
     *
     * The generation bump and the flag clears are unconditional here, not
     * content-compared as [upsert]'s are. A caller anchoring to values the row
     * already holds therefore costs one redundant upload — the price of keeping
     * a 0 return unambiguously "not open" rather than also meaning "no change".
     */
    @Query(
        "UPDATE hr_sessions SET workoutDate = :workoutDate, workoutSessionId = :workoutSessionId, " +
            "isSynced = 0, isQuarantined = 0, dirtyGeneration = dirtyGeneration + 1 " +
            "WHERE sessionId = :sessionId AND endedAtMs IS NULL",
    )
    abstract suspend fun anchorWorkout(
        sessionId: String,
        workoutDate: DateString,
        workoutSessionId: Long?,
    ): Int

    /**
     * Close a **still-open** session, in one statement.
     *
     * The mirror of [anchorWorkout] and the other half of the same race: a stop
     * that read the row before an anchor landed would write back the stale null
     * `workoutDate` and erase it. Touching only `endedAtMs` leaves every other
     * column exactly as whoever wrote it last left it.
     *
     * The same guard makes closing idempotent and makes reopening impossible:
     * a second close matches nothing and returns **0, meaning the session was
     * already closed** (or the row is gone), rather than moving `endedAtMs` to a
     * later instant and claiming coverage of samples that were never stored.
     */
    @Query(
        "UPDATE hr_sessions SET endedAtMs = :endedAtMs, " +
            "isSynced = 0, isQuarantined = 0, dirtyGeneration = dirtyGeneration + 1 " +
            "WHERE sessionId = :sessionId AND endedAtMs IS NULL",
    )
    abstract suspend fun closeSession(sessionId: String, endedAtMs: Long): Int

    /**
     * Write a session and derive **all three** pieces of upload state from what
     * actually changed.
     *
     * [row]'s own `isSynced`, `isQuarantined` and `dirtyGeneration` are ignored:
     * callers describe the session, not its upload state. Any difference in
     * content clears both flags and bumps the generation; a byte-identical
     * rewrite leaves all three alone.
     *
     * Keeping `isSynced` is what stops the service re-posting the same row on
     * every `START_STICKY` restart. Clearing `isQuarantined` gives a corrected
     * session another attempt. Bumping `dirtyGeneration` is what makes the
     * in-flight case safe: a verdict computed against the row as it was cannot
     * land on the row as it now is, so the rewrite stays pending and uploads on
     * the next pass.
     *
     * The read has to happen inside the transaction. Comparing against a row
     * fetched beforehand would race a concurrent [markSynced] or
     * [markQuarantined] — and would also be able to write back a stale
     * generation, which is worse than the flag it was trying to protect: a
     * verdict that should have missed would start matching.
     */
    @Transaction
    open suspend fun upsert(row: HrSessionEntity) {
        val existing = find(row.sessionId)
        val unchanged = existing != null && existing.content() == row.content()
        val storedGeneration = existing?.dirtyGeneration ?: 0L
        upsertRow(
            row.copy(
                isSynced = unchanged && existing?.isSynced == true,
                isQuarantined = unchanged && existing?.isQuarantined == true,
                dirtyGeneration = if (unchanged) storedGeneration else storedGeneration + 1,
            ),
        )
    }
}

/**
 * The row as the server would see it: both local flags and the generation
 * normalized away, so none of the three can make a session look edited.
 */
private fun HrSessionEntity.content(): HrSessionEntity =
    copy(isSynced = false, isQuarantined = false, dirtyGeneration = 0)
