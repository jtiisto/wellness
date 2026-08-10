package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachDao
import dev.jtiisto.wellness.core.data.db.CoachDateGeneration
import dev.jtiisto.wellness.core.data.db.CoachDirtyClear
import dev.jtiisto.wellness.core.data.db.CoachLogEntity
import dev.jtiisto.wellness.core.data.db.CoachMetaEntity
import dev.jtiisto.wellness.core.data.db.CoachPlanEntity
import dev.jtiisto.wellness.core.data.db.PendingUpload
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ForceSyncCounts
import dev.jtiisto.wellness.core.data.sync.ForceSyncModuleResult
import dev.jtiisto.wellness.core.data.sync.ForceSyncSkipReason
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncSkipReason
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * The full [CoachSyncStore] cycle against a fake DAO and a mock server.
 *
 * What these guard is ordering and atomicity: upload before pull, the payload
 * and its generations read together, one mutating write per response, and the
 * watermark coming from the pull alone. Each is a place where a plausible
 * rearrangement silently loses a user's edit or skips a remote one.
 */
class CoachSyncStoreTest {

    private val json = WellnessJson
    private val today = "2026-08-06"

    // ---- upload-first ordering -------------------------------------------

    @Test
    @DisplayName("the upload goes out before the pull")
    fun uploadHappensBeforeDownload() = runTest {
        val world = World()
        world.seedLog(today, """{"_lastModified":"day-tok","ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(listOf("POST", "GET"), world.httpCalls())
    }

    @Test
    @DisplayName("nothing dirty means no POST at all, only the pull")
    fun cleanStoreOnlyPulls() = runTest {
        val world = World()

        world.store().triggerSync()

        assertEquals(listOf("GET"), world.httpCalls())
    }

    @Test
    @DisplayName("an unsatisfiable dirty date is cleared BEFORE the POST, and an empty payload skips it entirely")
    fun unsatisfiableClearedThenPostSkipped() = runTest {
        val world = World()
        // Empty and never synced: nothing to send, nothing server-side to
        // delete. Leaving it dirty would wedge the client red forever.
        world.seedLog(today, """{"session_feedback":{}}""", dirty = true, generation = 1)

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(listOf("GET"), world.httpCalls(), "an empty payload must not be POSTed")
        assertFalse(world.dao.logs.getValue(today).isDirty, "the unsatisfiable date stayed dirty")
        val clearIndex = world.dao.calls.indexOf("clearDirty")
        val getIndex = world.dao.calls.indexOf("GET")
        assertTrue(clearIndex in 0 until getIndex, "the clear must precede the network: ${world.dao.calls}")
        assertEquals(SyncStatus.GREEN, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("with one satisfiable and one unsatisfiable date, only the satisfiable one is sent")
    fun onlySatisfiableDatesAreSent() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.seedLog("2026-08-05", """{"session_feedback":{}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")

        world.store().triggerSync()

        val body = world.uploadBody()
        assertTrue(body.contains(today), "the satisfiable date was not sent: $body")
        assertFalse(body.contains("2026-08-05"), "an unsatisfiable date reached the wire: $body")
        assertEquals(0, world.dao.dirtyCount(), "both dates should have settled")
    }

    // ---- adoption --------------------------------------------------------

    @Test
    @DisplayName("an untouched date adopts the merged server day wholesale and stops being dirty")
    fun wholesaleAdoption() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(
            results = """"$today":{"session_feedback":{},"ex_1":{"sets":[{"reps":5}],"_lastModified":"srv-ex"},""" +
                """"_lastModified":"srv-day"}""",
        )

        world.store().triggerSync()

        val stored = world.storedLog(today)
        assertEquals(JsonPrimitive("srv-day"), stored["_lastModified"])
        assertEquals(JsonPrimitive("srv-ex"), stored.getValue("ex_1").jsonObject["_lastModified"])
        assertFalse(world.dao.logs.getValue(today).isDirty)
    }

    @Test
    @DisplayName("an edit landing between the payload build and the adoption keeps its content and advances tokens")
    fun editBetweenBuildAndAdoptIsPreserved() = runTest {
        val world = World()
        world.seedLog(
            today,
            """{"_lastModified":"old-day","ex_1":{"reps":32,"_lastModified":"old-ex"}}""",
            dirty = true,
            generation = 1,
        )
        world.postResponse = post(
            results = """"$today":{"_lastModified":"srv-day","ex_1":{"reps":28,"_lastModified":"srv-ex"}}""",
        )
        // The user re-edits while the request is in flight.
        world.onUpload = { world.store().updateLog(today, "ex_1", buildJsonObject { put("reps", 99) }) }

        world.store().triggerSync()

        val stored = world.storedLog(today)
        assertEquals(JsonPrimitive(99), stored.getValue("ex_1").jsonObject["reps"], "the re-edit was clobbered")
        // The token still advances, or the retry would arbitrate against a base
        // the server has already moved past and come back rejected.
        assertEquals(JsonPrimitive("srv-ex"), stored.getValue("ex_1").jsonObject["_lastModified"])
        assertEquals(JsonPrimitive("srv-day"), stored["_lastModified"])
        assertTrue(world.dao.logs.getValue(today).isDirty, "the re-edit must stay dirty for the next cycle")
    }

    @Test
    @DisplayName("an edit made before the payload is built is part of that payload, and settles with it")
    fun editBeforeTheBuildIsIncludedInThePayload() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")
        world.dao.beforeBuildPendingUpload = {
            world.store().updateLog(today, "ex_1", buildJsonObject { put("reps", 77) })
        }

        world.store().triggerSync()

        assertTrue(world.uploadBody().contains("\"reps\":77"), "the payload lagged the row: ${world.uploadBody()}")
        // The edit is in the payload AND its generation is the one the clear is
        // guarded by, so the date settles rather than re-uploading forever.
        assertFalse(world.dao.logs.getValue(today).isDirty)
        // Note this does NOT prove the JSON and the generation were read in one
        // transaction — the fake DAO has no transaction boundary to violate.
        // That pairing is pinned on the real schema by CoachDaoTest's
        // buildPendingUpload cases.
    }

