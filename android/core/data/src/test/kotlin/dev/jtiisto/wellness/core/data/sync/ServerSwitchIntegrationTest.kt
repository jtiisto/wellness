package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.FakeCoachDao
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerSwitchDao
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import dev.jtiisto.wellness.core.data.journal.FakeJournalDao
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.TrendsApi
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * The switch's real guarantee, against writers that are genuinely in flight.
 *
 * The earlier switch tests asserted the *sequence* against synthetic drains and
 * a call-recording DAO, which proved the steps happen in order and nothing
 * about whether a writer can still land afterwards — the question the whole
 * mechanism exists to answer. These use controllable barriers instead: admit a
 * writer, block it mid-write, run the switch across it, release it, and then
 * read the storage the wipe was supposed to have emptied.
 *
 * The wipe here clears the same in-memory maps the stores write into, so a row
 * that survives is a row a test can see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSwitchIntegrationTest {

    @TempDir
    lateinit var cacheRoot: File

    private val json = WellnessJson

    private val debugLog = mockk<DebugLog>(relaxed = true).also {
        every { it.log(any(), any(), any()) } returns Unit
    }

    private fun fixture(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/$path")) {
            "missing golden fixture golden/$path"
        }.use { it.readBytes().decodeToString() }

    /** A journal DAO whose tracker write parks until the test releases it. */
    private class BarrierJournalDao : FakeJournalDao() {
        var barrier: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<Unit>()

        override suspend fun upsertTracker(row: JournalTrackerEntity) {
            barrier?.let {
                reached.complete(Unit)
                it.await()
            }
            super.upsertTracker(row)
        }
    }

    /** A coach DAO whose client-id read parks — the blocker's exact scenario. */
    private class BarrierCoachDao : FakeCoachDao() {
        var clientIdBarrier: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<Unit>()

        override suspend fun getOrCreateClientId(candidate: String): String {
            clientIdBarrier?.let {
                reached.complete(Unit)
                it.await()
            }
            return super.getOrCreateClientId(candidate)
        }
    }

    private class BarrierCacheDao : PayloadCacheDao {
        val rows = linkedMapOf<String, PayloadCacheEntity>()
        var barrier: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<Unit>()

        override suspend fun upsert(entry: PayloadCacheEntity) {
            barrier?.let {
                reached.complete(Unit)
                it.await()
            }
            rows["${entry.module}|${entry.key}"] = entry
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? = rows["$module|$key"]

        override suspend fun delete(module: String, key: String) {
            rows.remove("$module|$key")
        }

        override suspend fun clearModule(module: String) {
            rows.keys.removeIf { it.startsWith("$module|") }
        }
    }

    /**
     * A switch DAO wired to the very maps the stores write into, so "the wipe
     * emptied the table" is a claim about the same storage the writers use.
     */
    private class WiringSwitchDao(
        private val journal: FakeJournalDao,
        private val coach: FakeCoachDao,
        private val cache: BarrierCacheDao,
        private val trendsMeta: MutableMap<String, String>,
        private val profiles: MutableList<ServerProfileEntity>,
    ) : ServerSwitchDao() {
        val logLines = mutableListOf<String>()

        override suspend fun clearJournalTrackers() = journal.trackers.clear()
        override suspend fun clearJournalEntries() = journal.entries.clear()
        override suspend fun clearJournalMeta() = journal.meta.clear()
        override suspend fun clearCoachPlans() = coach.plans.clear()
        override suspend fun clearCoachLogs() = coach.logs.clear()
        override suspend fun clearCoachMeta() = coach.meta.clear()
        override suspend fun clearPayloadCache() = cache.rows.clear()
        override suspend fun clearTrendsMeta() = trendsMeta.clear()

        override suspend fun activate(id: Long) {
            profiles.replaceAll { it.copy(isActive = it.id == id) }
        }

        override suspend fun clearActive() {
            profiles.replaceAll { it.copy(isActive = false) }
        }

        override suspend fun deleteProfile(id: Long) {
            profiles.removeIf { it.id == id }
        }

        override suspend fun insertLogLine(entry: DebugLogEntity) {
            logLines += entry.message
        }
    }

    private inner class World {
        val gate = ServerSessionGate()
        val journalDao = BarrierJournalDao()
        val coachDao = BarrierCoachDao()
        val cacheDao = BarrierCacheDao()
        val trendsMeta = mutableMapOf<String, String>()
        val profiles = mutableListOf(
            ServerProfileEntity(id = 1, nickname = "Laptop", url = "https://laptop/wellness", isActive = true),
            ServerProfileEntity(id = 2, nickname = "Desk", url = "https://desk/wellness", isActive = false),
        )
        val switchDao = WiringSwitchDao(journalDao, coachDao, cacheDao, trendsMeta, profiles)
        val config = ServerConfig("http://localhost:9001/wellness")

        private fun engine(body: String) = MockEngine {
            respond(
                content = body,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val journal = JournalSyncStore(
            dao = journalDao,
            api = JournalApi(buildHttpClient(engine("""{"config":[],"days":{},"deletedTrackers":[],"serverTime":"s"}"""), config, json, debugLog), config),
            isOnline = { true },
            session = gate,
            json = json,
            debugLog = debugLog,
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "journal-client" },
        )

        val coach = CoachSyncStore(
            dao = coachDao,
            api = CoachApi(buildHttpClient(engine("""{"plans":{},"logs":{},"serverTime":"s","deletedPlanDates":[]}"""), config, json, debugLog), config),
            isOnline = { true },
            session = gate,
            json = json,
            debugLog = debugLog,
            clock = { "client-clock" },
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "coach-client" },
        )

        val trends = TrendsRepository(
            // The real golden payload: decoding happens BEFORE the cache write,
            // so a body the DTO rejects would never reach the barrier at all.
            api = TrendsApi(buildHttpClient(engine(fixture("trends/overview.json")), config, json, debugLog), config),
            cacheDao = cacheDao,
            debugLog = debugLog,
            session = gate,
            json = json,
        )

        val switcher = ServerSwitcher(
            gate = gate,
            schedulers = emptyList(),
            drains = listOf(journal::awaitQuiescence, coach::awaitQuiescence),
            stopAnalysis = {},
            backgroundWork = {},
            dao = switchDao,
            sharedFiles = SharedFileStore(File(cacheRoot, "shared"), now = { 1_786_285_805_000L }),
            now = { 1_786_285_805_000L },
        )

        /** Populate all eight wiped tables, so emptiness afterwards means something. */
        suspend fun seedEverything() {
            journalDao.upsertTracker(
                JournalTrackerEntity(
                    id = "t1", name = "Water", category = "Habits", type = "simple",
                    deleted = false, lastModifiedAt = "s1", dataJson = """{"id":"t1"}""",
                    isDirty = true, dirtyGeneration = 2,
                ),
            )
            journalDao.upsertEntry(
                dev.jtiisto.wellness.core.data.db.JournalEntryEntity(
                    date = "2026-08-06", trackerId = "t1", valueJson = "3", completed = true,
                    lastModifiedAt = "s1", isDirty = true, dirtyGeneration = 1,
                ),
            )
            journalDao.meta["lastServerSyncTime"] = "journal-watermark"
            coachDao.plans["2026-08-06"] =
                dev.jtiisto.wellness.core.data.db.CoachPlanEntity("2026-08-06", """{"_lastModified":"p"}""", "p")
            coachDao.logs["2026-08-06"] =
                dev.jtiisto.wellness.core.data.db.CoachLogEntity("2026-08-06", "{}", isDirty = true, dirtyGeneration = 1)
            coachDao.meta["lastServerSyncTime"] = "coach-watermark"
            cacheDao.rows["trends|overview"] = PayloadCacheEntity("trends", "overview", "{}", 1)
            trendsMeta["ui.range"] = "12w"
        }

        fun everythingIsEmpty(): Boolean =
            journalDao.trackers.isEmpty() && journalDao.entries.isEmpty() && journalDao.meta.isEmpty() &&
                coachDao.plans.isEmpty() && coachDao.logs.isEmpty() && coachDao.meta.isEmpty() &&
                cacheDao.rows.isEmpty() && trendsMeta.isEmpty()

        fun populatedTables(): List<String> = buildList {
            if (journalDao.trackers.isNotEmpty()) add("journal_trackers")
            if (journalDao.entries.isNotEmpty()) add("journal_entries")
            if (journalDao.meta.isNotEmpty()) add("journal_meta")
            if (coachDao.plans.isNotEmpty()) add("coach_plans")
            if (coachDao.logs.isNotEmpty()) add("coach_logs")
            if (coachDao.meta.isNotEmpty()) add("coach_meta")
            if (cacheDao.rows.isNotEmpty()) add("payload_cache")
            if (trendsMeta.isNotEmpty()) add("trends_meta")
        }
    }

    private fun switchTest(body: suspend TestScope.(World) -> Unit) = runTest { body(World()) }

    private fun target(world: World) = world.profiles.first { it.id == 2L }

    // ---- blocked writers -------------------------------------------------

    @Test
    @DisplayName("a journal write blocked mid-flight holds the switch back, and its row does not survive")
    fun blockedJournalWriterIsWaitedFor() = switchTest { world ->
        world.seedEverything()
        val barrier = CompletableDeferred<Unit>()
        world.journalDao.barrier = barrier

        val writer = launch {
            world.journal.addTracker(TrackerDto(id = "late", name = "Late", type = "simple"))
        }
        world.journalDao.reached.await()
        assertEquals(1, world.gate.leaseCount, "the writer should be holding a lease")

        val switch = launch { world.switcher.switchTo(target(world), fromNickname = "Laptop") }
        runCurrent()
        assertFalse(world.gate.isOpen, "the switch should have reached close()")
        // The wipe must not have run: close() is waiting on the writer.
        assertFalse(world.everythingIsEmpty(), "the wipe ran while a writer was still in flight")

        barrier.complete(Unit)
        writer.join()
        switch.join()

        assertEquals(
            emptyList<String>(),
            world.populatedTables(),
            "a write admitted before the switch survived the wipe",
        )
    }

    @Test
    @DisplayName("a coach edit parked at clientId() is waited for — the blocker's exact scenario")
    fun blockedCoachMutatorIsWaitedFor() = switchTest { world ->
        world.seedEverything()
        val barrier = CompletableDeferred<Unit>()
        world.coachDao.clientIdBarrier = barrier

        // clientId() is a DAO read and therefore a suspension point. Before the
        // lease covered it, an edit parked here resumed after the wipe and
        // committed a dirty row belonging to the server the user had left.
        val writer = launch {
            world.coach.updateLog("2026-08-06", "ex_1", buildJsonObject { put("reps", 5) })
        }
        world.coachDao.reached.await()
        assertEquals(1, world.gate.leaseCount)

        val switch = launch { world.switcher.switchTo(target(world), fromNickname = "Laptop") }
        runCurrent()
        assertFalse(world.gate.isOpen, "the switch should have reached close()")
        assertFalse(world.everythingIsEmpty(), "the wipe ran while a coach edit was mid-flight")

        barrier.complete(Unit)
        writer.join()
        switch.join()

        assertEquals(emptyList<String>(), world.populatedTables())
    }

    @Test
    @DisplayName("a trends cache write admitted before the close cannot land after the wipe")
    fun blockedTrendsCacheWriteIsWaitedFor() = switchTest { world ->
        world.seedEverything()
        val barrier = CompletableDeferred<Unit>()
        world.cacheDao.barrier = barrier

        // The check-then-write race: requireOpen() passed, the upsert suspended,
        // and the payload landed in the cache the wipe had just emptied.
        val writer = launch { world.trends.overview() }
        world.cacheDao.reached.await()
        assertEquals(1, world.gate.leaseCount)

        val switch = launch { world.switcher.switchTo(target(world), fromNickname = "Laptop") }
        runCurrent()
        assertFalse(world.gate.isOpen, "the switch should have reached close()")
        assertFalse(world.everythingIsEmpty())

        barrier.complete(Unit)
        writer.join()
        switch.join()

        assertEquals(emptyList<String>(), world.populatedTables())
    }

    @Test
    @DisplayName("every one of the eight tables is empty once the switch returns")
    fun allEightTablesAreEmptied() = switchTest { world ->
        world.seedEverything()
        assertEquals(8, world.populatedTables().size + 0, "the seed must cover all eight wiped tables")

        world.switcher.switchTo(target(world), fromNickname = "Laptop")

        assertTrue(world.everythingIsEmpty())
        // And the two that carry the switch itself survive it.
        assertEquals(listOf(2L), world.profiles.filter { it.isActive }.map { it.id })
        assertEquals(listOf("server switch: Laptop → Desk"), world.switchDao.logLines)
    }

    // ---- writers arriving after the close --------------------------------

    @Test
    @DisplayName("a write starting after the switch is refused outright")
    fun postCloseWriteIsRefused() = switchTest { world ->
        world.switcher.switchTo(target(world), fromNickname = "Laptop")

        assertThrows<ServerSessionClosedException> {
            world.journal.addTracker(TrackerDto(id = "after", name = "After", type = "simple"))
        }
        assertTrue(world.journalDao.trackers.isEmpty())
    }

    @Test
    @DisplayName("a coach edit starting after the switch is refused too")
    fun postCloseCoachEditIsRefused() = switchTest { world ->
        world.switcher.switchTo(target(world), fromNickname = "Laptop")

        assertThrows<ServerSessionClosedException> {
            world.coach.updateLog("2026-08-06", "ex_1", buildJsonObject { put("reps", 5) })
        }
        assertTrue(world.coachDao.logs.isEmpty())
    }

    @Test
    @DisplayName("deleting the active profile waits for in-flight writers in the same way")
    fun deleteActiveWaitsForWriters() = switchTest { world ->
        world.seedEverything()
        val barrier = CompletableDeferred<Unit>()
        world.journalDao.barrier = barrier

        val writer = launch {
            world.journal.addTracker(TrackerDto(id = "late", name = "Late", type = "simple"))
        }
        world.journalDao.reached.await()

        val switch = launch { world.switcher.deleteActive(world.profiles.first { it.isActive }) }
        runCurrent()
        assertFalse(world.gate.isOpen)
        assertFalse(world.everythingIsEmpty())

        barrier.complete(Unit)
        writer.join()
        switch.join()

        assertEquals(emptyList<String>(), world.populatedTables())
        assertTrue(world.profiles.none { it.id == 1L }, "the active profile should be gone")
        assertTrue(world.profiles.none { it.isActive }, "and nothing left active")
    }
}
