package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerSwitchDao
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * Cancels whatever background work is outstanding, and does not return until it
 * is gone.
 *
 * An interface because `WorkManager.getInstance()` throws when the process
 * never initialized it, which is a real state in unit tests and a possible one
 * in a stripped-down build — the switch must not fail on it.
 */
fun interface BackgroundWorkCanceller {
    suspend fun cancelAndAwait()
}

/**
 * Wipe-and-resync: point the app at a different server and destroy everything
 * the previous one put here.
 *
 * The wipe itself is one transaction and the easy part. The hard part is being
 * certain nothing writes *after* it, and that is what the ordered sequence in
 * [quiesce] is for. `SyncScheduler.stop()` only cancels timers — by design, so
 * an upload is never abandoned halfway — so a cycle that is already past its
 * last suspension point keeps running, and its `applyDelta` would write the old
 * server's rows and, worse, the old watermark into the freshly emptied
 * database. The client would then believe it was caught up with the new server
 * while holding the old one's data, which is precisely the outcome the wipe
 * exists to prevent.
 *
 * So the order is: fence first, then stop, then wait. Closing
 * [ServerSessionGate] refuses every new write **and blocks until the writes
 * already in flight have finished** — which covers the local mutators too, not
 * just the network-fed ones. The mutex drains then sit on top, waiting out the
 * sync cycles themselves so nothing is between a response and a write when the
 * transaction opens. Both halves are needed: the lease alone would let a
 * scheduler start a fresh cycle a moment later, and the drain alone never saw a
 * UI edit suspended at `clientId()`.
 *
 * **This class ends the process itself.** A task-flag "restart" was rejected:
 * `NEW_TASK | CLEAR_TASK` plus `exit(0)` restarts a *task*, with no guarantee a
 * new process is ever launched, so the app could simply vanish. Closing
 * deterministically and saying so is worse UX for two seconds and correct every
 * time — and the exit belongs *here* rather than in a UI effect, because a
 * collector that dies with its screen would leave the app running against a
 * config only the next boot can apply. Everything from [quiesce] to the exit is
 * [NonCancellable] for the same reason: a caller cancelled between `close()` and
 * the transaction would strand a permanently closed gate and no way to notice.
 */
class ServerSwitcher(
    private val gate: ServerSessionGate,
    private val schedulers: List<SyncScheduler>,
    private val drains: List<suspend () -> Unit>,
    private val stopAnalysis: suspend () -> Unit,
    private val backgroundWork: BackgroundWorkCanceller,
    private val dao: ServerSwitchDao,
    private val sharedFiles: SharedFileStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val exitApp: () -> Unit = { exitProcess(0) },
) {

    private val _switching = MutableStateFlow(false)

    /**
     * True from the moment a switch is claimed until the process goes away, and
     * never false again: the gate is closed by then, so there is nothing left
     * this session can usefully do. The UI reads it to refuse a second switch,
     * a force sync, and anything else that would queue work against a server the
     * app is in the middle of leaving.
     */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /**
     * Switch to [target], or back to the built-in server when it is null.
     *
     * [fromNickname] is only used for the boundary log line — the profile it
     * names is about to stop being active, so it has to be read before the
     * transaction, not after.
     */
    suspend fun switchTo(target: ServerProfileEntity?, fromNickname: String) = commit {
        dao.switchTo(
            targetId = target?.id,
            boundary = boundaryLine(fromNickname, target?.nickname ?: BUILT_IN),
        )
    }

    /**
     * Delete the active profile, which necessarily returns to the built-in
     * server and therefore wipes. One dialog discloses both; one transaction
     * performs both.
     */
    suspend fun deleteActive(profile: ServerProfileEntity) = commit {
        dao.deleteActiveProfile(profile.id, boundaryLine(profile.nickname, BUILT_IN))
    }

    /**
     * Quiesce, clean up, commit [wipe], and end the process — as one
     * uninterruptible unit, at most once per process.
     *
     * The staged share files go **before** the transaction rather than after it.
     * They are a regenerable cache; the transaction is the commit point that
     * makes the next boot a different server's. Deleting after it leaves a
     * window in which the switch is durable and a stale export describing the
     * previous server's data is still on disk — and this process is about to
     * exit, so there is no second chance at the cleanup. Losing a staged export
     * to a wipe that then fails is the cheaper mistake of the two.
     *
     * [exitApp] ends the process and nothing else. The task record deliberately
     * survives in recents: clearing it would need `finishAffinity()` and so an
     * Activity, which this scope does not have and must not acquire — routing
     * the exit back through a UI collector is exactly the coupling that made it
     * unreliable. Reopening from recents cold-starts an empty process, which
     * runs boot resolution against the profile the transaction just made
     * active, so the surviving entry is cosmetic and the reopen is correct.
     */
    private suspend fun commit(wipe: suspend () -> Unit) {
        if (!_switching.compareAndSet(expect = false, update = true)) return
        withContext(NonCancellable) {
            quiesce()
            sharedFiles.deleteAll()
            wipe()
            exitApp()
        }
    }

    /**
     * Bring every writer to a standstill. The order is the contract; see the
     * class note for why each step cannot move.
     */
    private suspend fun quiesce() {
        // 1. Refuse every new server-scoped write, and WAIT for the writes
        //    already in flight — local mutators included. This is the step that
        //    makes the wipe's precondition true rather than merely likely.
        gate.close()
        // 2. No new cycles may be scheduled. Joined, not merely asked: `stop()`
        //    dispatches to the schedulers' main-confined state, and this runs
        //    off the main thread — without the join the drain below could start
        //    while a timer was still free to fire one more cycle.
        schedulers.map(SyncScheduler::stop).joinAll()
        // 3. Wait out the cycles already running. Their writes are already
        //    fenced; this is about not being mid-flight when the wipe commits.
        drains.forEach { it() }
        // 4. Analysis has a poll rather than a scheduler, and joins its own.
        stopAnalysis()
        // 5. A flush Worker could wake mid-wipe and start a sync of its own.
        backgroundWork.cancelAndAwait()
    }

    private fun boundaryLine(from: String, to: String): DebugLogEntity = DebugLogEntity(
        ts = now(),
        tag = TAG,
        message = "server switch: $from → $to",
    )

    companion object {
        const val TAG = "server"
        const val BUILT_IN = "Built-in"
    }
}