    @Test
    @DisplayName("the window prune drops a still-dirty day that the window has closed on, unsent edit and all")
    fun windowPruneDropsAStillDirtyOldDay() = runTest {
        val world = World()
        val stale = "2026-06-01"
        world.seedLog(stale, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = "")
        // Re-modified while the upload was in flight, so it is still dirty when
        // the pull applies — and the pull's window has moved past its date.
        world.onUpload = { world.store().updateLog(stale, "ex_1", buildJsonObject { put("reps", 6) }) }
        world.getResponse = get(earliestDate = "2026-06-08")

        world.store().triggerSync()

        // Accepted, deliberate window-boundary behavior, matching the PWA — it
        // reaches the same end state one cycle later by clearing the date as
        // unsatisfiable. A day the server's 60-day window has closed on can no
        // longer be reconciled, so keeping the row would only wedge the client
        // red re-uploading it.
        assertFalse(stale in world.dao.logs, "the closed-window day should have been pruned")
        assertEquals(0, world.dao.dirtyCount(), "and its dirty marker must go with it, not linger")
        assertEquals(SyncStatus.GREEN, world.store().syncStatus.value)
    }

    // ---- tombstone verdicts (F4) at the store level ----------------------

    @Test
    @DisplayName("a REJECTED delete adopts the surviving server record instead of advancing the tombstone")
    fun rejectedDeleteAdoptsSurvivingRecord() = runTest {
        val world = World()
        world.seedTombstoneDay()
        // Re-modified mid-sync, so adoption goes through the token-advance path
        // where the verdict rule lives.
        world.onUpload = { world.store().updateSessionFeedback(today, feedback("general_notes", "mid-sync edit")) }
        world.postResponse = post(
            results = """"$today":{"session_feedback":{},"_lastModified":"srv-day",""" +
                """"extra_zone2":{"duration_min":60,"_lastModified":"t2"}}""",
        )

        world.store().triggerSync()

        val stored = world.storedLog(today)
        // Merely advancing the tombstone's token would make the next retry's
        // base match, turning a rejected delete into a winning one.
        assertEquals(
            world.obj("""{"duration_min":60,"_lastModified":"t2"}"""),
            stored["extra_zone2"],
        )
        assertEquals(
            JsonPrimitive("mid-sync edit"),
            stored.getValue("session_feedback").jsonObject["general_notes"],
        )
    }

    @Test
    @DisplayName("an ACCEPTED delete drops the tombstone key")
    fun acceptedDeleteDropsTheKey() = runTest {
        val world = World()
        world.seedTombstoneDay()
        world.onUpload = { world.store().updateSessionFeedback(today, feedback("general_notes", "mid-sync edit")) }
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")

        world.store().triggerSync()

        assertFalse("extra_zone2" in world.storedLog(today))
    }

    @Test
    @DisplayName("a tombstone created mid-sync is kept verbatim — it has not been arbitrated yet")
    fun midSyncTombstoneSurvivesUntouched() = runTest {
        val world = World()
        world.seedLog(
            today,
            """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"duration_min":45,"_lastModified":"t1"}}""",
            dirty = true,
            generation = 1,
        )
        // The delete happens after the payload was built, so it was never sent.
        world.onUpload = { world.store().deleteLogEntry(today, "extra_zone2") }
        world.postResponse = post(
            results = """"$today":{"session_feedback":{},"_lastModified":"srv-day",""" +
                """"extra_zone2":{"duration_min":45,"_lastModified":"t1"}}""",
        )

        world.store().triggerSync()

        assertEquals(
            world.obj("""{"_deleted":true,"_lastModified":"t1"}"""),
            world.storedLog(today)["extra_zone2"],
            "an unarbitrated tombstone must keep its original base",
        )
        assertTrue(world.dao.logs.getValue(today).isDirty)
    }

    @Test
    @DisplayName("a delete uploaded then re-added mid-flight keeps _readd for the next upload")
    fun readdSurvivesIntoTheNextUpload() = runTest {
        val world = World()
        world.seedLog(
            today,
            """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"duration_min":45,"_lastModified":"t1"}}""",
        )
        val store = world.store()
        store.deleteLogEntry(today, "extra_zone2")

        // The delete is accepted server-side while the user re-adds the session.
        world.onUpload = { store.updateLog(today, "extra_zone2", buildJsonObject { put("duration_min", 50) }) }
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")
        store.triggerSync()

        val afterFirst = world.storedLog(today).getValue("extra_zone2").jsonObject
        assertEquals(JsonPrimitive(true), afterFirst["_readd"], "the re-add marker was dropped")
        assertEquals(JsonPrimitive("t1"), afterFirst["_lastModified"], "the re-add lost the base it needs to win")
        assertTrue(world.dao.logs.getValue(today).isDirty)

        // Second cycle: the marker has to reach the server, or the resurrection
        // guard rejects the insert and the re-added session vanishes.
        world.onUpload = {}
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day-2"}""")
        store.triggerSync()

