package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.FakeHrSampleDao
import dev.jtiisto.wellness.core.data.db.FakeHrSessionDao
import dev.jtiisto.wellness.core.data.db.FakeSetEventDao
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.db.key
import dev.jtiisto.wellness.core.data.network.HrApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncFlushWorker
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncSkipReason
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/** The one response shape the mock server ever builds. */
private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

/**
 * The upload pipeline: what happens to rows, per status code.
 *
 * The 422 bisect is the reason this file is long. It is the one place where the
 * client throws data away, and the difference between "this row is invalid" and
 * "this server is refusing everything" is a difference between quarantining one
 * beat and quarantining a workout — so the isolation, both circuit breakers and
 * the statuses that must *not* bisect each get pinned separately.
 *
 * Everything runs against Stage A's DAO fakes, whose behaviour `HrDaoTest`
 * asserts against the real schema in `androidTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HrSyncStoreTest {

    @AfterEach
    fun tearDown() {
        // Only the debounce test installs Main; resetting unconditionally is
        // cheap and keeps a failure there from leaking into the next class.
        Dispatchers.resetMain()
    }

    // ---- the happy path --------------------------------------------------

    @Test
    @DisplayName("a 2xx marks exactly the rows it sent")
    fun successMarksSynced() = runTest {
        val world = World()
        world.seedEvents(3)

        val result = world.store().flushEvents()

        assertTrue(result.success)
        assertEquals(1, world.requests.size)
        assertEquals(3, world.requests.single().rows)
        assertTrue(world.eventDao.events.values.all { it.isSynced })
        assertTrue(world.eventDao.events.values.none { it.isQuarantined })
    }

    @Test
    @DisplayName("nothing pending sends nothing at all")
    fun emptyFlushIsSilent() = runTest {
        val world = World()

        assertTrue(world.store().flush().success)

        assertEquals(emptyList<Recorded>(), world.requests)
    }

    @Test
    @DisplayName("a full flush drains sessions, then events, then samples")
    fun fullFlushCoversEveryFlow() = runTest {
        val world = World()
        world.seedSessions(1)
        world.seedEvents(2)
        world.seedSamples(4)

        assertTrue(world.store().flush().success)

        assertEquals(
            listOf(HrApi.SESSIONS_PATH, HrApi.SET_EVENTS_PATH, HrApi.SAMPLES_PATH),
            world.requests.map { it.path },
        )
        assertTrue(world.sessionDao.sessions.values.all { it.isSynced })
        assertTrue(world.eventDao.events.values.all { it.isSynced })
        assertTrue(world.sampleDao.samples.values.all { it.isSynced })
    }

    @Test
    @DisplayName("a synced sample records when it left, from the injected clock")
    fun syncedAtIsStamped() = runTest {
        val world = World()
        world.seedSamples(1)

        world.store().flushSamples()

        assertEquals(NOW, world.sampleDao.samples.values.single().syncedAt)
    }

    @Test
    @DisplayName("the batch is capped at 1000 rows per request")
    fun batchCapIsRespected() = runTest {
        val world = World()
        world.seedSamples(1500)

        assertTrue(world.store().flushSamples().success)

        assertEquals(listOf(1000, 500), world.requests.map { it.rows })
        assertTrue(world.sampleDao.samples.values.all { it.isSynced })
    }

    // ---- the 422 bisect --------------------------------------------------

    @Test
    @DisplayName("one poison row in a thousand is isolated and quarantined alone")
    fun bisectIsolatesOnePoisonRow() = runTest {
        val world = World()
        world.seedEvents(1000)
        world.poison = setOf(eventId(499))

        val result = world.store().flushEvents()

        assertTrue(result.success)
        assertEquals(
            listOf(eventId(499)),
            world.eventDao.events.values.filter { it.isQuarantined }.map { it.eventId },
        )
        assertEquals(999, world.eventDao.events.values.count { it.isSynced })
        assertFalse(world.eventDao.events.getValue(eventId(499)).isSynced)
        // Halving, not scanning: a linear search for the bad row would be a
        // thousand requests, and this is the whole reason the batch is split.
        assertTrue(world.requests.size < 40, "expected a logarithmic search, took ${world.requests.size}")
    }

    @Test
    @DisplayName("a quarantined row is never offered again")
    fun quarantinedRowsStayOut() = runTest {
        val world = World()
        world.seedEvents(4)
        world.poison = setOf(eventId(2))

        world.store().flushEvents()
        world.requests.clear()
        val second = world.store().flushEvents()

        assertTrue(second.success)
        assertEquals(emptyList<Recorded>(), world.requests, "the poison row must not be re-sent")
    }

    @Test
    @DisplayName("two poison rows in the same batch are both isolated, and only they")
    fun bisectIsolatesEveryPoisonRow() = runTest {
        val world = World()
        world.seedEvents(16)
        world.poison = setOf(eventId(3), eventId(11))

        assertTrue(world.store().flushEvents().success)

        assertEquals(
            listOf(eventId(3), eventId(11)),
            world.eventDao.events.values.filter { it.isQuarantined }.map { it.eventId },
        )
        assertEquals(14, world.eventDao.events.values.count { it.isSynced })
    }

    @Test
    @DisplayName("the bisect works the same on the samples flow, composite key and all")
    fun bisectWorksOnSamples() = runTest {
        val world = World()
        world.seedSamples(32)
        world.poison = setOf("\"timestampMs\":${sampleTimestamp(20)}")

        assertTrue(world.store().flushSamples().success)

        val quarantined = world.sampleDao.samples.values.filter { it.isQuarantined }
        assertEquals(listOf(sampleTimestamp(20)), quarantined.map { it.timestampMs })
        assertEquals(31, world.sampleDao.samples.values.count { it.isSynced })
    }

    // ---- the circuit breakers --------------------------------------------

    @Test
    @DisplayName("three quarantines with nothing yet accepted stops the run")
    fun breakerBeforeFirstSuccess() = runTest {
        val world = World()
        world.seedEvents(16)
        // Every row is poison, so no request in the run can succeed. Without
        // this breaker the bisect would happily quarantine all sixteen — and
        // on a real backlog, all thousand.
        world.poison = (0 until 16).map { eventId(it) }.toSet()

        val result = world.store().flushEvents()

        assertFalse(result.success)
        assertEquals(3, world.eventDao.events.values.count { it.isQuarantined })
        assertEquals(0, world.eventDao.events.values.count { it.isSynced })
        // Classified like a systemic 4xx, not as a retryable error: with
        // nothing accepted, "three bad rows" and "this server refuses
        // everything" are indistinguishable from here, and a background retry
        // of the second case eats the backlog three good rows at a time.
        assertEquals(SyncSkipReason.OTHER, result.reason)
        assertNull(result.error)
        assertFalse(
            SyncFlushWorker.needsRetry(listOf(result), sessionOpen = true),
            "the Worker must not wake for this",
        )
    }

    @Test
    @DisplayName("ten quarantines stops the run even once rows have been accepted")
    fun breakerPerRun() = runTest {
        val world = World()
        world.seedEvents(64)
        // The clean first half is accepted before the poison is reached, so the
        // three-strikes breaker is satisfied and this is the ten-per-run one.
        world.poison = (32..42).map { eventId(it) }.toSet()

        val result = world.store().flushEvents()

        assertFalse(result.success)
        // The server demonstrably takes good rows, so these quarantines were
        // earned and the failure is an ordinary retryable one.
        assertTrue(result.error is QuarantineLimitException, "was ${result.error}")
        assertNull(result.reason)
        assertTrue(SyncFlushWorker.needsRetry(listOf(result), sessionOpen = true))
        assertEquals(10, world.eventDao.events.values.count { it.isQuarantined })
        assertTrue(world.eventDao.events.values.count { it.isSynced } >= 32)
    }

    @Test
    @DisplayName("the three-strikes limit hands over to the ten-per-run one at the first 2xx")
    fun breakerHandsOverAtFirstSuccess() = runTest {
        val world = World()
        world.seedEvents(32)
        // Two poison rows are isolated before anything has been accepted, then
        // their clean siblings land, and six more are isolated afterwards.
        // Seven quarantines in one run is only reachable because that 2xx
        // arrived: under the pre-success limit alone this run stops at three.
        world.poison = (listOf(0, 1) + (16..20)).map { eventId(it) }.toSet()

        val result = world.store().flushEvents()

        assertTrue(result.success, "the run must survive its own early quarantines")
        assertEquals(7, world.eventDao.events.values.count { it.isQuarantined })
        assertEquals(25, world.eventDao.events.values.count { it.isSynced })
    }

    @Test
    @DisplayName("the quarantine budget is one per run, shared across the flows")
    fun breakerBudgetIsSharedAcrossFlows() = runTest {
        val world = World()
        world.seedSessions(32)
        world.seedEvents(4)
        // Eight quarantines in the sessions flow, then three poison events —
        // nowhere near a limit on their own, but the run's budget is already
        // spent, so the third one trips it.
        world.poison = (listOf(0, 1) + (16..21)).map { sessionId(it) }.toSet() +
            (0..2).map { eventId(it) }.toSet()

        val result = world.store().flush()

        assertFalse(result.success)
        assertTrue(result.error is QuarantineLimitException, "was ${result.error}")
        assertEquals(8, world.sessionDao.countQuarantined())
        assertEquals(2, world.eventDao.countQuarantined(), "the tenth is the last one recorded")
        // Samples never ran: the run stopped in the flow before them.
        assertTrue(world.requests.none { it.path == HrApi.SAMPLES_PATH })
    }

    @Test
    @DisplayName("a tripped breaker leaves the run's remaining rows pending, not lost")
    fun breakerKeepsTheRest() = runTest {
        val world = World()
        world.seedEvents(16)
        world.poison = (0 until 16).map { eventId(it) }.toSet()

        world.store().flushEvents()

        assertEquals(13, world.eventDao.countPending())
    }

    // ---- statuses that are not 422 ---------------------------------------

    @Test
    @DisplayName("a 400 aborts the run as systemic — no bisect, nothing quarantined")
    fun otherClientErrorsAreSystemic() = runTest {
        val world = World()
        world.seedEvents(8)
        world.responder = { _, _ -> respondJson(ERROR_BODY, HttpStatusCode.BadRequest) }

        val result = world.store().flushEvents()

        assertFalse(result.success)
        assertEquals(SyncSkipReason.OTHER, result.reason)
        // No error object, deliberately: this is the one failure the
        // background Worker must not wake up and retry. See SyncFlushWorker.
        assertNull(result.error)
        assertEquals(1, world.requests.size, "a systemic refusal must not be bisected")
        assertEquals(0, world.eventDao.events.values.count { it.isQuarantined })
        assertEquals(8, world.eventDao.countPending())
    }

    @Test
    @DisplayName("a 404 is systemic too — only 422 means 'some row of yours is bad'")
    fun notFoundIsSystemic() = runTest {
        val world = World()
        world.seedEvents(8)
        world.responder = { _, _ -> respondJson(ERROR_BODY, HttpStatusCode.NotFound) }

        val result = world.store().flushEvents()

        assertEquals(SyncSkipReason.OTHER, result.reason)
        assertEquals(1, world.requests.size)
    }

    @Test
    @DisplayName("a 500 propagates as an error so the backoff policy sees it")
    fun serverErrorsPropagate() = runTest {
        val world = World()
        world.seedEvents(8)
        world.responder = { _, _ -> respondJson(ERROR_BODY, HttpStatusCode.InternalServerError) }

        val result = world.store().flushEvents()

        assertFalse(result.success)
        assertTrue(result.error is ServerResponseException, "was ${result.error}")
        assertEquals(1, world.requests.size, "a 5xx must not be bisected either")
        assertEquals(8, world.eventDao.countPending(), "the batch is kept for the retry")
    }

    @Test
    @DisplayName("a dropped connection propagates the same way")
    fun networkErrorsPropagate() = runTest {
        val world = World()
        world.seedEvents(8)
        world.responder = { _, _ -> throw IOException("fixture-connection-dropped") }

        val result = world.store().flushEvents()

        assertTrue(result.error is IOException, "was ${result.error}")
        assertEquals(8, world.eventDao.countPending())
    }

    @Test
    @DisplayName("a 5xx four levels into a bisect is still a 5xx, not a quarantine")
    fun serverErrorDuringBisect() = runTest {
        val world = World()
        world.seedEvents(8)
        var seen = 0
        world.responder = { _, _ ->
            seen++
            // The first request 422s, which splits the batch; the halves then
            // meet a server that has fallen over. Nothing here is the rows'
            // fault and nothing may be thrown away for it.
            if (seen == 1) {
                respondJson(ERROR_BODY, HttpStatusCode.UnprocessableEntity)
            } else {
                respondJson(ERROR_BODY, HttpStatusCode.InternalServerError)
            }
        }

        val result = world.store().flushEvents()

        assertTrue(result.error is ServerResponseException)
        assertEquals(0, world.eventDao.events.values.count { it.isQuarantined })
        assertEquals(8, world.eventDao.countPending())
    }

    // ---- sessions --------------------------------------------------------

    @Test
    @DisplayName("a session upserts on needsUpload and is marked synced by the 2xx")
    fun sessionsUpsertAndMark() = runTest {
        val world = World()
        world.seedSessions(2)

        assertTrue(world.store().flushSessions().success)

        assertEquals(HrApi.SESSIONS_PATH, world.requests.single().path)
        assertEquals(2, world.requests.single().rows)
        assertEquals(0, world.sessionDao.countPending())
    }

    @Test
    @DisplayName("a session row that changes re-uploads; an identical rewrite does not")
    fun sessionRewriteRules() = runTest {
        val world = World()
        world.seedSessions(1)
        val store = world.store()
        store.flushSessions()

        val open = world.sessionDao.sessions.values.single()
        world.sessionDao.upsert(open.copy(isSynced = false))
        world.requests.clear()
        store.flushSessions()
        assertEquals(emptyList<Recorded>(), world.requests, "an unchanged row must not re-upload")

        world.sessionDao.upsert(open.copy(endedAtMs = NOW))
        store.flushSessions()
        assertEquals(1, world.requests.size, "closing the session must re-upsert it")
    }

    @Test
    @DisplayName("a poison session is quarantined and the rest of the batch still lands")
    fun poisonSessionIsQuarantined() = runTest {
        val world = World()
        world.seedSessions(4)
        world.poison = setOf(sessionId(2))

        val result = world.store().flushSessions()

        assertTrue(result.success)
        assertEquals(1, world.sessionDao.countQuarantined())
        assertTrue(world.sessionDao.sessions.getValue(sessionId(2)).isQuarantined)
        assertEquals(3, world.sessionDao.sessions.values.count { it.isSynced })
        assertEquals(0, world.sessionDao.countPending())
    }

    @Test
    @DisplayName("a quarantined session is not offered to the next run")
    fun quarantinedSessionStaysOut() = runTest {
        val world = World()
        world.seedSessions(2)
        world.poison = setOf(sessionId(1))

        world.store().flushSessions()
        world.requests.clear()
        val second = world.store().flushSessions()

        assertTrue(second.success)
        assertEquals(emptyList<Recorded>(), world.requests, "the poison row must not be re-sent")
    }

    @Test
    @DisplayName("a quarantined session that is then corrected gets another attempt")
    fun quarantinedSessionSelfHeals() = runTest {
        val world = World()
        world.seedSessions(1)
        world.poison = setOf(sessionId(0))
        val store = world.store()
        store.flushSessions()
        assertEquals(1, world.sessionDao.countQuarantined())

        // The capture ends, which rewrites the row. This is the difference
        // from samples and events: the row the server refused is not the row
        // that replaced it, so the upsert clears the flag and the corrected
        // session goes back on the wire without this store remembering it.
        world.poison = emptySet()
        world.sessionDao.upsert(world.sessionDao.sessions.getValue(sessionId(0)).copy(endedAtMs = NOW))
        world.requests.clear()

        assertTrue(store.flushSessions().success)

        assertEquals(1, world.requests.size)
        assertEquals(0, world.sessionDao.countQuarantined())
        assertTrue(world.sessionDao.sessions.getValue(sessionId(0)).isSynced)
    }

    @Test
    @DisplayName("a session rewritten while its batch is in flight is re-offered at the new generation")
    fun sessionRewrittenMidFlightIsReUploaded() = runTest {
        val world = World()
        world.seedSessions(1)
        var inFlight = true
        world.onRequest = {
            if (inFlight) {
                inFlight = false
                // The capture ends while its row is on the wire. The verdict
                // coming back was computed against the open session, and the
                // generation guard is what stops it landing on this one.
                world.sessionDao.upsert(
                    world.sessionDao.sessions.getValue(sessionId(0)).copy(endedAtMs = NOW),
                )
            }
        }

        val result = world.store().flushSessions()

        assertTrue(result.success)
        // Two requests: the stale mark missed, so the same drain loop found the
        // row still pending and sent the version that replaced it.
        assertEquals(2, world.requests.size)
        assertTrue(
            world.requests.last().body.contains("endedAtMs"),
            "the closed session is what must reach the server",
        )
        val stored = world.sessionDao.sessions.getValue(sessionId(0))
        assertTrue(stored.isSynced, "the final state has to end up marked")
        assertEquals(1L, stored.dirtyGeneration)
        assertEquals(0, world.sessionDao.countPending())
    }

    @Test
    @DisplayName("a mark that stops removing rows aborts the drain instead of re-sending forever")
    fun brokenMarkAbortsTheDrain() = runTest {
        val world = World(
            sessionDao = object : FakeHrSessionDao() {
                // Same id, same generation, still pending: the failure mode the
                // head-of-batch check exists for, and the one it must tell
                // apart from a legitimate mid-flight rewrite.
                override suspend fun markSynced(sessionId: String, generation: Long) = Unit
            },
        )
        world.seedSessions(1)

        val result = world.store().flushSessions()

        assertFalse(result.success)
        assertTrue(
            result.error?.message?.contains("made no progress") == true,
            "was ${result.error}",
        )
        assertEquals(1, world.requests.size, "it must abort before sending the same batch again")
    }

    @Test
    @DisplayName("quarantining a session does not make the drain loop re-read it forever")
    fun quarantinedSessionTerminatesTheDrain() = runTest {
        val world = World()
        world.seedSessions(1)
        world.poison = setOf(sessionId(0))

        // The drain re-reads until nothing is pending, so the flag has to take
        // the row out of `needsUpload` — otherwise this call never returns.
        assertTrue(world.store().flushSessions().success)

        assertEquals(1, world.requests.size)
    }

    // ---- reconciliation --------------------------------------------------

    @Test
    @DisplayName("counts that do not reconcile are logged and change nothing")
    fun countMismatchIsDiagnosticOnly() = runTest {
        val world = World()
        world.seedEvents(3)
        world.responder = { _, _ ->
            respondJson("""{"accepted":1,"duplicates":0,"totalReceived":2}""")
        }

        val result = world.store().flushEvents()

        assertTrue(result.success, "the 2xx is what marks rows, not the counts")
        assertTrue(world.eventDao.events.values.all { it.isSynced })
        verify(exactly = 1) {
            world.debugLog.log(any(), match { it.contains("do not reconcile") }, any())
        }
    }

    @Test
    @DisplayName("an upsert reporting fewer rows than sent is normal and logs nothing")
    fun upsertMayCollapseRows() = runTest {
        val world = World()
        world.seedSessions(2)
        // Distinct sessions written, rows received: the two differ whenever a
        // batch repeats a sessionId, so this is not a mismatch.
        world.responder = { _, _ -> respondJson("""{"upserted":1,"totalReceived":2}""") }

        assertTrue(world.store().flushSessions().success)

        verify(exactly = 0) {
            world.debugLog.log(any(), match { it.contains("do not reconcile") }, any())
        }
    }

    // ---- retention -------------------------------------------------------

    @Test
    @DisplayName("synced samples are pruned after seven days, exclusive on the boundary")
    fun samplePruneHorizon() = runTest {
        val world = World()
        val cutoff = NOW - SAMPLE_RETENTION_MS
        world.seedSample(timestampMs = cutoff - 1, synced = true)
        world.seedSample(timestampMs = cutoff, synced = true)
        world.seedSample(timestampMs = NOW, synced = true)

        assertTrue(world.store().flushSamples().success)

        assertEquals(
            listOf(cutoff, NOW),
            world.sampleDao.samples.values.map { it.timestampMs },
            "the row stamped exactly at the cutoff survives",
        )
    }

    @Test
    @DisplayName("synced events keep sixty days, not the samples' seven")
    fun eventPruneHorizon() = runTest {
        val world = World()
        val cutoff = NOW - EVENT_RETENTION_MS
        world.seedEvent(index = 0, clientTimestampMs = cutoff - 1, synced = true)
        world.seedEvent(index = 1, clientTimestampMs = cutoff, synced = true)
        // Three weeks old: gone under the samples' horizon, kept under this one.
        world.seedEvent(index = 2, clientTimestampMs = NOW - 21L * 24 * 60 * 60 * 1000, synced = true)

        assertTrue(world.store().flushEvents().success)

        assertEquals(listOf(eventId(1), eventId(2)), world.eventDao.events.keys.toList())
    }

    @Test
    @DisplayName("an unsynced row is never pruned, however old")
    fun unsyncedRowsSurvivePruning() = runTest {
        val world = World()
        // Quarantined, so the flush cannot upload it and it stays unsynced
        // across the prune that follows.
        world.seedSample(timestampMs = NOW - SAMPLE_RETENTION_MS * 2, synced = false, quarantined = true)

        assertTrue(world.store().flushSamples().success)

        assertEquals(1, world.sampleDao.samples.size)
    }

    @Test
    @DisplayName("the horizon is measured on the data's own timestamp, not on when it was sent")
    fun pruneFollowsTheDataTimestamp() = runTest {
        val world = World()
        // A backlog older than the horizon uploads and is then pruned in the
        // same run. That is the intent: the server has it, and retention here
        // is about how much history this device carries.
        world.seedSample(timestampMs = NOW - SAMPLE_RETENTION_MS - 1, synced = false)

        assertTrue(world.store().flushSamples().success)

        assertEquals(1, world.requests.size)
        assertTrue(world.sampleDao.samples.isEmpty())
    }

    @Test
    @DisplayName("sessions are never pruned — they are what makes the samples legible")
    fun sessionsAreNeverPruned() = runTest {
        val world = World()
        world.sessionDao.sessions[sessionId(0)] = HrSessionEntity(
            sessionId = sessionId(0),
            deviceId = DEVICE,
            startedAtMs = NOW - 400L * 24 * 60 * 60 * 1000,
            endedAtMs = NOW,
            isSynced = true,
        )

        assertTrue(world.store().flush().success)

        assertEquals(1, world.sessionDao.sessions.size)
    }

    @Test
    @DisplayName("a failed run does not prune")
    fun failedRunSkipsPruning() = runTest {
        val world = World()
        world.seedSample(timestampMs = NOW - SAMPLE_RETENTION_MS - 1, synced = true)
        world.seedSample(timestampMs = NOW, synced = false)
        world.responder = { _, _ -> respondJson(ERROR_BODY, HttpStatusCode.InternalServerError) }

        assertFalse(world.store().flushSamples().success)

        assertEquals(2, world.sampleDao.samples.size)
    }

    // ---- gating ----------------------------------------------------------

    @Test
    @DisplayName("offline is a skip, not a failure, and sends nothing")
    fun offlineSkips() = runTest {
        val world = World()
        world.online = false
        world.seedEvents(3)

        val result = world.store().flush()

        assertFalse(result.success)
        assertEquals(SyncSkipReason.OFFLINE, result.reason)
        assertEquals(emptyList<Recorded>(), world.requests)
    }

    @Test
    @DisplayName("a second flush while one is in flight skips instead of interleaving")
    fun singleFlight() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val world = World(
            eventDao = object : FakeSetEventDao() {
                override suspend fun pendingUpload(limit: Int): List<SetEventEntity> {
                    started.complete(Unit)
                    release.await()
                    return super.pendingUpload(limit)
                }
            },
        )
        world.seedEvents(1)
        val store = world.store()

        val first = launch { store.flushEvents() }
        started.await()
        val second = store.flushEvents()
        release.complete(Unit)
        first.join()

        assertEquals(SyncSkipReason.ALREADY_SYNCING, second.reason)
        assertEquals(1, world.requests.size, "the two runs must not both upload")
    }

    @Test
    @DisplayName("the mark that follows a response is refused once the server session closes")
    fun marksAreUnderTheWriteLease() = runTest {
        val world = World()
        world.seedEvents(3)
        // A server switch confirmed while this batch was on the wire. The gate
        // covers the write, so the mark is refused rather than landing in a
        // database that now belongs to a different server.
        world.onRequest = { world.session.close() }

        val result = world.store().flushEvents()

        assertTrue(result.error is ServerSessionClosedException, "was ${result.error}")
        assertEquals(0, world.eventDao.events.values.count { it.isSynced })
    }

    @Test
    @DisplayName("pending data is anything unsent and not quarantined")
    fun pendingProbe() = runTest {
        val world = World()
        assertFalse(world.store().hasPendingData())

        world.seedEvents(1)
        assertTrue(world.store().hasPendingData())

        world.eventDao.markSynced(listOf(eventId(0)))
        assertFalse(world.store().hasPendingData())

        world.seedSamples(1)
        assertTrue(world.store().hasPendingData(), "a pending sample counts on its own")

        world.sampleDao.markQuarantined(world.sampleDao.samples.keys.toList())
        assertFalse(world.store().hasPendingData(), "a quarantined row is not waiting for anything")

        world.seedSessions(1)
        assertTrue(world.store().hasPendingData(), "so does a session that has not been upserted")

        // The probe and the batch query must agree on every table, or the
        // scheduler wakes for work no flow will ever offer it.
        world.sessionDao.markQuarantined(sessionId(0), world.sessionGeneration(0))
        assertFalse(world.store().hasPendingData(), "a quarantined-only backlog is not pending work")
    }

    @Test
    @DisplayName("a backlog of nothing but quarantined rows reports no pending work and sends nothing")
    fun quarantinedBacklogIsNotWork() = runTest {
        val world = World()
        world.seedSessions(1)
        world.seedEvents(1)
        world.seedSample(timestampMs = NOW)
        world.sessionDao.markQuarantined(sessionId(0), world.sessionGeneration(0))
        world.eventDao.markQuarantined(listOf(eventId(0)))
        world.sampleDao.markQuarantined(world.sampleDao.samples.keys.toList())

        assertFalse(world.store().hasPendingData())
        assertTrue(world.store().flush().success)

        assertEquals(emptyList<Recorded>(), world.requests)
    }

    // ---- the debounce ----------------------------------------------------

    @Test
    @DisplayName("a burst of set-event inserts coalesces into one flush")
    fun debounceCoalesces() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val world = World()
        world.seedEvents(3)
        val store = world.store()
        var flushes = 0
        // The real scheduler, wired as Koin wires it: the store's hook arms the
        // debounce and the debounce calls back into the store.
        val scheduler = SyncScheduler(
            scope = this,
            name = "hr",
            syncFn = {
                flushes++
                store.flush()
            },
            isSyncing = { store.isFlushing },
            hasDirtyData = store::hasPendingData,
            isOnline = { true },
            isNetworkError = { false },
        )
        world.scheduleUpload = scheduler::scheduleUpload

        store.scheduleFlush()
        advanceTimeBy(500)
        store.scheduleFlush()
        advanceTimeBy(500)
        store.scheduleFlush()

        advanceTimeBy(2_499)
        assertEquals(0, flushes, "the debounce has not elapsed yet")
        advanceTimeBy(2)
        assertEquals(1, flushes, "three inserts, one flush")
        // The flush itself crosses a real dispatcher on its way to the mock
        // server, so the upload is counted only once it has finished.
        scheduler.stop()
        store.awaitQuiescence()
        assertEquals(1, world.requests.size, "three inserts, one upload")
    }

    // ---- harness ---------------------------------------------------------

    /** One request the client made, reduced to what the assertions care about. */
    private data class Recorded(val path: String, val rows: Int, val body: String)

    /**
     * The three DAOs, a scriptable server and the fence, wired as Koin wires
     * them.
     *
     * The default responder is poison-aware: a batch containing any string in
     * [poison] comes back 422, which is exactly how the real server behaves
     * (validation is whole-request, so one bad row rejects everything sent with
     * it) and is what makes the bisect tests readable.
     */
    private inner class World(
        val sessionDao: FakeHrSessionDao = FakeHrSessionDao(),
        val sampleDao: FakeHrSampleDao = FakeHrSampleDao(),
        val eventDao: FakeSetEventDao = FakeSetEventDao(),
    ) {
        val session = ServerSessionGate()
        val requests = mutableListOf<Recorded>()

        var online = true
        var poison: Set<String> = emptySet()
        var onRequest: suspend () -> Unit = {}
        var scheduleUpload: () -> Unit = {}

        /** Replaces the default responder entirely. */
        var responder: (suspend MockRequestHandleScope.(String, Int) -> HttpResponseData)? = null

        val debugLog = mockk<DebugLog>(relaxed = true).also {
            every { it.log(any(), any(), any()) } returns Unit
        }

        private val storeInstance: HrSyncStore by lazy {
            val config = ServerConfig("http://localhost:9001/wellness")
            val engine = MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                val path = request.url.encodedPath.removePrefix("/wellness")
                val rows = rowCount(body)
                requests += Recorded(path, rows, body)
                onRequest()
                val custom = responder
                when {
                    custom != null -> custom(path, rows)
                    poison.any { body.contains(it) } -> respondJson(ERROR_BODY, HttpStatusCode.UnprocessableEntity)
                    path == HrApi.SESSIONS_PATH -> respondJson("""{"upserted":$rows,"totalReceived":$rows}""")
                    else -> respondJson("""{"accepted":$rows,"duplicates":0,"totalReceived":$rows}""")
                }
            }
            HrSyncStore(
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                eventDao = eventDao,
                api = HrApi(buildHttpClient(engine, config, WellnessJson, debugLog), config),
                clientId = { CLIENT },
                isOnline = { online },
                session = session,
                debugLog = debugLog,
                scheduleUpload = { scheduleUpload() },
                now = { NOW },
            )
        }

        fun store(): HrSyncStore = storeInstance

        fun seedEvents(count: Int) {
            repeat(count) { seedEvent(it) }
        }

        /**
         * Timestamps ascend with the index, because `pendingUpload` orders by
         * them: a batch's position in the bisect is what several of these tests
         * are about, and seeding them backwards silently reverses it.
         */
        fun seedEvent(index: Int, clientTimestampMs: Long = NOW - 100_000 + index, synced: Boolean = false) {
            val id = eventId(index)
            eventDao.events[id] = SetEventEntity(
                eventId = id,
                date = "2030-01-03",
                exerciseKey = "fixture-adhoc-lift",
                setNum = 1,
                action = SetEventEntity.ACTION_CHECK,
                clientTimestampMs = clientTimestampMs,
                isSynced = synced,
            )
        }

        fun seedSamples(count: Int) {
            repeat(count) { seedSample(timestampMs = sampleTimestamp(it)) }
        }

        fun seedSample(timestampMs: Long, synced: Boolean = false, quarantined: Boolean = false) {
            val row = HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = timestampMs,
                seq = 0,
                heartRateBpm = 140,
                rrIntervalMs = 430,
                sessionId = sessionId(0),
                isSynced = synced,
                isQuarantined = quarantined,
            )
            sampleDao.samples[row.key()] = row
        }

        /** A stored session's current generation — what a mark has to match. */
        fun sessionGeneration(index: Int): Long =
            sessionDao.sessions.getValue(sessionId(index)).dirtyGeneration

        fun seedSessions(count: Int) {
            repeat(count) { index ->
                val id = sessionId(index)
                sessionDao.sessions[id] = HrSessionEntity(
                    sessionId = id,
                    deviceId = DEVICE,
                    // Ascending, as `needsUpload` orders them. See [seedEvent].
                    startedAtMs = NOW - 100_000 + index,
                )
            }
        }

        /** However many rows the envelope carries, whichever array holds them. */
        private fun rowCount(body: String): Int = WellnessJson.parseToJsonElement(body)
            .jsonObject.values.filterIsInstance<JsonArray>().single().size
    }

    private companion object {
        const val CLIENT = "fixture-client-hr-0001"
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        const val NOW = 1_800_000_000_000L
        const val ERROR_BODY = """{"detail":"fixture-validation-error"}"""

        fun eventId(index: Int): String = "event-%04d".format(index)

        fun sessionId(index: Int): String = "session-%04d".format(index)

        /** Spaced by a second so no two share a millisecond. */
        fun sampleTimestamp(index: Int): Long = NOW - 2_000_000L + index * 1_000L
    }
}
