package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * The server switch's one write: empty every table that holds server data, move
 * the active flag, and record the boundary — all or nothing.
 *
 * **Eleven tables go, two stay.** `journal_trackers`, `journal_entries`,
 * `journal_meta`, `coach_plans`, `coach_logs`, `coach_meta`, `payload_cache`
 * and `trends_meta` all hold data that came from — or is destined for — one
 * specific server, `journal_meta` and `coach_meta` most of all: they carry the
 * client ids and the sync watermarks, and an empty watermark *is* the request
 * for a full pull, which is how the new server's data arrives without any
 * explicit "resync" step at all.
 *
 * `hr_sessions`, `hr_samples` and `set_events` go for the harder version of the
 * same reason: they are *destined* for the server being left. Nothing arbitrates
 * them, so keeping them would upload one server's captures into another's `hr`
 * module the moment the switch finished — telemetry silently attributed to the
 * wrong place, with no watermark or dirty flag anywhere to notice it.
 *
 * `server_profiles` survives because it is the list the user just used, and
 * `debug_log` survives because the switch is exactly the kind of event someone
 * reads the log to understand afterwards.
 */
@Dao
abstract class ServerSwitchDao {

    @Query("DELETE FROM journal_trackers")
    abstract suspend fun clearJournalTrackers()

    @Query("DELETE FROM journal_entries")
    abstract suspend fun clearJournalEntries()

    @Query("DELETE FROM journal_meta")
    abstract suspend fun clearJournalMeta()

    @Query("DELETE FROM coach_plans")
    abstract suspend fun clearCoachPlans()

    @Query("DELETE FROM coach_logs")
    abstract suspend fun clearCoachLogs()

    @Query("DELETE FROM coach_meta")
    abstract suspend fun clearCoachMeta()

    @Query("DELETE FROM payload_cache")
    abstract suspend fun clearPayloadCache()

    @Query("DELETE FROM trends_meta")
    abstract suspend fun clearTrendsMeta()

    @Query("DELETE FROM hr_sessions")
    abstract suspend fun clearHrSessions()

    @Query("DELETE FROM hr_samples")
    abstract suspend fun clearHrSamples()

    @Query("DELETE FROM set_events")
    abstract suspend fun clearSetEvents()

    @Query("UPDATE server_profiles SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    abstract suspend fun activate(id: Long)

    @Query("UPDATE server_profiles SET isActive = 0")
    abstract suspend fun clearActive()

    @Query("DELETE FROM server_profiles WHERE id = :id")
    abstract suspend fun deleteProfile(id: Long)

    /**
     * The boundary line, written **synchronously inside the transaction**.
     *
     * `DebugLog.log()` is fire-and-forget on an IO scope, which is right for
     * everything else and wrong for this: the process is about to call
     * `exit(0)`, and a queued write would lose the race. Without the line, a log
     * dump taken after a switch would show two servers' traffic with nothing to
     * mark where one ended.
     */
    @Insert
    abstract suspend fun insertLogLine(entry: DebugLogEntity)

    /**
     * Switch to [targetId], or to the built-in server when it is null.
     *
     * One transaction, so there is no instant in which the data is gone but the
     * app still points at the server it came from — or the reverse, which would
     * be worse: pointing at the new server while the old one's dirty rows sit
     * waiting to upload.
     */
    @Transaction
    open suspend fun switchTo(targetId: Long?, boundary: DebugLogEntity) {
        wipeServerData()
        if (targetId == null) clearActive() else activate(targetId)
        insertLogLine(boundary)
    }

    /**
     * Delete the profile that is currently active, which necessarily returns to
     * the built-in server.
     *
     * Not a delete followed by a switch: those are two transactions, and a crash
     * between them leaves the previous server's data in place with no profile to
     * explain where it came from. One dialog covers both halves, so one
     * transaction performs them.
     */
    @Transaction
    open suspend fun deleteActiveProfile(id: Long, boundary: DebugLogEntity) {
        wipeServerData()
        deleteProfile(id)
        clearActive()
        insertLogLine(boundary)
    }

    private suspend fun wipeServerData() {
        clearJournalTrackers()
        clearJournalEntries()
        clearJournalMeta()
        clearCoachPlans()
        clearCoachLogs()
        clearCoachMeta()
        clearPayloadCache()
        clearTrendsMeta()
        clearHrSessions()
        clearHrSamples()
        clearSetEvents()
    }
}