        val body = world.uploadBody()
        assertTrue(body.contains(""""_readd":true"""), "_readd never reached the wire: $body")
        assertTrue(body.contains(""""_baseLastModifiedAt":"t1""""), "the re-add's base token was lost: $body")
    }

    // ---- download --------------------------------------------------------

    @Test
    @DisplayName("the pull overwrites a clean day, and an already-uploaded one takes the server's merged copy")
    fun downloadOverwritesSettledDays() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"local":true}}""", dirty = true, generation = 1)
        world.seedLog("2026-08-05", """{"ex_1":{"local":true}}""")
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")
        world.getResponse = get(
            logs = """"$today":{"ex_1":{"server":true},"_lastModified":"s1"},""" +
                """"2026-08-05":{"ex_1":{"server":true},"_lastModified":"s1"}""",
        )

        world.store().triggerSync()

        assertEquals(JsonPrimitive(true), world.storedLog("2026-08-05").getValue("ex_1").jsonObject["server"])
        // `today` was sent, so its dirty flag cleared before the pull and the
        // merge is free to overwrite it. Safe, and the reason it is safe is that
        // the server copy IS the day this cycle just adopted. (The PWA clears
        // after the pull instead; same end state, and this pins the ordering.)
        assertEquals(JsonPrimitive(true), world.storedLog(today).getValue("ex_1").jsonObject["server"])
    }

    @Test
    @DisplayName("a day dirtied mid-cycle is skipped by the merge — the dirty check is re-read in the transaction")
    fun downloadNeverClobbersADayDirtiedMidCycle() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")
        // A brand-new edit on another date, made while the upload is in flight:
        // it was never part of this cycle's dirty set, so only a check made
        // inside the download transaction can see it.
        world.onUpload = { world.store().updateLog("2026-08-05", "ex_1", buildJsonObject { put("reps", 7) }) }
        world.getResponse = get(logs = """"2026-08-05":{"ex_1":{"server":true},"_lastModified":"s1"}""")

        world.store().triggerSync()

        assertTrue(world.dao.logs.getValue("2026-08-05").isDirty)
        assertEquals(
            JsonPrimitive(7),
            world.storedLog("2026-08-05").getValue("ex_1").jsonObject["reps"],
            "the pull clobbered an edit made mid-cycle",
        )
    }

    @Test
    @DisplayName("deletedPlanDates removes the local plan")
    fun deletedPlanDatesRemovePlans() = runTest {
        val world = World()
        world.seedPlan(today, """{"session_id":1,"day_name":"Push","_lastModified":"p1"}""")
        world.getResponse = get(deletedPlanDates = """"$today"""")

        world.store().triggerSync()

        assertFalse(today in world.dao.plans)
    }

    @Test
    @DisplayName("plans are overwritten unconditionally and the _lastModified column is reprojected every time")
    fun planLastModifiedIsProjectedOnEveryUpsert() = runTest {
        val world = World()
        val store = world.store()

        world.getResponse = get(plans = """"$today":{"session_id":1,"day_name":"Push","_lastModified":"p1"}""")
        store.triggerSync()
        assertEquals("p1", world.dao.plans.getValue(today).lastModified)

        world.getResponse = get(plans = """"$today":{"session_id":1,"day_name":"Pull","_lastModified":"p2"}""")
        store.triggerSync()

        val row = world.dao.plans.getValue(today)
        assertEquals("Pull", world.obj(row.planJson)["day_name"]?.jsonPrimitive()?.content)
        // A stamp that moved in the JSON but not the column would silently stop
        // the version comparison from ever triggering another pull.
        assertEquals("p2", row.lastModified)
    }

    @Test
    @DisplayName("earliestDate prunes plans and logs alike")
    fun earliestDatePrunesBothMaps() = runTest {
        val world = World()
        world.seedPlan("2026-05-01", """{"session_id":1,"_lastModified":"p0"}""")
        world.seedPlan(today, """{"session_id":2,"_lastModified":"p1"}""")
        world.seedLog("2026-05-01", """{"ex_1":{"sets":[]}}""")
        world.seedLog(today, """{"ex_1":{"sets":[]}}""")
        world.getResponse = get(earliestDate = "2026-06-08")

        world.store().triggerSync()

        assertEquals(setOf(today), world.dao.plans.keys)
        assertEquals(setOf(today), world.dao.logs.keys)
        assertEquals("2026-06-08", world.dao.meta[CoachDao.KEY_EARLIEST_DATE])
    }

    @Test
    @DisplayName("the pull's serverTime becomes the watermark and is sent back as last_sync_time")
    fun watermarkRoundTrips() = runTest {
        val world = World()
        world.getResponse = get(serverTime = "s-pull")
        val store = world.store()

        store.triggerSync()
        assertEquals("s-pull", world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME])
        assertFalse(world.urls.first().contains("last_sync_time"), "a first pull must omit the watermark entirely")

        store.triggerSync()
        assertTrue(world.urls.last().contains("last_sync_time=s-pull"), "expected the watermark in ${world.urls.last()}")
    }

    @Test
    @DisplayName("a successful POST followed by a failed GET leaves the watermark untouched")
    fun postServerTimeIsNeverTheWatermark() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(
            serverTime = "post-time",
            results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""",
        )
        world.getStatus = HttpStatusCode.InternalServerError

        val result = world.store().triggerSync()

        assertFalse(result.success)
        // The POST's serverTime is an ordinary write stamp with none of the
        // GET's overlap. Persisting it would make the next incremental pull skip
        // whatever another device committed during this one.
        assertNull(world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
        // The upload still landed and must not be re-sent blindly.
        assertFalse(world.dao.logs.getValue(today).isDirty)
    }

    @Test
    @DisplayName("a pull with no serverTime fails the cycle rather than inventing a watermark")
    fun missingServerTimeFailsTheCycle() = runTest {
        val world = World()
        world.getResponse = """{"plans":{},"logs":{},"deletedPlanDates":[]}"""

        val result = world.store().triggerSync()

        // A device clock running ahead of the server would advance the
        // watermark past changes the server has not delivered yet, and they
        // would never be pulled again. Failing costs one retry.
        assertFalse(result.success)
        assertTrue(result.error != null)
        assertNull(world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
    }

    // ---- pollCheck -------------------------------------------------------

    @Test
    @DisplayName("pollCheck short-circuits to true on dirty data without asking the server")
    fun pollCheckShortCircuitsOnDirty() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)

        assertTrue(world.store().pollCheck())
        assertTrue(world.urls.isEmpty(), "the dirty short-circuit must not hit the network")
    }

    @Test
    @DisplayName("pollCheck: the cold-start baseline is null, so the first version seen triggers a sync")
    fun pollCheckColdStartAlwaysSyncsOnce() = runTest {
        val world = World()
        world.plansVersion = """{"version":"v1"}"""

        val store = world.store()
        assertTrue(store.pollCheck(), "a cold start must sync once — it is also the correctness safety net")
        // The probe alone must NOT retire the version: until a sync consumes
        // it, every tick still answers yes.
        assertTrue(store.pollCheck(), "the version was retired by the probe rather than by a sync")

        store.triggerSync()
        assertFalse(store.pollCheck(), "a successful pull must consume the probed version")

        world.plansVersion = """{"version":"v2"}"""
        assertTrue(store.pollCheck())
    }

    @Test
    @DisplayName("pollCheck keeps saying yes while the sync it asked for keeps failing")
    fun probedVersionSurvivesAFailedSync() = runTest {
        val world = World()
        world.plansVersion = """{"version":"v1"}"""
        world.getStatus = HttpStatusCode.InternalServerError
        val store = world.store()

        assertTrue(store.pollCheck())
        assertFalse(store.triggerSync().success)
        // Recording the version at probe time would have made this false, and
        // the client would sit on plans it never pulled until something else
        // moved the version again.
        assertTrue(store.pollCheck(), "the failed sync retired a version it never consumed")

        world.getStatus = HttpStatusCode.OK
        assertTrue(store.triggerSync().success)
        assertFalse(store.pollCheck(), "once the pull lands the version is consumed")
    }

    @Test
    @DisplayName("a pull consumes the probed version even when it carries no plans of its own")
    fun probedVersionIsConsumedWithoutPlanRows() = runTest {
        val world = World()
        // `plans-version` aggregates log writes and log-entry deletions too, so
        // a probe can legitimately fire for a pull whose `plans` map is empty.
        // Committing only the plan-derived max would leave it unretired and
        // re-sync on every single tick.
        world.plansVersion = """{"version":"v9"}"""
        world.getResponse = get(logs = """"$today":{"session_feedback":{},"_lastModified":"s1"}""")
        val store = world.store()

        assertTrue(store.pollCheck())
        assertTrue(store.triggerSync().success)

        assertFalse(store.pollCheck(), "a log-driven version bump was never retired")
    }

    @Test
    @DisplayName("pollCheck: a null or empty version never triggers a sync")
    fun pollCheckIgnoresAbsentVersion() = runTest {
        val world = World()
        world.plansVersion = """{"version":null}"""
        assertFalse(world.store().pollCheck())

        world.plansVersion = """{}"""
        assertFalse(world.store().pollCheck())
    }

    @Test
    @DisplayName("pollCheck: a failing probe is a quiet no, not an error")
    fun pollCheckFailureIsFalse() = runTest {
        val world = World()
        world.plansVersionStatus = HttpStatusCode.InternalServerError

        assertFalse(world.store().pollCheck())
    }

    // ---- cancellation is not a failure -----------------------------------

    @Test
    @DisplayName("pollCheck rethrows cancellation rather than reporting a healthy poll")
    fun pollCheckRethrowsCancellation() = runTest {
        val api = mockk<CoachApi>()
        coEvery { api.plansVersion() } throws CancellationException("scheduler stopped")
        val store = storeOver(api)

        // Swallowing this into `false` would report a shutting-down scheduler
        // as "nothing changed", and the structured-concurrency contract wants
        // the cancellation to keep travelling.
        assertThrows<CancellationException> { store.pollCheck() }
    }

    @Test
    @DisplayName("triggerSync rethrows cancellation, and never paints it as a red error state")
    fun triggerSyncRethrowsCancellation() = runTest {
        val api = mockk<CoachApi>()
        coEvery { api.sync(any(), any()) } throws CancellationException("scope closed")
        val store = storeOver(api)

        assertThrows<CancellationException> { store.triggerSync() }

        assertEquals(SyncStatus.GRAY, store.syncStatus.value, "cancellation is not a sync failure")
        assertFalse(store.isSyncing, "the busy flag must still be released")
    }

    /** A store over a mocked API, for the failure modes an engine cannot express. */
    private fun storeOver(api: CoachApi): CoachSyncStore = CoachSyncStore(
        session = ServerSessionGate(),
        dao = FakeCoachDao(),
        api = api,
        isOnline = { true },
        json = json,
        debugLog = mockk(relaxed = true),
        clock = { "client-clock" },
        today = { LocalDate.parse(today) },
        newClientId = { "fixed-client" },
    )

    // ---- mutators --------------------------------------------------------

    @Test
    @DisplayName("updateLog on an absent day creates it with session_feedback, the entry and both advisory stamps")
    fun updateLogCreatesTheDay() = runTest {
        val world = World()

        world.store().updateLog(today, "ex_1", buildJsonObject { put("duration_min", 30) })

        val stored = world.storedLog(today)
        assertEquals(JsonObject(emptyMap()), stored["session_feedback"])
        assertEquals(world.obj("""{"duration_min":30}"""), stored["ex_1"])
        assertEquals(JsonPrimitive("client-clock"), stored["_lastModifiedAt"])
        assertEquals(JsonPrimitive("fixed-client"), stored["_lastModifiedBy"])
        assertTrue(world.dao.logs.getValue(today).isDirty)
        assertEquals(1L, world.dao.logs.getValue(today).dirtyGeneration)
        assertEquals(1, world.scheduledUploads)
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("transformLogEntry reads the entry as stored, so back-to-back edits compose")
    fun transformLogEntrySeesTheStoredEntry() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{},"ex_1":{"sets":[{"set_num":1,"weight":60}]}}""")
        val store = world.store()

        // Two edits with nothing read in between. The array is rebuilt from what
        // the transaction finds, not from a snapshot either caller was handed, so
        // the second cannot discard the first.
        store.transformLogEntry(today, "ex_1") { entry ->
            val sets = entry?.getValue("sets")?.jsonArray.orEmpty()
            buildJsonObject { put("sets", JsonArray(sets + world.obj("""{"set_num":2,"weight":65}"""))) }
        }
        store.transformLogEntry(today, "ex_1") { entry ->
            val sets = entry?.getValue("sets")?.jsonArray.orEmpty()
            buildJsonObject { put("sets", JsonArray(sets + world.obj("""{"set_num":3,"weight":70}"""))) }
        }

        val sets = world.storedLog(today).getValue("ex_1").jsonObject.getValue("sets").jsonArray
        assertEquals(3, sets.size, "an edit was lost: $sets")
        assertEquals(
            listOf("60", "65", "70"),
            sets.map { it.jsonObject.getValue("weight").jsonPrimitive.content },
        )
        assertEquals(2L, world.dao.logs.getValue(today).dirtyGeneration)
    }

    @Test
    @DisplayName("transformLogEntry returning null writes nothing at all")
    fun transformLogEntryNoOp() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{},"ex_1":{"sets":[]}}""")
        val before = world.dao.logs.getValue(today)

        world.store().transformLogEntry(today, "ex_1") { null }

        assertEquals(before, world.dao.logs.getValue(today))
        assertEquals(0, world.scheduledUploads, "a no-op must not wake the uploader")
    }

    @Test
    @DisplayName("transformLogEntry hides a tombstone from the transform and records a re-add")
    fun transformLogEntryOverATombstone() = runTest {
        val world = World()
        world.seedLog(
            today,
            """{"session_feedback":{},"extra_zone2":{"_deleted":true,"_lastModified":"srv-1"}}""",
        )

        var sawEntry: JsonObject? = world.obj("{}")
        world.store().transformLogEntry(today, "extra_zone2") { entry ->
            // The screen renders a tombstone as absent; the transform is shown
            // the same thing, so an edit starts from nothing rather than from
            // the deleted session's leftovers.
            sawEntry = entry
            buildJsonObject { put("duration_min", 45) }
        }

        assertNull(sawEntry)
        val entry = world.storedLog(today).getValue("extra_zone2").jsonObject
        assertEquals(JsonPrimitive(45), entry["duration_min"])
        assertNull(entry["_deleted"], "the tombstone must not survive a re-add")
        assertEquals(JsonPrimitive(true), entry["_readd"])
        assertEquals(JsonPrimitive("srv-1"), entry["_lastModified"], "the base token is what lets the re-add win")
    }

    @Test
    @DisplayName("updateSessionFeedback merges and stamps BOTH advisory fields")
    fun sessionFeedbackStampsBothFields() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{"pain_discomfort":"knee"},"ex_1":{"sets":[]}}""")

        world.store().updateSessionFeedback(today, feedback("general_notes", "felt good"))

        val stored = world.storedLog(today)
        val fb = stored.getValue("session_feedback").jsonObject
        assertEquals(JsonPrimitive("knee"), fb["pain_discomfort"], "the merge dropped an untouched field")
        assertEquals(JsonPrimitive("felt good"), fb["general_notes"])
        // The PWA sets only `_lastModifiedAt` here, inconsistently with its
        // other two mutators. Both are advisory, so we normalize.
        assertEquals(JsonPrimitive("client-clock"), stored["_lastModifiedAt"])
        assertEquals(JsonPrimitive("fixed-client"), stored["_lastModifiedBy"])
        assertTrue(world.dao.logs.getValue(today).isDirty)
    }

    @Test
    @DisplayName("deleteLogEntry on an absent day, or an absent key, is a full no-op")
    fun deleteLogEntryNoOpDoesNotDirty() = runTest {
        val world = World()
        val store = world.store()

        store.deleteLogEntry(today, "ex_1")
        assertFalse(today in world.dao.logs, "an absent day must not be created by a delete")

        world.seedLog(today, """{"session_feedback":{},"ex_1":{"sets":[]}}""")
        store.deleteLogEntry(today, "not-there")

        val row = world.dao.logs.getValue(today)
        assertFalse(row.isDirty, "deleting a key that is not there must not dirty the day")
        assertEquals(0L, row.dirtyGeneration)
        assertFalse("_lastModifiedAt" in world.storedLog(today), "nor stamp it")
        assertEquals(0, world.scheduledUploads, "nor schedule an upload")
    }

    @Test
    @DisplayName("deleteLogEntry on a real key tombstones it, keeping the server stamp, and dirties the day")
    fun deleteLogEntryTombstones() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{},"ex_1":{"sets":[],"_lastModified":"t1"}}""")

        world.store().deleteLogEntry(today, "ex_1")

        assertEquals(world.obj("""{"_deleted":true,"_lastModified":"t1"}"""), world.storedLog(today)["ex_1"])
        assertTrue(world.dao.logs.getValue(today).isDirty)
        assertEquals(1, world.scheduledUploads)
    }

    @Test
    @DisplayName("updateLog over a pending tombstone is a re-add, not a resurrection of the delete")
    fun updateLogOverTombstoneIsAReadd() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{},"ex_1":{"_deleted":true,"_lastModified":"t1"}}""")

        world.store().updateLog(today, "ex_1", buildJsonObject { put("duration_min", 45) })

        val entry = world.storedLog(today).getValue("ex_1").jsonObject
        assertFalse("_deleted" in entry, "the server would have processed the re-add as a deletion")
        assertEquals(JsonPrimitive(true), entry["_readd"])
        assertEquals(JsonPrimitive("t1"), entry["_lastModified"])
    }

    // ---- status, single-flight, offline ----------------------------------

    @Test
    @DisplayName("offline is a skip, not a failure, and shows gray")
    fun offlineSkips() = runTest {
        val world = World()
        world.online = false

        val result = world.store().triggerSync()

        assertFalse(result.success)
        assertEquals(SyncSkipReason.OFFLINE, result.reason)
        assertNull(result.error)
        assertEquals(SyncStatus.GRAY, world.store().syncStatus.value)
        assertTrue(world.urls.isEmpty())
    }

    @Test
    @DisplayName("a clean coach store is GREEN even with no watermark — unlike the journal")
    fun greenHasNoWatermarkCondition() = runTest {
        val world = World()
        // The journal shows GRAY until it has synced once. The coach dot does
        // not have that condition, and the two clients must not disagree about
        // the same server state.
        world.dao.dropWatermarkWrites = true

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertNull(world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.GREEN, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("a second sync entering while one is in flight is refused, not interleaved")
    fun triggerSyncIsSingleFlight() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-day"}""")
        var reentrant: dev.jtiisto.wellness.core.data.sync.SyncResult? = null
        world.onUpload = { reentrant = world.store().triggerSync() }

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(SyncSkipReason.ALREADY_SYNCING, reentrant?.reason)
        assertFalse(reentrant!!.success)
    }

    @Test
    @DisplayName("the client id is minted once and reused")
    fun clientIdIsStable() = runTest {
        val world = World()
        val store = world.store()

        assertEquals("fixed-client", store.clientId())
        assertEquals("fixed-client", store.clientId())
        assertEquals("fixed-client", world.dao.meta[CoachDao.KEY_CLIENT_ID])
    }

    // ---- reads -----------------------------------------------------------

    @Test
    @DisplayName("observePlan decodes on read, and a blob that will not decode yields null rather than throwing")
    fun observePlanDecodesOnRead() = runTest {
        val world = World()
        world.seedPlan(
            today,
            """{"session_id":7,"day_name":"Push","blocks":[{"block_index":0,"block_type":"strength",""" +
                """"exercises":[{"id":"s1","name":"Bench","type":"strength","target_rpe":"6-7"}]}]}""",
        )
        val store = world.store()

        val plan = requireNotNull(store.observePlan(today).first())
        assertEquals(7L, plan.sessionId)
        assertEquals("Push", plan.dayName)
        assertEquals("6-7", plan.blocks.single().exercises.single().targetRpe)

        world.seedPlan("2026-08-05", """{"nope":true}""")
        assertNull(store.observePlan("2026-08-05").first(), "a bad blob must not take the screen down")
    }

    @Test
    @DisplayName("observeLog hands back the stored day verbatim")
    fun observeLogIsVerbatim() = runTest {
        val world = World()
        world.seedLog(today, """{"session_feedback":{},"extra_zone2":{"duration_min":30}}""")

        val log = requireNotNull(world.store().observeLog(today).first())

        assertEquals(setOf("session_feedback", "extra_zone2"), log.keys)
        assertNull(world.store().observeLog("2026-08-05").first())
    }

    // ---- nullable adoption semantics (pure, but load-bearing here) -------

    @Test
    @DisplayName("adoption: a null snapshot disables re-modification detection; an EMPTY map does not")
    fun nullSnapshotIsNotAnEmptyMap() = runTest {
        val local = mapOf("d1" to obj("""{"ex_1":{"local":true},"_lastModified":"old"}"""))
        val results = mapOf("d1" to obj("""{"ex_1":{"server":true},"_lastModified":"srv"}"""))
        val currentGens = mapOf("d1" to 5L)

        // null: no detection at all → wholesale adoption.
        val withNull = adoptUploadResults(local, results, null, currentGens)
        assertEquals(results.getValue("d1"), withNull.getValue("d1"))

        // empty map: `5 != null` → re-modified → local content kept.
        val withEmpty = adoptUploadResults(local, results, emptyMap(), currentGens)
        assertEquals(JsonPrimitive(true), withEmpty.getValue("d1").getValue("ex_1").jsonObject["local"])
        assertEquals(JsonPrimitive("srv"), withEmpty.getValue("d1")["_lastModified"])
    }

    @Test
    @DisplayName("adoption: a null uploadedLogs treats every re-modified tombstone as created mid-sync")
    fun nullUploadedLogsPreservesTombstones() = runTest {
        val local = mapOf(
            "d1" to obj("""{"_lastModified":"day-tok","extra_zone2":{"_deleted":true,"_lastModified":"t1"}}"""),
        )
        val results = mapOf(
            "d1" to obj("""{"_lastModified":"srv-day","extra_zone2":{"duration_min":60,"_lastModified":"t2"}}"""),
        )

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploadedLogs = null)

        // Nothing is known to have been arbitrated, so no verdict may be applied.
        assertEquals(
            obj("""{"_deleted":true,"_lastModified":"t1"}"""),
            next.getValue("d1")["extra_zone2"],
        )
    }

    @Test
    @DisplayName("adoption: null results leave local logs exactly as they were")
    fun nullResultsAreANoOp() = runTest {
        val local = mapOf("d1" to obj("""{"ex_1":{"local":true}}"""))

        assertEquals(local, adoptUploadResults(local, null, mapOf("d1" to 1L), mapOf("d1" to 1L)))
    }


    // ---- force sync ------------------------------------------------------

    @Test
    @DisplayName("forceSync pulls with no last_sync_time even when a watermark is stored")
    fun forceSyncIgnoresTheStoredWatermark() = runTest {
        val world = World()
        world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME] = "s-old"

        val result = world.store().forceSync()

        assertTrue(result is ForceSyncModuleResult.Success)
        val pull = world.urls.single { !it.contains("plans-version") }
        assertFalse(pull.contains("last_sync_time"), "a force pull must be unconditional: $pull")
        assertEquals("s-pull", world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME])
    }

    @Test
    @DisplayName("triggerSync, by contrast, always sends the stored watermark")
    fun triggerSyncStillSendsTheWatermark() = runTest {
        val world = World()
        world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME] = "s-old"

        world.store().triggerSync()

        assertTrue(world.urls.single().contains("last_sync_time=s-old"), world.urls.single())
    }

    @Test
    @DisplayName("upload still goes first on the force path")
    fun forceSyncUploadsBeforePulling() = runTest {
        val world = World()
        world.seedLog(today, """{"_lastModified":"day-tok","ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")

        world.store().forceSync()

        assertEquals(listOf("POST", "GET"), world.httpCalls())
    }

    @Test
    @DisplayName("the reported count is the dates actually sent, not the dates that were dirty")
    fun forceSyncCountsUploadedDates() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        // Empty and never synced: unsatisfiable, dropped before the POST.
        world.seedLog("2026-08-05", """{"session_feedback":{}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")

        val result = world.store().forceSync()

        assertEquals(ForceSyncModuleResult.Success(ForceSyncCounts.Coach(uploadedDates = 1)), result)
    }

    @Test
    @DisplayName("offline is a skip with its own reason")
    fun forceSyncOffline() = runTest {
        val world = World()
        world.online = false

        assertEquals(
            ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE),
            world.store().forceSync(),
        )
        assertTrue(world.urls.isEmpty())
    }

    @Test
    @DisplayName("a cycle already running is a busy skip")
    fun forceSyncBusy() = runTest {
        val world = World()
        world.seedLog(today, """{"ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        var nested: ForceSyncModuleResult? = null
        world.onUpload = { nested = world.store().forceSync() }
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")

        world.store().triggerSync()

        assertEquals(ForceSyncModuleResult.Skipped(ForceSyncSkipReason.BUSY), nested)
    }

    @Test
    @DisplayName("a failing pull is a Failed result and a red dot")
    fun forceSyncFailure() = runTest {
        val world = World()
        world.getStatus = HttpStatusCode.InternalServerError

        val result = world.store().forceSync()

        assertTrue(result is ForceSyncModuleResult.Failed)
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
    }

    // ---- full-snapshot plans ---------------------------------------------

    @Test
    @DisplayName("a plan the server no longer sends is deleted on the force path")
    fun forceSyncDeletesPlansAbsentFromTheResponse() = runTest {
        val world = World()
        world.seedPlan("2030-01-04", """{"_lastModified":"p-old","session":{}}""")
        world.seedPlan(today, """{"_lastModified":"p-1","session":{}}""")
        // A full pull carries no `deletedPlanDates` — the server computes those
        // relative to a last_sync_time it was not given. Without full-snapshot
        // semantics the stale plan would survive every "full reconciliation".
        world.getResponse = get(plans = """"$today":{"_lastModified":"p-2","session":{}}""")

        world.store().forceSync()

        assertFalse(world.dao.plans.containsKey("2030-01-04"), "a server-deleted plan survived the force sync")
        assertEquals("p-2", world.dao.plans.getValue(today).lastModified)
    }

    @Test
    @DisplayName("the incremental path is untouched: an absent plan is kept without an explicit deletion")
    fun incrementalPullKeepsPlansAbsentFromTheResponse() = runTest {
        val world = World()
        world.seedPlan("2030-01-04", """{"_lastModified":"p-old","session":{}}""")
        world.getResponse = get(plans = """"$today":{"_lastModified":"p-2","session":{}}""")

        world.store().triggerSync()

        assertTrue(
            world.dao.plans.containsKey("2030-01-04"),
            "an incremental pull only reports what changed; absence means nothing",
        )
    }

    @Test
    @DisplayName("a dirty local log is never deleted by the full snapshot — it has no server copy yet")
    fun forceSyncKeepsDirtyLogsAbsentFromTheResponse() = runTest {
        val world = World()
        world.seedLog("2030-01-04", """{"ex_1":{"sets":[{"reps":9}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = "")
        world.getResponse = get(logs = """"$today":{"session_feedback":{}}""")

        world.store().forceSync()

        assertTrue(world.dao.logs.containsKey("2030-01-04"), "unsent local work was destroyed by the snapshot")
        assertEquals(
            9,
            world.storedLog("2030-01-04")["ex_1"]!!.jsonObject["sets"]!!.jsonArray[0]
                .jsonObject["reps"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    @DisplayName("an explicit deletedPlanDates is still honoured on the force path")
    fun forceSyncStillHonoursExplicitDeletions() = runTest {
        val world = World()
        world.seedPlan("2030-01-03", """{"_lastModified":"p-a","session":{}}""")
        world.getResponse = get(deletedPlanDates = """"2030-01-03"""")

        world.store().forceSync()

        assertFalse(world.dao.plans.containsKey("2030-01-03"))
    }

    // ---- the server-switch fence -----------------------------------------

    @Test
    @DisplayName("a pull that lands after the session closes writes NOTHING — no rows, no watermark")
    fun closedGateFencesTheDownloadApply() = runTest {
        val world = World()
        world.getResponse = get(
            plans = """"$today":{"_lastModified":"p-1","session":{}}""",
            logs = """"$today":{"session_feedback":{}}""",
            serverTime = "s-late",
        )
        world.onDownload = { world.session.close() }

        val result = world.store().triggerSync()

        assertFalse(result.success)
        assertTrue(world.dao.plans.isEmpty(), "old-server plans reached the wiped database")
        assertTrue(world.dao.logs.isEmpty(), "old-server logs reached the wiped database")
        assertNull(
            world.dao.meta[CoachDao.KEY_LAST_SERVER_SYNC_TIME],
            "the old server's watermark was restored — the next full pull would be skipped",
        )
    }

    @Test
    @DisplayName("an upload response that lands after the session closes adopts nothing")
    fun closedGateFencesTheUploadApply() = runTest {
        val world = World()
        world.seedLog(today, """{"_lastModified":"day-tok","ex_1":{"sets":[{"reps":5}]}}""", dirty = true, generation = 1)
        world.postResponse = post(results = """"$today":{"session_feedback":{},"_lastModified":"srv-1"}""")
        world.onUpload = { world.session.close() }

        world.store().triggerSync()

        assertEquals(
            "day-tok",
            world.storedLog(today)["_lastModified"]!!.jsonPrimitive.content,
            "a post-wipe adoption was applied",
        )
        assertTrue(world.dao.logs.getValue(today).isDirty)
    }

    @Test
    @DisplayName("awaitQuiescence returns once no cycle holds the sync lock")
    fun quiescenceReturnsWhenIdle() = runTest {
        val world = World()

        world.store().triggerSync()
        world.store().awaitQuiescence()

        assertEquals(listOf("GET"), world.httpCalls())
    }

    // ---- harness ---------------------------------------------------------

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun feedback(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }

    private fun get(
        plans: String = "",
        logs: String = "",
        serverTime: String = "s-pull",
        earliestDate: String? = null,
        deletedPlanDates: String = "",
    ): String = buildString {
        append("""{"plans":{$plans},"logs":{$logs},"serverTime":"$serverTime",""")
        if (earliestDate != null) append(""""earliestDate":"$earliestDate",""")
        append(""""deletedPlanDates":[$deletedPlanDates]}""")
    }

    private fun post(serverTime: String = "s-post", results: String = ""): String =
        """{"success":true,"results":{$results},"serverTime":"$serverTime"}"""

    /** The fake server, the fake storage, and the knobs the tests turn. */
    private inner class World {
        val dao = FakeCoachDao()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        var online = true
        var getResponse: String = get()
        var postResponse: String = post()
        var plansVersion: String = """{"version":null}"""
        var getStatus: HttpStatusCode = HttpStatusCode.OK
        var plansVersionStatus: HttpStatusCode = HttpStatusCode.OK
        var onUpload: suspend () -> Unit = {}
        var onDownload: suspend () -> Unit = {}
        var scheduledUploads = 0

        /** Open unless a test closes it — the server-switch fence. */
        val session = ServerSessionGate()

        private val debugLog = mockk<DebugLog>(relaxed = true).also {
            every { it.log(any(), any(), any()) } returns Unit
        }

        private val api: CoachApi by lazy {
            val config = ServerConfig("http://localhost:9001/wellness")
            val engine = MockEngine { request ->
                urls += request.url.toString()
                when {
                    request.url.encodedPath.endsWith("plans-version") ->
                        respondJson(plansVersion, plansVersionStatus)

                    request.method == HttpMethod.Post -> {
                        dao.calls += "POST"
                        bodies += request.body.toByteArray().decodeToString()
                        onUpload()
                        respondJson(postResponse, HttpStatusCode.OK)
                    }

                    else -> {
                        dao.calls += "GET"
                        onDownload()
                        respondJson(getResponse, getStatus)
                    }
                }
            }
            CoachApi(buildHttpClient(engine, config, json, debugLog), config)
        }

        private val storeInstance: CoachSyncStore by lazy {
            CoachSyncStore(
                dao = dao,
                api = api,
                isOnline = { online },
                session = session,
                json = json,
                // A real (mocked) log, not null: the log lines build JsonObjects
                // eagerly, and a safe call on a null receiver would skip them.
                debugLog = debugLog,
                scheduleUpload = { scheduledUploads++ },
                clock = { "client-clock" },
                today = { LocalDate.parse(today) },
                newClientId = { "fixed-client" },
            )
        }

        fun store(): CoachSyncStore = storeInstance

        fun httpCalls(): List<String> = dao.calls.filter { it == "POST" || it == "GET" }

        fun uploadBody(): String = bodies.lastOrNull() ?: error("no upload was sent")

        fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        fun storedLog(date: DateString): JsonObject = obj(dao.logs.getValue(date).logJson)

        fun seedLog(date: DateString, logJson: String, dirty: Boolean = false, generation: Long = 0) {
            dao.logs[date] = CoachLogEntity(date, logJson, dirty, generation)
        }

        fun seedPlan(date: DateString, planJson: String) {
            dao.plans[date] = CoachPlanEntity(
                date = date,
                planJson = planJson,
                lastModified = obj(planJson)["_lastModified"]?.jsonPrimitive()?.content,
            )
        }

        /** A synced day whose only entry is an uploadable tombstone. */
        fun seedTombstoneDay() {
            seedLog(
                today,
                """{"_lastModified":"day-tok","session_feedback":{},""" +
                    """"extra_zone2":{"_deleted":true,"_lastModified":"t1"}}""",
                dirty = true,
                generation = 1,
            )
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive(): JsonPrimitive? = this as? JsonPrimitive
}

/**
 * In-memory [CoachDao]. Only the SQL primitives are overridden — the composed
 * `@Transaction` methods are inherited, so these tests exercise the real
 * ordering inside them rather than a re-implementation of it.
 */
internal open class FakeCoachDao : CoachDao() {

    val plans = linkedMapOf<DateString, CoachPlanEntity>()
    val logs = linkedMapOf<DateString, CoachLogEntity>()
    val meta = linkedMapOf<String, String>()

    /** Ordered write log, so tests can assert what happened *inside* a transaction. */
    val calls = mutableListOf<String>()

    /** Fires just before the dirty snapshot is read — the atomic-build window. */
    var beforeBuildPendingUpload: suspend () -> Unit = {}

    /** Fires just before the response transaction opens. */
    var beforeApplyUploadResults: suspend () -> Unit = {}

    /** Simulates a store that has never recorded a pull watermark. */
    var dropWatermarkWrites = false

    private val revision = MutableStateFlow(0L)

    private fun touch() {
        revision.value += 1
    }

    fun dirtyCount(): Int = logs.values.count { it.isDirty }

    override suspend fun buildPendingUpload(build: (List<CoachLogEntity>) -> PendingUpload): PendingUpload {
        beforeBuildPendingUpload()
        calls += "buildPendingUpload"
        return super.buildPendingUpload(build)
    }

    override suspend fun applyUploadResults(
        dates: List<DateString>,
        clears: List<CoachDirtyClear>,
        adopt: (List<CoachLogEntity>) -> List<CoachLogEntity>,
    ) {
        beforeApplyUploadResults()
        calls += "applyUploadResults:start"
        super.applyUploadResults(dates, clears, adopt)
        calls += "applyUploadResults:end"
    }

    override suspend fun clearDirty(clears: List<CoachDirtyClear>) {
        calls += "clearDirty"
        super.clearDirty(clears)
    }

    override suspend fun applyDownload(
        plans: List<CoachPlanEntity>,
        deletedPlanDates: List<DateString>,
        logs: List<CoachLogEntity>,
        earliestDate: DateString?,
        watermark: String?,
        fullSnapshotPlans: Boolean,
    ) {
        calls += "applyDownload:start"
        super.applyDownload(plans, deletedPlanDates, logs, earliestDate, watermark, fullSnapshotPlans)
        calls += "applyDownload:end"
    }

    override suspend fun getMeta(key: String): String? = meta[key]

    override fun observeMeta(key: String): Flow<String?> = revision.map { meta[key] }

    override suspend fun upsertMeta(row: CoachMetaEntity) {
        if (dropWatermarkWrites && row.key == KEY_LAST_SERVER_SYNC_TIME) return
        meta[row.key] = row.value
        touch()
    }

    override suspend fun insertMetaIfAbsent(key: String, value: String) {
        meta.putIfAbsent(key, value)
        touch()
    }

    override suspend fun getPlan(date: DateString): CoachPlanEntity? = plans[date]

    override fun observePlan(date: DateString): Flow<CoachPlanEntity?> = revision.map { plans[date] }

    override suspend fun listAllPlans(): List<CoachPlanEntity> = plans.values.sortedBy { it.date }

    override fun observeAllPlans(): Flow<List<CoachPlanEntity>> =
        revision.map { plans.values.sortedBy { row -> row.date } }

    override suspend fun upsertPlan(row: CoachPlanEntity) {
        plans[row.date] = row
        touch()
    }

    override suspend fun deletePlan(date: DateString) {
        plans.remove(date)
        touch()
    }

    override suspend fun getLog(date: DateString): CoachLogEntity? = logs[date]

    override fun observeLog(date: DateString): Flow<CoachLogEntity?> = revision.map { logs[date] }

    override suspend fun listAllLogs(): List<CoachLogEntity> = logs.values.sortedBy { it.date }

    override fun observeAllLogs(): Flow<List<CoachLogEntity>> =
        revision.map { logs.values.sortedBy { row -> row.date } }

    override suspend fun listDirtyLogs(): List<CoachLogEntity> =
        logs.values.filter { it.isDirty }.sortedBy { it.date }

    override suspend fun snapshotDirtyLogs(): List<CoachDateGeneration> =
        listDirtyLogs().map { CoachDateGeneration(it.date, it.dirtyGeneration) }

    override suspend fun countDirty(): Int = dirtyCount()

    override fun observeDirtyCount(): Flow<Int> = revision.map { dirtyCount() }

    override suspend fun upsertLog(row: CoachLogEntity) {
        logs[row.date] = row
        touch()
    }

    override suspend fun markLogDirty(date: DateString) {
        logs.computeIfPresent(date) { _, row ->
            row.copy(isDirty = true, dirtyGeneration = row.dirtyGeneration + 1)
        }
        touch()
    }

    override suspend fun clearLogDirty(date: DateString, generation: Long) {
        logs.computeIfPresent(date) { _, row ->
            if (row.dirtyGeneration == generation) row.copy(isDirty = false) else row
        }
        touch()
    }

    override suspend fun pruneLogsBefore(cutoff: DateString) {
        logs.values.removeIf { it.date < cutoff }
        touch()
    }

    override suspend fun prunePlansBefore(cutoff: DateString) {
        plans.values.removeIf { it.date < cutoff }
        touch()
    }
}
