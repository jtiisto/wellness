package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.db.CoachDao
import dev.jtiisto.wellness.core.data.db.DebugLogDao
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.journal.FakeJournalDao
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.coach.FakeCoachDao
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The debug dump is shareable; the data behind it is not.
 *
 * **Permitted**: HTTP method, URL and status, module tags, counts, error class
 * names, opaque ids (tracker UUIDs, report ids) and calendar dates.
 * **Forbidden**: tracker names, entry values, notes, report bodies and queries,
 * headers, and request or response bodies.
 *
 * A grep over the source would not be enough to prove this and was not how the
 * one real violation was found. Existing log lines legitimately carry tracker
 * ids and `date|trackerId` keys, so "does this line mention a tracker" is the
 * wrong question; the right one is whether forbidden *content* can reach
 * [DebugLog.dump]. So these push synthetic forbidden strings through the real
 * sync flows and read the assembled dump.
 */
class DebugLogPrivacyTest {

    private val json = WellnessJson

    /** Strings that must never survive to a dump, whatever route they take. */
    private val forbidden = listOf(
        "FORBIDDEN-TRACKER-NAME",
        "FORBIDDEN-ENTRY-VALUE",
        "FORBIDDEN-NOTE-TEXT",
        "FORBIDDEN-EXERCISE-CONTENT",
        "FORBIDDEN-SERVER-DETAIL",
    )

    private class FakeDebugLogDao : DebugLogDao() {
        private val rows = mutableListOf<DebugLogEntity>()
        private var nextId = 1L
        private val revision = MutableStateFlow(0L)

        override suspend fun insert(entry: DebugLogEntity) {
            rows += entry.copy(id = nextId++)
            revision.value += 1
        }

        override suspend fun deleteExpired(cutoff: Long) {
            rows.removeIf { it.ts <= cutoff }
        }

        override suspend fun trimToCap(max: Int) {
            while (rows.size > max) rows.removeAt(0)
        }

        override fun observeAll(): Flow<List<DebugLogEntity>> = revision.map { rows.sortedByDescending { it.id } }

        override suspend fun listSince(cutoff: Long): List<DebugLogEntity> =
            rows.filter { it.ts > cutoff }.sortedBy { it.id }
    }

    private fun respondJson(
        scope: io.ktor.client.engine.mock.MockRequestHandleScope,
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = scope.respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun assertNothingForbiddenIn(dump: String) {
        val leaked = forbidden.filter { dump.contains(it) }
        assertTrue(leaked.isEmpty(), "the shareable dump leaked $leaked:\n$dump")
    }

    @Test
    @DisplayName("a journal sync carrying forbidden content logs ids and counts, never the content")
    fun journalSyncLeaksNothing() = runTest {
        val logDao = FakeDebugLogDao()
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), json) { 1_000L }
        val dao = FakeJournalDao()
        val config = ServerConfig("http://localhost:9001/wellness")

