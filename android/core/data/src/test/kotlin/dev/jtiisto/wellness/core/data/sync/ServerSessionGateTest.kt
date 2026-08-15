package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The writer lease.
 *
 * The close-awaits-outstanding property is the one that matters: without it the
 * gate is only a boolean check, and a write already past it lands after the
 * wipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSessionGateTest {

    @Test
    @DisplayName("an open gate grants leases and runs the write")
    fun openGateGrantsLeases() = runTest {
        val gate = ServerSessionGate()

        assertEquals("written", gate.withWriteLease { "written" })
        assertEquals(0, gate.leaseCount, "the lease must be given back")
    }

    @Test
    @DisplayName("a closed gate refuses new leases")
    fun closedGateRefusesLeases() = runTest {
        val gate = ServerSessionGate()
        gate.close()

        assertFalse(gate.isOpen)
        assertThrows<ServerSessionClosedException> { gate.withWriteLease { } }
    }

    @Test
    @DisplayName("close does not return while a write is still in flight")
    fun closeAwaitsOutstandingLeases() = runTest {
        val gate = ServerSessionGate()
        val barrier = CompletableDeferred<Unit>()
        var wrote = false
        var closed = false

        val writer = launch { gate.withWriteLease { barrier.await(); wrote = true } }
        runCurrent()
        assertEquals(1, gate.leaseCount)

        val closer = launch { gate.close(); closed = true }
        runCurrent()
        // This is the whole contract: the wipe's precondition is not "no new
        // writes", it is "no writes at all".
        assertFalse(closed, "close returned while a write was still running")
        assertFalse(wrote)

        barrier.complete(Unit)
        writer.join()
        closer.join()
        assertTrue(wrote)
        assertTrue(closed)
    }

    @Test
    @DisplayName("close waits for every outstanding lease, not just the first")
    fun closeAwaitsAllLeases() = runTest {
        val gate = ServerSessionGate()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var closed = false

        val a = launch { gate.withWriteLease { first.await() } }
        val b = launch { gate.withWriteLease { second.await() } }
        runCurrent()
        assertEquals(2, gate.leaseCount)

        val closer = launch { gate.close(); closed = true }
        runCurrent()

        first.complete(Unit)
        a.join()
        runCurrent()
        assertFalse(closed, "one writer finishing is not all of them")

        second.complete(Unit)
        b.join()
        closer.join()
        assertTrue(closed)
    }

    @Test
    @DisplayName("a lease is returned even when the write throws")
    fun leaseIsReleasedOnFailure() = runTest {
        val gate = ServerSessionGate()

        assertThrows<IllegalStateException> {
            gate.withWriteLease { throw IllegalStateException("write failed") }
        }

        assertEquals(0, gate.leaseCount, "a failed write must not wedge a later switch")
        gate.close()
    }

    @Test
    @DisplayName("a nested lease taken after the close is refused rather than deadlocking")
    fun nestedLeaseAfterCloseIsRefused() = runTest {
        val gate = ServerSessionGate()
        val barrier = CompletableDeferred<Unit>()
        var nestedRefused = false

        val writer = launch {
            gate.withWriteLease {
                barrier.await()
                // The switch closed while this outer lease was held. Refusing
                // here unwinds the outer lease, which lets close() finish.
                try {
                    gate.withWriteLease { }
                } catch (_: ServerSessionClosedException) {
                    nestedRefused = true
                }
            }
        }
        runCurrent()
        val closer = launch { gate.close() }
        runCurrent()

        barrier.complete(Unit)
        writer.join()
        closer.join()
        assertTrue(nestedRefused)
    }

    @Test
    @DisplayName("closing twice is harmless, and there is deliberately no way to reopen")
    fun closeIsTerminalAndIdempotent() = runTest {
        val gate = ServerSessionGate()

        gate.close()
        gate.close()

        assertFalse(gate.isOpen)
        assertTrue(ServerSessionGate().isOpen)
    }
}
