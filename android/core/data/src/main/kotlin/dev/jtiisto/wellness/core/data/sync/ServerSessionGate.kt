package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.CompletableDeferred

/**
 * Thrown when a write is attempted after the session has closed. Not a failure
 * to recover from: the process is on its way out.
 */
class ServerSessionClosedException : IllegalStateException("the server session is closed")

/**
 * A one-way latch **and a writer lease**, process-wide, guarding every
 * server-scoped Room write for the duration of a server switch.
 *
 * A bare "is it still open?" check is not enough, and assuming otherwise was a
 * real defect. Two things go wrong with a check:
 *
 * - **It does not cover the write.** `requireOpen()` followed by a suspending
 *   `upsert` is a check-then-act: the write is admitted, the coroutine
 *   suspends, the switch closes and wipes, and the admitted write lands
 *   afterwards.
 * - **It never saw the local mutators at all.** A coach edit suspended at
 *   `clientId()` — a DAO read, and a suspension point — resumes after the wipe
 *   and commits a dirty row belonging to the previous server. Nothing about
 *   that write is "network-to-Room", which is why a fence defined that way
 *   missed it.
 *
 * So writers take a **lease** that spans the whole write, and [close] does two
 * things: it refuses new leases, and it **waits for every outstanding one** to
 * finish before returning. When it returns, no write is in flight and none can
 * start — which is the actual precondition the wipe needs.
 *
 * A writer that acquires a nested lease after [close] has begun gets a
 * [ServerSessionClosedException] rather than a deadlock: its outer lease is
 * released by the unwinding, and the refused write is exactly the outcome
 * wanted.
 *
 * There is no reopen. A closed gate means this process is finishing; the next
 * one starts with a fresh instance.
 */
class ServerSessionGate {

    private val lock = Any()
    private var open = true
    private var outstanding = 0
    private var drained: CompletableDeferred<Unit>? = null

    val isOpen: Boolean get() = synchronized(lock) { open }

    /** Outstanding leases. Diagnostics and tests only. */
    val leaseCount: Int get() = synchronized(lock) { outstanding }

    /**
     * Run [block] as the holder of a write lease.
     *
     * The lease has to be taken **before** the first suspension point of the
     * write it protects — including any read the write depends on, such as
     * `clientId()` — or the gap in front of it is the same check-then-act race
     * this exists to close.
     */
    suspend fun <T> withWriteLease(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            // Non-suspending, so a cancelled writer still gives its lease back
            // and cannot wedge a switch that is waiting on it.
            release()
        }
    }

    /** Terminal and idempotent, and it does not return until the writers have. */
    suspend fun close() {
        val waiter = synchronized(lock) {
            open = false
            when {
                outstanding == 0 -> null
                else -> drained ?: CompletableDeferred<Unit>().also { drained = it }
            }
        }
        waiter?.await()
    }

    private fun acquire() {
        synchronized(lock) {
            if (!open) throw ServerSessionClosedException()
            outstanding++
        }
    }

    private fun release() {
        val finished = synchronized(lock) {
            outstanding--
            if (outstanding == 0) drained else null
        }
        finished?.complete(Unit)
    }
}
