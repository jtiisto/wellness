package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.EntryGeneration
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalMetaEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.db.TrackerGeneration
import dev.jtiisto.wellness.core.data.db.UploadResponseApply
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.SyncStamp
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ForceSyncCounts
import dev.jtiisto.wellness.core.data.sync.ForceSyncModuleResult
import dev.jtiisto.wellness.core.data.sync.ForceSyncSkipReason
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncSkipReason
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The full [JournalSyncStore] cycle against a fake DAO and a mock server.
 *
 * What these are really guarding is ordering: the generation snapshot ahead of
 * the network, the pull's dirty-skip, one mutating write after the upload, and
 * the prune last. Each is a place where a plausible-looking rearrangement
 * silently loses a user's edit.
 */
class JournalSyncStoreTest {

    private val json = WellnessJson

    // ---- pull ------------------------------------------------------------

    @Test
    @DisplayName("first sync omits `since`, stores the server's rows and the watermark")
    fun firstSyncFullPull() = runTest {
        val world = World()
        world.delta = delta(
            config = """{"id":"t1","name":"Water","category":"Habits","type":"simple","lastModifiedAt":"s1"}""",
            days = """"2026-08-06":{"t1":{"value":null,"completed":true,"lastModifiedAt":"s1"}}""",
            serverTime = "s2",
        )

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(1, world.urls.size, "no upload expected: nothing was dirty")
        assertFalse(world.urls.single().contains("since="), "first sync must omit `since` entirely")
        assertTrue(world.urls.single().contains("client_id=fixed-client"))

        val tracker = world.dao.trackers.getValue("t1")
        assertEquals("Water", tracker.name)
        assertEquals("Habits", tracker.category)
        assertEquals("s1", tracker.lastModifiedAt)
        assertEquals("s1", world.storedStamp("t1"))
        assertFalse(tracker.isDirty)
        assertEquals(true, world.dao.entries.getValue("2026-08-06|t1").completed)
        assertEquals("s2", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.GREEN, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("a server-sent explicit null value is stored as null, not as absent")
    fun pullPreservesExplicitNullValues() = runTest {
        val world = World()
        world.delta = delta(
            config = """{"id":"t1","name":"Water","type":"quantifiable","lastModifiedAt":"s1"}""",
            days = """"2026-08-06":{""" +
                """"t1":{"value":null,"completed":true,"lastModifiedAt":"s1"},""" +
                """"t2":{"completed":true,"lastModifiedAt":"s1"}}""",
        )

        world.store().triggerSync()

        // The two are different states and storage has to keep them apart: the
        // checkbox seeds a tracker's default value into an ABSENT entry only,
        // and folding these together would let it overwrite a null the server
        // deliberately sent.
        assertEquals("null", world.dao.entries.getValue("2026-08-06|t1").valueJson)
        assertNull(world.dao.entries.getValue("2026-08-06|t2").valueJson, "no value key at all stays absent")

        val day = world.store().observeDay("2026-08-06").first()
        assertEquals(JsonNull, day.getValue("t1").value)
        assertFalse(day["t1"].isValueAbsent(), "an explicit null is a value the checkbox must not replace")
        assertNull(day.getValue("t2").value)
        assertTrue(day["t2"].isValueAbsent())
    }

    @Test
    @DisplayName("an update that changes nothing writes nothing and dirties nothing")
    fun unchangedUpdateIsANoOp() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0")
        val before = world.dao.trackers.getValue("t1")

        world.store().updateTracker("t1") { it }

        assertEquals(before, world.dao.trackers.getValue("t1"))
        assertEquals(0, world.dao.countDirty(), "an untouched save must not dirty the tracker")
        assertEquals(0, world.scheduledUploads, "nor schedule an upload for it")
    }

    @Test
    @DisplayName("a real edit still writes and dirties")
    fun changedUpdateStillWrites() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0")

        world.store().updateTracker("t1") { it.copy(name = "Hydration") }

        assertEquals("Hydration", world.dao.trackers.getValue("t1").name)
        assertTrue(world.dao.trackers.getValue("t1").isDirty)
        assertEquals(1, world.scheduledUploads)
    }

    @Test
    @DisplayName("a later sync sends the stored watermark as `since`")
    fun incrementalPullSendsWatermark() = runTest {
        val world = World()
        world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME] = "s1"

        world.store().triggerSync()