        val delta = """{"config":[{"id":"tracker-uuid-1","name":"FORBIDDEN-TRACKER-NAME",""" +
            """"category":"FORBIDDEN-NOTE-TEXT","type":"note","lastModifiedAt":"s1"}],""" +
            """"days":{"2026-08-06":{"tracker-uuid-1":""" +
            """{"value":"FORBIDDEN-ENTRY-VALUE","completed":true,"lastModifiedAt":"s1"}}},""" +
            """"deletedTrackers":[],"serverTime":"s2"}"""
        val update = """{"serverTime":"s3","acceptedTrackers":[],"acceptedEntries":[],""" +
            """"rejectedTrackers":[{"id":"tracker-uuid-1","errorKind":"stale",""" +
            """"serverRow":{"id":"tracker-uuid-1","name":"FORBIDDEN-TRACKER-NAME","type":"note"}}],""" +
            """"rejectedEntries":[]}"""

        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) respondJson(this, update) else respondJson(this, delta)
        }
        val store = JournalSyncStore(
            dao = dao,
            api = JournalApi(buildHttpClient(engine, config, json, debugLog), config),
            isOnline = { true },
            session = ServerSessionGate(),
            json = json,
            debugLog = debugLog,
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "client-uuid" },
        )

        store.addTracker(
            dev.jtiisto.wellness.core.data.journal.TrackerDto(
                id = "tracker-uuid-2",
                name = "FORBIDDEN-TRACKER-NAME",
                type = "note",
            ),
        )
        store.updateEntry("2026-08-06", "tracker-uuid-2", JsonPrimitive("FORBIDDEN-ENTRY-VALUE"), completed = true)
        store.triggerSync()

        val dump = debugLog.dump()
        assertNothingForbiddenIn(dump)
        // The permitted classes are still there, which is what makes the log useful.
        assertTrue(dump.contains("tracker-uuid"), "opaque ids are permitted and expected:\n$dump")
        assertTrue(dump.contains("journal-sync"))
    }

    @Test
    @DisplayName("a coach sync carrying forbidden exercise content logs dates and counts only")
    fun coachSyncLeaksNothing() = runTest {
        val logDao = FakeDebugLogDao()
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), json) { 1_000L }
        val dao = FakeCoachDao()
        val config = ServerConfig("http://localhost:9001/wellness")

        val pull = """{"plans":{"2026-08-06":{"_lastModified":"p1",""" +
            """"notes":"FORBIDDEN-EXERCISE-CONTENT"}},"logs":{},"serverTime":"s-pull","deletedPlanDates":[]}"""

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("plans-version") -> respondJson(this, """{"version":"v1"}""")
                request.method == HttpMethod.Post ->
                    respondJson(this, """{"success":true,"results":{},"serverTime":"s-post"}""")
                else -> respondJson(this, pull)
            }
        }
        val store = CoachSyncStore(
            dao = dao,
            api = CoachApi(buildHttpClient(engine, config, json, debugLog), config),
            isOnline = { true },
            session = ServerSessionGate(),
            json = json,
            debugLog = debugLog,
            clock = { "client-clock" },
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "client-uuid" },
        )

        store.updateLog(
            "2026-08-06",
            "ex_1",
            buildJsonObject { put("notes", "FORBIDDEN-EXERCISE-CONTENT") },
        )
        store.triggerSync()

        val dump = debugLog.dump()
        assertNothingForbiddenIn(dump)
        assertTrue(dump.contains("coach-sync"))
    }

    @Test
    @DisplayName("a server error body never reaches the dump, even though Ktor puts it in the message")
    fun serverErrorBodyIsNotLogged() = runTest {
        val logDao = FakeDebugLogDao()
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), json) { 1_000L }
        val config = ServerConfig("http://localhost:9001/wellness")

        // This is the real leak this rule was written for. Ktor builds a
        // ResponseException message as `Bad response: … Text: "<body>"`, and a
        // FastAPI 422 echoes the input it rejected — which is journal content.
        val engine = MockEngine {
            respondJson(this, """{"detail":"invalid value FORBIDDEN-SERVER-DETAIL"}""", HttpStatusCode.BadRequest)
        }
        val store = JournalSyncStore(
            dao = FakeJournalDao(),
            api = JournalApi(buildHttpClient(engine, config, json, debugLog), config),
            isOnline = { true },
            session = ServerSessionGate(),
            json = json,
            debugLog = debugLog,
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "client-uuid" },
        )

        store.triggerSync()

        val dump = debugLog.dump()
        assertNothingForbiddenIn(dump)
        assertTrue(dump.contains("sync error"), "the failure must still be visible:\n$dump")
    }

    @Test
    @DisplayName("a force-sync failure is described the same safe way")
    fun forceSyncErrorBodyIsNotLogged() = runTest {
        val logDao = FakeDebugLogDao()
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), json) { 1_000L }
        val config = ServerConfig("http://localhost:9001/wellness")
        val engine = MockEngine {
            respondJson(this, """{"detail":"invalid value FORBIDDEN-SERVER-DETAIL"}""", HttpStatusCode.BadRequest)
        }
        val store = JournalSyncStore(
            dao = FakeJournalDao(),
            api = JournalApi(buildHttpClient(engine, config, json, debugLog), config),
            isOnline = { true },
            session = ServerSessionGate(),
            json = json,
            debugLog = debugLog,
            today = { LocalDate.parse("2026-08-06") },
            newClientId = { "client-uuid" },
        )

        store.forceSync()

        assertNothingForbiddenIn(debugLog.dump())
    }

    @Test
    @DisplayName("the HTTP log line is method, URL and status — never a body")
    fun httpLoggingIsMethodUrlStatus() = runTest {
        val logDao = FakeDebugLogDao()
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), json) { 1_000L }
        val config = ServerConfig("http://localhost:9001/wellness")
        val engine = MockEngine {
            respondJson(this, """{"lastModified":"FORBIDDEN-SERVER-DETAIL"}""")
        }

        JournalApi(buildHttpClient(engine, config, json, debugLog), config).syncStatus()

        val dump = debugLog.dump()
        assertNothingForbiddenIn(dump)
        assertTrue(dump.contains("/api/journal/sync/status"), "the URL is permitted and useful:\n$dump")
    }

    @Test
    @DisplayName("the boundary line names servers, which are configuration rather than data")
    fun switchBoundaryLineIsPermitted() {
        val entry = DebugLogEntity(ts = 1_000L, tag = ServerSwitcher.TAG, message = "server switch: A → B")

        val line = DebugLogLogic.formatDumpLine(entry)

        assertTrue(line.contains("server switch: A → B"))
        assertFalse(forbidden.any { line.contains(it) })
    }
}
