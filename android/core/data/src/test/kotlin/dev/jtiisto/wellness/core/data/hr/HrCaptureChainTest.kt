package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.ble.buffer.BufferConfig
import dev.jtiisto.wellness.core.ble.buffer.IntervalBuffer
import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import dev.jtiisto.wellness.core.data.db.FakeHrSampleDao
import dev.jtiisto.wellness.core.data.db.FakeHrSessionDao
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private const val NOW = 1_800_000_000_000L
private const val DEVICE = "AA:BB:CC:DD:EE:FF"

/**
 * The buffer and the store as one chain, with the real [ServerSessionGate]
 * between them.
 *
 * Both halves are covered on their own, and both are right on their own. What
 * neither can show is that the contract *between* them holds: the store throws a
 * refused write, and the buffer has to read that as "keep the batch" rather than
 * "these are stored". A stub sink in the buffer's tests can only prove the buffer
 * honours a throw it was told to expect; a fake gate in the store's tests can
 * only prove the store throws. This is the join.
 *
 * The case is a server switch confirmed mid-capture, which closes the gate under
 * a running strap.
 */
class HrCaptureChainTest {

    private class Chain(scope: CoroutineScope) {
        val sessionDao = FakeHrSessionDao()
        val sampleDao = FakeHrSampleDao()
        val gate = ServerSessionGate()
        var scheduled = 0
        private var minted = 0

        val store = HrCaptureStore(
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            session = gate,
            scope = scope,
            scheduleUpload = { scheduled++ },
            newSessionId = { "session-${++minted}" },
            now = { NOW },
        )

        /** The real buffer, sinking into the real store. */
        val buffer = IntervalBuffer(
            sink = store,
            scope = scope,
            config = BufferConfig(maxBufferSize = 1000),
        )

        fun beat(receivedAtMs: Long) = HeartRateSample(
            deviceId = DEVICE,
            receivedAtMs = receivedAtMs,
            heartRateBpm = 142,
            rrIntervalsMs = listOf(428),
        )
    }

    @Test
    @DisplayName("a gate closed mid-capture keeps the beats buffered and leaves the session open")
    fun closedGateKeepsTheBatchAndTheSession() = runTest {
        val chain = Chain(backgroundScope)
        val id = chain.store.startSession(DEVICE)
        chain.buffer.add(chain.beat(NOW), id)
        chain.buffer.add(chain.beat(NOW + 1000), id)

        // The server switch: every server-scoped write is refused from here on.
        chain.gate.close()

        // The stop path runs the final flush, which the store refuses.
        val result = chain.store.stopSession(chain.buffer::flush)

        assertEquals(CaptureStopResult.LEFT_OPEN, result)
        // The beats are still in memory rather than silently dropped — the whole
        // point of the sink throwing instead of returning.
        assertEquals(2, chain.buffer.size)
        assertTrue(chain.sampleDao.samples.isEmpty())
        // And the session stays open, because nothing it covers is durable.
        assertNull(chain.sessionDao.sessions.getValue(id).endedAtMs)
        assertNull(chain.store.currentSessionId)
    }

    @Test
    @DisplayName("a flush refused by the gate reports failure rather than claiming success")
    fun refusedFlushReportsFailure() = runTest {
        val chain = Chain(backgroundScope)
        val id = chain.store.startSession(DEVICE)
        chain.buffer.add(chain.beat(NOW), id)
        chain.gate.close()

        assertFalse(chain.buffer.flush())

        assertEquals(1, chain.buffer.size)
    }

    /**
     * Also the deadlock guard, and the reason it is worth having here rather than
     * only as a control.
     *
     * `stopSession` runs its final flush through the buffer, which sinks back
     * into the store, which takes the store's lifecycle lock to finish any
     * deferred close. Holding that lock across the flush — the obvious way to
     * serialize the lifecycle — deadlocks exactly this path, and the only place
     * the re-entrancy is visible is with the real buffer on both ends. A
     * regression here hangs rather than fails, which `runTest` surfaces as a
     * timeout.
     */
    @Test
    @DisplayName("the same chain persists and closes normally while the gate is open")
    fun openGateStoresAndCloses() = runTest {
        val chain = Chain(backgroundScope)
        val id = chain.store.startSession(DEVICE)
        chain.buffer.add(chain.beat(NOW), id)
        chain.buffer.add(chain.beat(NOW + 1000), id)

        val result = chain.store.stopSession(chain.buffer::flush)

        // The control case, so the one above cannot pass for the wrong reason.
        assertEquals(CaptureStopResult.CLOSED, result)
        assertEquals(0, chain.buffer.size)
        assertEquals(2, chain.sampleDao.samples.size)
        assertEquals(NOW, chain.sessionDao.sessions.getValue(id).endedAtMs)
        assertTrue(chain.sampleDao.samples.values.all { it.sessionId == id })
    }
}