        assertTrue(world.urls.single().contains("since=s1"), "expected the watermark in ${world.urls.single()}")
    }

    @Test
    @DisplayName("the pull overwrites clean rows and leaves locally dirty ones alone")
    fun pullSkipsDirtyRows() = runTest {
        val world = World()
        world.seedTracker("t1", name = "local edit", stamp = "s0", isDirty = true, generation = 1)
        world.seedTracker("t2", name = "clean", stamp = "s0")
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s0", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t2", completed = true, stamp = "s0")
        world.delta = delta(
            config = """{"id":"t1","name":"server wins","lastModifiedAt":"s9"},""" +
                """{"id":"t2","name":"server wins","lastModifiedAt":"s9"}""",
            days = """"2026-08-06":{""" +
                """"t1":{"value":null,"completed":false,"lastModifiedAt":"s9"},""" +
                """"t2":{"value":null,"completed":false,"lastModifiedAt":"s9"}}""",
        )
        world.update = update(
            accepted = """{"id":"t1","lastModifiedAt":"s10"}""",
            acceptedEntries = """{"date":"2026-08-06","trackerId":"t1","lastModifiedAt":"s10"}""",
        )

        world.store().triggerSync()

        // The dirty rows kept their local values through the pull and were the
        // ones uploaded; the clean rows took the server's.
        assertEquals("server wins", world.dao.trackers.getValue("t2").name)
        assertEquals(false, world.dao.entries.getValue("2026-08-06|t2").completed)
        val uploaded = world.uploadBody()
        assertTrue(uploaded.contains("local edit"), "the dirty tracker's local value should have uploaded")
        assertEquals(true, world.dao.entries.getValue("2026-08-06|t1").completed)
    }

    @Test
    @DisplayName("deletedTrackers drops the tracker, its entries and all of their dirty state")
    fun deletedTrackersPurgeIncludesDirty() = runTest {
        val world = World()
        world.seedTracker("t1", name = "doomed", stamp = "s0", isDirty = true, generation = 3)
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s0", isDirty = true, generation = 2)
        world.seedTracker("t2", name = "survivor", stamp = "s0")
        world.delta = delta(deletedTrackers = """"t1"""")

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(setOf("t2"), world.dao.trackers.keys)
        assertTrue(world.dao.entries.isEmpty())
        // Without the dirty purge the client would stay red forever, retrying
        // an upload for a row the server no longer has.
        assertEquals(0, world.dao.countDirty())
        assertEquals(1, world.urls.size, "nothing was left dirty, so nothing should have uploaded")
    }

    // ---- upload ----------------------------------------------------------

    @Test
    @DisplayName("an accepted upload stamps the token into dataJson and the column, and clears dirty")
    fun acceptedUploadStampsAndClears() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s0", isDirty = true, generation = 1)
        world.update = update(
            serverTime = "s5",
            accepted = """{"id":"t1","lastModifiedAt":"s5"}""",
            acceptedEntries = """{"date":"2026-08-06","trackerId":"t1","lastModifiedAt":"s5"}""",
        )

        val store = world.store()
        val result = store.triggerSync()

        assertTrue(result.success)
        assertEquals("s5", world.dao.trackers.getValue("t1").lastModifiedAt)
        // The column alone is not enough: the next upload reads its base token
        // out of the JSON.
        assertEquals("s5", world.storedStamp("t1"))
        assertEquals("s5", world.dao.entries.getValue("2026-08-06|t1").lastModifiedAt)
        assertEquals(0, world.dao.countDirty())
        // The pull's stamp, not the upload's: see `uploadDoesNotAdvanceTheWatermark`.
        assertEquals("s-delta", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.GREEN, store.syncStatus.value)
    }

    @Test
    @DisplayName("an upload NEVER advances the pull watermark — only the delta's serverTime does")
    fun uploadDoesNotAdvanceTheWatermark() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.delta = delta(serverTime = "s-delta")
        // Later than the delta's, as a real POST's always is — it is stamped at
        // wall clock while the delta's is stamped two seconds behind it.
        world.update = update(serverTime = "s-upload", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")

        world.store().triggerSync()

        // Those two seconds are the server's deliberate overlap: the next
        // incremental pull re-reads the window in which another client's write
        // may have been committed but not yet visible. Storing the POST's stamp
        // discarded it, and with a PWA and this app on the same account that is
        // a change delivered to neither. Redelivery is the cost, and every row
        // carries the token that makes re-applying it a no-op.
        assertEquals("s-delta", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals("s5", world.storedStamp("t1"), "the row's own token still comes from the response")
    }

    @Test
    @DisplayName("an edit made during the upload keeps the row dirty for the next cycle")
    fun midSyncEditStaysDirty() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")
        // The user re-edits while the request is in flight: the generation
        // advances past the snapshot, so the clear must not land.
        world.onUpload = { world.dao.markTrackerDirty("t1") }

        world.store().triggerSync()

        assertTrue(world.dao.trackers.getValue("t1").isDirty, "the mid-sync edit was silently dropped")
        assertEquals(2L, world.dao.trackers.getValue("t1").dirtyGeneration)
        // The token still landed, so the retry uploads against a fresh base.
        assertEquals("s5", world.storedStamp("t1"))
    }

    @Test
    @DisplayName("an edit landing between the response and the write keeps both the edit and its dirty flag")
    fun editBetweenResponseAndApplyIsPreserved() = runTest {
        val world = World()
        world.seedTracker("t1", name = "original", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")
        // The narrowest window there is: the response is in hand, the write has
        // not started. Rows read before this point are already stale.
        world.dao.beforeApplyUploadResponse = {
            world.store().updateTracker("t1") { it.copy(name = "edited mid-apply") }
        }

        world.store().triggerSync()

        val row = world.dao.trackers.getValue("t1")
        assertEquals("edited mid-apply", row.name, "the edit was overwritten by a pre-transaction read")
        assertEquals("edited mid-apply", world.storedTracker("t1").name, "dataJson kept the stale copy")
        assertTrue(row.isDirty, "the edit's dirty marker was cleared, so it would never upload")
        assertEquals(2L, row.dirtyGeneration)
        // The token still lands: without it the retry arbitrates against a base
        // the server has already moved past, and comes back rejected.
        assertEquals("s5", row.lastModifiedAt)
        assertEquals("s5", world.storedStamp("t1"))
    }

    @Test
    @DisplayName("an adoption is refused once the row has moved past the generation that was uploaded")
    fun adoptionRefusedWhenTheRowMovedOn() = runTest {
        val world = World()
        world.seedTracker("t1", name = "local", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(
            serverTime = "s5",
            rejected = """{"id":"t1","errorKind":"stale","serverRow":""" +
                """{"id":"t1","name":"server","deleted":false,"lastModifiedAt":"s9"}}""",
        )
        world.dao.beforeApplyUploadResponse = {
            world.store().updateTracker("t1") { it.copy(name = "edited mid-apply") }
        }

        world.store().triggerSync()

        val row = world.dao.trackers.getValue("t1")
        assertEquals("edited mid-apply", row.name, "the newer edit must win over a stale adoption")
        assertTrue(row.isDirty, "it stays dirty so the next cycle arbitrates it properly")
    }

    @Test
    @DisplayName("a rejected tracker adopts the server's row in-cycle and stops being dirty")
    fun rejectedTrackerAdoptsServerRow() = runTest {
        val world = World()
        world.seedTracker("t1", name = "local", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(
            serverTime = "s5",
            rejected = """{"id":"t1","errorKind":"stale","serverRow":""" +
                """{"id":"t1","name":"server","category":"Habits","deleted":false,"lastModifiedAt":"s9"}}""",
        )

        world.store().triggerSync()

        val row = world.dao.trackers.getValue("t1")
        assertEquals("server", row.name)
        assertEquals("Habits", row.category)
        assertEquals("s9", row.lastModifiedAt)
        assertEquals("s9", world.storedStamp("t1"))
        assertFalse(row.isDirty, "a rejection is settled too — leaving it dirty would loop")
    }

    @Test
    @DisplayName("a rejected soft-deleted serverRow removes the tracker, its entries and both dirty sets")
    fun rejectedSoftDeleteCleansUp() = runTest {
        val world = World()
        world.seedTracker("t1", name = "local", stamp = "s0", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s0", isDirty = true, generation = 1)
        world.seedTracker("t2", name = "survivor", stamp = "s0")
        world.update = update(
            serverTime = "s5",
            rejected = """{"id":"t1","errorKind":"stale","serverRow":{"id":"t1","deleted":true}}""",
        )

        world.store().triggerSync()

        assertEquals(setOf("t2"), world.dao.trackers.keys)
        assertTrue(world.dao.entries.isEmpty(), "the deleted tracker's entries must go with it")
        assertEquals(0, world.dao.countDirty())
    }

    @Test
    @DisplayName("nothing dirty after the pull means no upload at all")
    fun noDirtyShortCircuits() = runTest {
        val world = World()

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(1, world.urls.size)
        assertTrue(world.urls.single().contains("/sync/delta"))
    }

    @Test
    @DisplayName("offline is a skip, not a failure, and shows gray")
    fun offlineSkips() = runTest {
        val world = World()
        world.online = false

        val store = world.store()
        val result = store.triggerSync()

        assertFalse(result.success)
        assertEquals(SyncSkipReason.OFFLINE, result.reason)
        assertNull(result.error)
        assertEquals(SyncStatus.GRAY, store.syncStatus.value)
        assertTrue(world.urls.isEmpty())
    }

    @Test
    @DisplayName("a server failure surfaces as an error result and a red dot")
    fun serverErrorGoesRed() = runTest {
        val world = World()
        world.deltaStatus = HttpStatusCode.InternalServerError

        val store = world.store()
        val result = store.triggerSync()

        assertFalse(result.success)
        assertTrue(result.error != null)
        assertEquals(SyncStatus.RED, store.syncStatus.value)
    }

    @Test
    @DisplayName("a delta with no serverTime fails the cycle rather than inventing a watermark")
    fun missingServerTimeFailsTheCycle() = runTest {
        val world = World()
        // A device clock running ahead of the server would advance the
        // watermark past changes the server has not delivered yet, and the next
        // delta's `since` would skip them for good. Failing costs one retry;
        // the watermark stays exactly where it was.
        world.delta = """{"config":[],"days":{},"deletedTrackers":[]}"""

        val result = world.store().triggerSync()

        assertFalse(result.success)
        assertTrue(result.error != null)
        assertNull(world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("an upload response with no serverTime is fine — nothing reads it")
    fun missingUpdateServerTimeIsHarmless() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.delta = delta(serverTime = "s-delta")
        world.update = """{"acceptedTrackers":[{"id":"t1","lastModifiedAt":"s5"}],""" +
            """"acceptedEntries":[],"rejectedTrackers":[],"rejectedEntries":[]}"""

        val result = world.store().triggerSync()

        // The pull is the only half with a watermark to require, so a missing
        // stamp here fails nothing: the cycle settles the upload as usual and
        // the delta's watermark stands.
        assertTrue(result.success)
        assertEquals("s-delta", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
        assertFalse(world.dao.trackers.getValue("t1").isDirty, "the acceptance still settled the row")
    }

    // ---- upload wire shape -----------------------------------------------

    @Test
    @DisplayName("an entry upload literally contains \"value\":null and \"completed\":null")
    fun uploadKeepsExplicitNulls() = runTest {
        val world = World()
        world.seedEntry("2026-08-06", "t1", value = null, completed = null, stamp = null, isDirty = true)
        world.update = update(
            serverTime = "s5",
            acceptedEntries = """{"date":"2026-08-06","trackerId":"t1","lastModifiedAt":"s5"}""",
        )

        world.store().triggerSync()

        // Asserted on the raw body, not a decode-back: the whole risk is the
        // null-omitting serializer dropping these on the way out.
        val body = world.uploadBody()
        assertTrue(body.contains(""""value":null"""), "value was omitted from: $body")
        assertTrue(body.contains(""""completed":null"""), "completed was omitted from: $body")
    }

    @Test
    @DisplayName("a locally deleted tracker uploads _deleted:true carrying its base token")
    fun localDeleteUploadsDeletedFlag() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s1")
        world.dao.softDeleteTrackerAndMarkDirty("t1")
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")

        world.store().triggerSync()

        val body = world.uploadBody()
        assertTrue(body.contains(""""_deleted":true"""), "the delete uploaded as a plain upsert: $body")
        assertTrue(body.contains(""""_baseLastModifiedAt":"s1""""), "the delete lost its base token: $body")
        assertFalse(body.contains(""""lastModifiedAt":"s1""""), "the reserved stamp must not be echoed: $body")
    }

    // ---- pruning ---------------------------------------------------------

    @Test
    @DisplayName("the 7-day prune drops old clean entries but never a dirty one")
    fun pruneSparesDirtyRows() = runTest {
        val world = World()
        world.seedEntry("2026-07-01", "t1", completed = true, stamp = "s0")
        world.seedEntry("2026-07-02", "t1", completed = true, stamp = "s0", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s0")
        // The server settles nothing this cycle, so the old edit is still dirty
        // when the prune runs — the case the PWA would have deleted outright.
        world.update = update(serverTime = "s5")

        world.store().triggerSync()

        assertEquals(setOf("2026-07-02|t1", "2026-08-06|t1"), world.dao.entries.keys)
    }

    @Test
    @DisplayName("a confirmed local delete is cleaned up inside the response transaction, entries and all")
    fun settledDeleteIsCleanedUpAtomically() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s1")
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s1")
        world.dao.softDeleteTrackerAndMarkDirty("t1")
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")

        world.store().triggerSync()

        assertTrue(world.dao.trackers.isEmpty())
        assertTrue(world.dao.entries.isEmpty())
        assertEquals(0, world.dao.countDirty())
        // Cleanup outside the transaction would leave a crash window in which
        // the row is clean, hidden and never uploaded again.
        val calls = world.dao.calls
        val start = calls.indexOf("applyUploadResponse:start")
        val end = calls.indexOf("applyUploadResponse:end")
        val deletion = calls.indexOf("deleteTracker:t1")
        assertTrue(deletion in (start + 1) until end, "delete happened outside the transaction: $calls")
    }

    @Test
    @DisplayName("a tracker deleted after the upload body was built is not swept up by that upload")
    fun deleteAfterPayloadBuildSurvivesThePrune() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s1", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t1", completed = true, stamp = "s1")
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")
        // The acceptance answers the *edit* that was uploaded; the delete came
        // afterwards and its `_deleted` has never reached the server.
        world.onUpload = { world.store().deleteTracker("t1") }

        world.store().triggerSync()

        val row = world.dao.trackers.getValue("t1")
        assertTrue(row.deleted, "the pending delete was lost")
        assertTrue(row.isDirty, "it must stay dirty so the delete actually uploads next cycle")
        assertTrue(world.dao.entries.isNotEmpty(), "entries go only once the delete is settled")
    }

    // ---- normalization ---------------------------------------------------

    @Test
    @DisplayName("a tracker normalized mid-cycle uploads now but only clears next cycle")
    fun normalizedTrackerClearsNextCycle() = runTest {
        val world = World()
        world.delta = delta(
            config = """{"id":"t1","name":"Fast","frequency":"weekly","weeklyDay":3,"lastModifiedAt":"s1"}""",
            serverTime = "s2",
        )
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")

        val store = world.store()
        store.triggerSync()

        val afterFirst = world.dao.trackers.getValue("t1")
        assertEquals(
            listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(3))),
            world.storedTracker("t1").scheduleHistory,
        )
        assertFalse("frequency" in world.storedTracker("t1").extras)
        // It uploaded this cycle — but normalization happened after the
        // generation snapshot, so the snapshot-gated clear skipped it.
        assertTrue(world.uploadBody().contains("scheduleHistory"))
        assertTrue(afterFirst.isDirty, "expected the harmless one-cycle re-upload")
        assertEquals("s5", afterFirst.lastModifiedAt)

        world.delta = delta(serverTime = "s6")
        world.update = update(serverTime = "s7", accepted = """{"id":"t1","lastModifiedAt":"s7"}""")
        store.triggerSync()

        assertFalse(world.dao.trackers.getValue("t1").isDirty, "the second cycle must converge")
        assertEquals(SyncStatus.GREEN, store.syncStatus.value)
    }

    // ---- mutators --------------------------------------------------------

    @Test
    @DisplayName("every mutator marks its row dirty and kicks the scheduler")
    fun mutatorsMarkDirtyAndSchedule() = runTest {
        val world = World()
        val store = world.store()

        store.addTracker(TrackerDto(id = "t1", name = "Water", category = "Habits", type = "simple"))
        assertTrue(world.dao.trackers.getValue("t1").isDirty)
        assertEquals(1L, world.dao.trackers.getValue("t1").dirtyGeneration)
        assertEquals(SyncStatus.RED, store.syncStatus.value)

        store.updateTracker("t1") { it.copy(name = "Hydration") }
        assertEquals("Hydration", world.dao.trackers.getValue("t1").name)
        assertEquals("Hydration", world.storedTracker("t1").name)
        assertEquals(2L, world.dao.trackers.getValue("t1").dirtyGeneration)

        store.updateEntry("2026-08-06", "t1", JsonPrimitive(3), completed = true)
        val entry = world.dao.entries.getValue("2026-08-06|t1")
        assertEquals("3", entry.valueJson)
        assertEquals(true, entry.completed)
        assertTrue(entry.isDirty)

        assertEquals(3, world.scheduledUploads)
    }

    @Test
    @DisplayName("a local delete keeps the row, its JSON and its base token until the server confirms")
    fun localDeleteKeepsBaseToken() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s1")

        world.store().deleteTracker("t1")

        val row = world.dao.trackers.getValue("t1")
        assertTrue(row.deleted)
        assertTrue(row.isDirty)
        assertEquals("s1", row.lastModifiedAt)
        assertEquals("s1", world.storedStamp("t1"))
    }

    @Test
    @DisplayName("ticking a day leaves the entry's value exactly as stored")
    fun setEntryCompletedKeepsTheStoredValue() = runTest {
        val world = World()
        world.seedEntry("2026-08-06", "t1", value = "42", completed = false, stamp = "s1")

        world.store().setEntryCompleted("2026-08-06", "t1", completed = true)

        val entry = world.dao.entries.getValue("2026-08-06|t1")
        // A caller passing a value it read from the screen would undo whatever
        // the last pull delivered; the stored row is the only safe source.
        assertEquals("42", entry.valueJson)
        assertEquals(true, entry.completed)
        assertEquals("s1", entry.lastModifiedAt)
        assertTrue(entry.isDirty)
    }

    @Test
    @DisplayName("a second sync entering while one is in flight is refused, not interleaved")
    fun triggerSyncIsSingleFlight() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(serverTime = "s5", accepted = """{"id":"t1","lastModifiedAt":"s5"}""")
        var reentrant: dev.jtiisto.wellness.core.data.sync.SyncResult? = null
        world.onUpload = { reentrant = world.store().triggerSync() }

        val result = world.store().triggerSync()

        assertTrue(result.success)
        assertEquals(SyncSkipReason.ALREADY_SYNCING, reentrant?.reason)
        assertFalse(reentrant!!.success)
        // Two cycles overlapping would each clear dirty flags the other still needs.
        assertEquals(2, world.urls.size, "the refused cycle must not have hit the network")
    }

    @Test
    @DisplayName("an updated entry keeps the server stamp it was last accepted with")
    fun updateEntryPreservesStamp() = runTest {
        val world = World()
        world.seedEntry("2026-08-06", "t1", completed = false, stamp = "s1")

        world.store().updateEntry("2026-08-06", "t1", null, completed = true)

        val entry = world.dao.entries.getValue("2026-08-06|t1")
        assertEquals("s1", entry.lastModifiedAt)
        assertEquals(true, entry.completed)
        assertNull(entry.valueJson)
    }

    @Test
    @DisplayName("the client id is minted once and reused")
    fun clientIdIsStable() = runTest {
        val world = World()
        val store = world.store()

        assertEquals("fixed-client", store.clientId())
        assertEquals("fixed-client", store.clientId())
        assertEquals("fixed-client", world.dao.meta[JournalDao.KEY_CLIENT_ID])
    }

    // ---- observation -----------------------------------------------------

    @Test
    @DisplayName("the tracker list hides pending deletes and decodes from dataJson")
    fun observeTrackersHidesDeletes() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Visible", stamp = "s1")
        world.seedTracker("t2", name = "Gone", stamp = "s1")
        world.dao.markTrackerDeleted("t2")

        val trackers = world.store().observeTrackers().first()

        assertEquals(listOf("Visible"), trackers.map { it.name })
    }

    @Test
    @DisplayName("the day view keys entries by tracker and preserves the polymorphic value")
    fun observeDayDecodesValues() = runTest {
        val world = World()
        world.seedEntry("2026-08-06", "t1", value = "\"note text\"", completed = null, stamp = "s1")
        world.seedEntry("2026-08-06", "t2", value = "12.5", completed = true, stamp = "s1")
        world.seedEntry("2026-08-05", "t1", value = null, completed = true, stamp = "s1")

        val day = world.store().observeDay("2026-08-06").first()

        assertEquals(setOf("t1", "t2"), day.keys)
        assertEquals("note text", day.getValue("t1").value?.jsonPrimitive?.content)
        assertEquals(12.5, day.getValue("t2").value?.jsonPrimitive?.content?.toDouble())
    }


    // ---- force sync ------------------------------------------------------

    @Test
    @DisplayName("forceSync pulls with no `since` even when a watermark is stored")
    fun forceSyncIgnoresTheStoredWatermark() = runTest {
        val world = World()
        world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME] = "s-old"
        world.delta = delta(serverTime = "s-new")

        val result = world.store().forceSync()

        assertTrue(result is ForceSyncModuleResult.Success)
        assertFalse(world.urls.first().contains("since="), "a force pull must be unconditional: ${world.urls.first()}")
        assertEquals("s-new", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
    }

    @Test
    @DisplayName("triggerSync, by contrast, always sends the stored watermark")
    fun triggerSyncStillSendsTheWatermark() = runTest {
        val world = World()
        world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME] = "s-old"

        world.store().triggerSync()

        // This is why forceSync had to be a separate entry point rather than a
        // flag: there is no honest way for this path to skip the watermark.
        assertTrue(world.urls.first().contains("since=s-old"), world.urls.first())
    }

    @Test
    @DisplayName("a watermark that goes backwards is accepted: replay is idempotent by protocol")
    fun forceSyncToleratesWatermarkRegression() = runTest {
        val world = World()
        world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME] = "s-9"
        // The server stamps wall-clock minus 2s, so a full pull can legitimately
        // answer below the stored value. The cost is re-delivery, not loss.
        world.delta = delta(serverTime = "s-1")

        world.store().forceSync()

        assertEquals("s-1", world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
    }

    @Test
    @DisplayName("offline is a skip with its own reason, not a failure")
    fun forceSyncOffline() = runTest {
        val world = World()
        world.online = false

        val result = world.store().forceSync()

        assertEquals(ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE), result)
        assertTrue(world.urls.isEmpty())
        assertEquals(SyncStatus.GRAY, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("a cycle already running is a busy skip, and the running one is left alone")
    fun forceSyncBusy() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        var nested: ForceSyncModuleResult? = null
        world.onUpload = { nested = world.store().forceSync() }
        world.update = update(accepted = """{"id":"t1","lastModifiedAt":"s1"}""")

        world.store().triggerSync()

        assertEquals(ForceSyncModuleResult.Skipped(ForceSyncSkipReason.BUSY), nested)
    }

    @Test
    @DisplayName("counts come from the response: accepted rows and conflicts, both halves summed")
    fun forceSyncCounts() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.seedEntry("2026-08-06", "t2", completed = true, stamp = "s0", isDirty = true, generation = 1)
        world.update = update(
            accepted = """{"id":"t1","lastModifiedAt":"s1"}""",
            acceptedEntries = """{"date":"2026-08-06","trackerId":"t2","lastModifiedAt":"s1"}""",
            rejected = """{"id":"t3","errorKind":"stale"}""",
        )

        val result = world.store().forceSync()

        assertEquals(
            ForceSyncModuleResult.Success(ForceSyncCounts.Journal(accepted = 2, conflicts = 1)),
            result,
        )
    }

    @Test
    @DisplayName("nothing dirty: a full pull, no upload, and honest zeroes")
    fun forceSyncWithNothingDirty() = runTest {
        val world = World()

        val result = world.store().forceSync()

        assertEquals(1, world.urls.size, "no upload should have been attempted")
        assertEquals(
            ForceSyncModuleResult.Success(ForceSyncCounts.Journal(accepted = 0, conflicts = 0)),
            result,
        )
    }

    @Test
    @DisplayName("a failing pull is a Failed result and a red dot, never a thrown exception")
    fun forceSyncFailure() = runTest {
        val world = World()
        world.deltaStatus = HttpStatusCode.InternalServerError

        val result = world.store().forceSync()

        assertTrue(result is ForceSyncModuleResult.Failed)
        assertEquals(SyncStatus.RED, world.store().syncStatus.value)
    }

    @Test
    @DisplayName("the failure message is the exception class and status, never its message")
    fun forceSyncFailureIsDescribedSafely() = runTest {
        val world = World()
        world.deltaStatus = HttpStatusCode.BadRequest
        // Ktor puts the whole response body in `Throwable.message`; a 422 from
        // FastAPI echoes the rejected input, which is journal content.
        world.delta = """{"detail":"value was SECRET-JOURNAL-VALUE"}"""

        val result = world.store().forceSync() as ForceSyncModuleResult.Failed

        assertFalse(result.message.contains("SECRET-JOURNAL-VALUE"), "leaked a response body: ${result.message}")
        assertTrue(result.message.contains("400"), result.message)
    }

    // ---- the server-switch fence -----------------------------------------

    @Test
    @DisplayName("a delta that lands after the session closes writes NOTHING — no rows, no watermark")
    fun closedGateFencesTheDeltaApply() = runTest {
        val world = World()
        world.delta = delta(
            config = """{"id":"t1","name":"Water","category":"Habits","type":"simple","lastModifiedAt":"s1"}""",
            days = """"2026-08-06":{"t1":{"value":null,"completed":true,"lastModifiedAt":"s1"}}""",
            serverTime = "s2",
        )
        // The switch is confirmed while this response is in flight.
        world.onDelta = { world.session.close() }

        val result = world.store().triggerSync()

        assertTrue(result.success.not(), "the fenced cycle must not report success")
        assertTrue(world.dao.trackers.isEmpty(), "old-server rows reached the wiped database")
        assertTrue(world.dao.entries.isEmpty(), "old-server entries reached the wiped database")
        assertNull(
            world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME],
            "the old server's watermark was restored — the next full pull would be skipped",
        )
    }

    @Test
    @DisplayName("an upload response that lands after the session closes applies no stamps")
    fun closedGateFencesTheUploadApply() = runTest {
        val world = World()
        world.seedTracker("t1", name = "Water", stamp = "s0", isDirty = true, generation = 1)
        world.update = update(accepted = """{"id":"t1","lastModifiedAt":"s-new"}""")
        world.onUpload = { world.session.close() }

        world.store().triggerSync()

        assertEquals("s0", world.storedStamp("t1"), "a post-wipe stamp was applied")
        assertTrue(world.dao.trackers.getValue("t1").isDirty, "the row was cleared against a wiped database")
    }

    @Test
    @DisplayName("forceSync is fenced too")
    fun closedGateFencesForceSync() = runTest {
        val world = World()
        world.delta = delta(
            config = """{"id":"t1","name":"Water","type":"simple","lastModifiedAt":"s1"}""",
            serverTime = "s2",
        )
        world.onDelta = { world.session.close() }

        val result = world.store().forceSync()

        assertTrue(result is ForceSyncModuleResult.Failed)
        assertTrue(world.dao.trackers.isEmpty())
        assertNull(world.dao.meta[JournalDao.KEY_LAST_SERVER_SYNC_TIME])
    }

    @Test
    @DisplayName("awaitQuiescence returns once no cycle holds the sync lock")
    fun quiescenceWaitsOutACycle() = runTest {
        val world = World()
        var quiesced = false
        world.onDelta = {
            // Mid-cycle: the drain must not be able to complete yet.
            quiesced = false
        }

        world.store().triggerSync()
        world.store().awaitQuiescence()
        quiesced = true

        assertTrue(quiesced)
    }

    // ---- harness ---------------------------------------------------------

    private fun delta(
        config: String = "",
        days: String = "",
        deletedTrackers: String = "",
        serverTime: String = "s-delta",
    ) = """{"config":[$config],"days":{$days},"deletedTrackers":[$deletedTrackers],"serverTime":"$serverTime"}"""

    private fun update(
        serverTime: String = "s-update",
        accepted: String = "",
        acceptedEntries: String = "",
        rejected: String = "",
        rejectedEntries: String = "",
    ) = """{"serverTime":"$serverTime","acceptedTrackers":[$accepted],"acceptedEntries":[$acceptedEntries],""" +
        """"rejectedTrackers":[$rejected],"rejectedEntries":[$rejectedEntries]}"""

    /** The fake server, the fake storage, and the knobs the tests turn. */
    private inner class World {
        val dao = FakeJournalDao()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        var online = true
        var delta: String = delta()
        var update: String = update()
        var deltaStatus: HttpStatusCode = HttpStatusCode.OK
        var onUpload: suspend () -> Unit = {}
        var onDelta: suspend () -> Unit = {}
        var scheduledUploads = 0

        /** Open unless a test closes it — the server-switch fence. */
        val session = ServerSessionGate()

        private val debugLog = mockk<DebugLog>(relaxed = true).also {
            every { it.log(any(), any(), any()) } returns Unit
        }

        private val api: JournalApi by lazy {
            val config = ServerConfig("http://localhost:9001/wellness")
            val engine = MockEngine { request ->
                urls += request.url.toString()
                if (request.method == HttpMethod.Post) {
                    bodies += request.body.toByteArray().decodeToString()
                    onUpload()
                    respondJson(update, HttpStatusCode.OK)
                } else {
                    onDelta()
                    respondJson(delta, deltaStatus)
                }
            }
            JournalApi(buildHttpClient(engine, config, json, debugLog), config)
        }

        private val storeInstance: JournalSyncStore by lazy {
            JournalSyncStore(
                dao = dao,
                api = api,
                isOnline = { online },
                session = session,
                json = json,
                // A real (mocked) log, not null: the log lines build JsonObjects
                // eagerly, and a safe call on a null receiver would skip them.
                debugLog = debugLog,
                scheduleUpload = { scheduledUploads++ },
                today = { LocalDate.parse("2026-08-06") },
                newClientId = { "fixed-client" },
            )
        }

        fun store(): JournalSyncStore = storeInstance

        fun uploadBody(): String = bodies.lastOrNull() ?: error("no upload was sent")

        fun storedTracker(id: String): TrackerDto =
            TrackerDtoSerializer.fromJson(json.parseToJsonElement(dao.trackers.getValue(id).dataJson).jsonObject, json)

        fun storedStamp(id: String): String? = storedTracker(id).lastModifiedAt

        fun seedTracker(
            id: String,
            name: String,
            stamp: String?,
            category: String = "Habits",
            isDirty: Boolean = false,
            generation: Long = 0,
        ) {
            val tracker = TrackerDto(id = id, name = name, category = category, type = "simple", lastModifiedAt = stamp)
            dao.trackers[id] = JournalTrackerEntity(
                id = id,
                name = name,
                category = category,
                type = "simple",
                deleted = false,
                lastModifiedAt = stamp,
                dataJson = TrackerDtoSerializer.toJson(tracker, json).toString(),
                isDirty = isDirty,
                dirtyGeneration = generation,
            )
        }

        fun seedEntry(
            date: DateString,
            trackerId: String,
            completed: Boolean?,
            stamp: String?,
            value: String? = null,
            isDirty: Boolean = false,
            generation: Long = 0,
        ) {
            dao.entries["$date|$trackerId"] = JournalEntryEntity(
                date = date,
                trackerId = trackerId,
                valueJson = value,
                completed = completed,
                lastModifiedAt = stamp,
                isDirty = isDirty,
                dirtyGeneration = generation,
            )
        }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

/**
 * In-memory [JournalDao]. Only the SQL primitives are overridden — the
 * composed `@Transaction` methods are inherited, so these tests exercise the
 * real ordering inside them rather than a re-implementation of it.
 */
internal open class FakeJournalDao : JournalDao() {

    val trackers = linkedMapOf<String, JournalTrackerEntity>()
    val entries = linkedMapOf<String, JournalEntryEntity>()
    val meta = linkedMapOf<String, String>()

    /** Ordered write log, so tests can assert what happened *inside* a transaction. */
    val calls = mutableListOf<String>()

    /** Fires just before the response transaction opens — the interleaving window. */
    var beforeApplyUploadResponse: suspend () -> Unit = {}

    override suspend fun applyUploadResponse(
        plan: UploadResponseApply,
        restampTracker: (JournalTrackerEntity, SyncStamp) -> JournalTrackerEntity,
    ) {
        beforeApplyUploadResponse()
        calls += "applyUploadResponse:start"
        super.applyUploadResponse(plan, restampTracker)
        calls += "applyUploadResponse:end"
    }

    /** Bumped on every write so the observe* flows re-emit, as Room's do. */
    private val revision = MutableStateFlow(0L)

    private fun touch() {
        revision.value += 1
    }

    private fun key(date: DateString, trackerId: String) = "$date|$trackerId"

    override suspend fun getMeta(key: String): String? = meta[key]

    override fun observeMeta(key: String): Flow<String?> = revision.map { meta[key] }

    override suspend fun upsertMeta(row: JournalMetaEntity) {
        meta[row.key] = row.value
        touch()
    }

    override suspend fun insertMetaIfAbsent(key: String, value: String) {
        meta.putIfAbsent(key, value)
        touch()
    }

    override suspend fun getTracker(id: String): JournalTrackerEntity? = trackers[id]

    override suspend fun listAllTrackers(): List<JournalTrackerEntity> = trackers.values.toList()

    override suspend fun listDirtyTrackers(): List<JournalTrackerEntity> = trackers.values.filter { it.isDirty }

    override suspend fun snapshotDirtyTrackers(): List<TrackerGeneration> =
        trackers.values.filter { it.isDirty }.map { TrackerGeneration(it.id, it.dirtyGeneration) }

    override suspend fun listSettledDeletedTrackerIds(): List<String> =
        trackers.values.filter { it.deleted && !it.isDirty }.map { it.id }

    override fun observeTrackers(): Flow<List<JournalTrackerEntity>> = revision.map {
        trackers.values.filterNot { it.deleted }.sortedWith(compareBy({ it.category }, { it.name }))
    }

    override suspend fun getEntry(date: DateString, trackerId: String): JournalEntryEntity? =
        entries[key(date, trackerId)]

    override suspend fun listAllEntries(): List<JournalEntryEntity> = entries.values.toList()

    override suspend fun listDirtyEntries(): List<JournalEntryEntity> = entries.values.filter { it.isDirty }

    override suspend fun snapshotDirtyEntries(): List<EntryGeneration> =
        entries.values.filter { it.isDirty }.map { EntryGeneration(it.date, it.trackerId, it.dirtyGeneration) }

    override fun observeDay(date: DateString): Flow<List<JournalEntryEntity>> =
        revision.map { entries.values.filter { it.date == date } }

    override fun observeAllEntries(): Flow<List<JournalEntryEntity>> = revision.map { entries.values.toList() }

    override suspend fun countDirty(): Int =
        trackers.values.count { it.isDirty } + entries.values.count { it.isDirty }

    override suspend fun countDirtyTrackers(): Int = trackers.values.count { it.isDirty }

    override fun observeDirtyTrackerCount(): Flow<Int> = revision.map { trackers.values.count { it.isDirty } }

    override suspend fun upsertTracker(row: JournalTrackerEntity) {
        trackers[row.id] = row
        touch()
    }

    override suspend fun upsertEntry(row: JournalEntryEntity) {
        entries[key(row.date, row.trackerId)] = row
        touch()
    }

    override suspend fun markTrackerDirty(id: String) {
        trackers.computeIfPresent(id) { _, row ->
            row.copy(isDirty = true, dirtyGeneration = row.dirtyGeneration + 1)
        }
        touch()
    }

    override suspend fun markEntryDirty(date: DateString, trackerId: String) {
        entries.computeIfPresent(key(date, trackerId)) { _, row ->
            row.copy(isDirty = true, dirtyGeneration = row.dirtyGeneration + 1)
        }
        touch()
    }

    override suspend fun stampEntry(date: DateString, trackerId: String, stamp: SyncStamp) {
        entries.computeIfPresent(key(date, trackerId)) { _, row -> row.copy(lastModifiedAt = stamp) }
        touch()
    }

    override suspend fun clearTrackerDirty(id: String, generation: Long) {
        trackers.computeIfPresent(id) { _, row ->
            if (row.dirtyGeneration == generation) row.copy(isDirty = false) else row
        }
        touch()
    }

    override suspend fun clearEntryDirty(date: DateString, trackerId: String, generation: Long) {
        entries.computeIfPresent(key(date, trackerId)) { _, row ->
            if (row.dirtyGeneration == generation) row.copy(isDirty = false) else row
        }
        touch()
    }

    override suspend fun markTrackerDeleted(id: String) {
        trackers.computeIfPresent(id) { _, row -> row.copy(deleted = true) }
        touch()
    }

    override suspend fun deleteTracker(id: String) {
        calls += "deleteTracker:$id"
        trackers.remove(id)
        touch()
    }

    override suspend fun deleteEntriesOf(trackerId: String) {
        entries.values.removeIf { it.trackerId == trackerId }
        touch()
    }

    override suspend fun pruneEntriesBefore(windowStart: DateString) {
        entries.values.removeIf { !it.isDirty && it.date < windowStart }
        touch()
    }
}
