package dev.jtiisto.wellness.core.data.analysis

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.network.AnalysisApi
import dev.jtiisto.wellness.core.data.network.AnalysisHttpException
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Network-first reads and the failure matrix behind them.
 *
 * Two rules carry most of the weight. A report is cached **only once its status
 * is terminal** — a pending row on disk would come back from the next launch
 * looking permanently unfinished, and `UNKNOWN` is not terminal however final it
 * looks. And a 4xx never falls back: this module's 404s and 409s are the whole
 * recovery mechanism, and answering one with an old payload would swallow it.
 */
class AnalysisRepositoryTest {

    private val loggedMessages = mutableListOf<String>()
    private val debugLog = mockk<DebugLog>().also { mock ->
        every { mock.log(any(), any(), any()) } answers { loggedMessages += secondArg<String>() }
    }

    private var now = 5_000_000L

    private class FakeCacheDao : PayloadCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PayloadCacheEntity>()
        val deleted = mutableListOf<String>()
        var upsertFailure: Throwable? = null
        var readFailure: Throwable? = null
        var deleteFailure: Throwable? = null

        override suspend fun upsert(entry: PayloadCacheEntity) {
            upsertFailure?.let { throw it }
            rows[entry.module to entry.key] = entry
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? {
            readFailure?.let { throw it }
            return rows[module to key]
        }

        override suspend fun delete(module: String, key: String) {
            deleted += key
            deleteFailure?.let { throw it }
            rows.remove(module to key)
        }

        override suspend fun clearModule(module: String) {
            rows.keys.removeAll { it.first == module }
        }
    }

    private val dao = FakeCacheDao()

    /** [handle] answers every request; anything it throws reaches the client. */
    private fun repository(handle: () -> String): AnalysisRepository =
        build(MockEngine { respondJson(handle()) })

    private fun failingRepository(status: HttpStatusCode, body: String = "{}"): AnalysisRepository =
        build(MockEngine { respondJson(body, status) })

    private fun throwingRepository(error: Throwable): AnalysisRepository =
        build(MockEngine { throw error })

    private fun build(engine: MockEngine): AnalysisRepository {
        val config = ServerConfig(BASE)
        return AnalysisRepository(
            session = ServerSessionGate(),
            api = AnalysisApi(buildHttpClient(engine, config, WellnessJson, mockk(relaxed = true)), config, WellnessJson),
            cacheDao = dao,
            debugLog = debugLog,
            json = WellnessJson,
            clock = { now },
        )
    }

    private fun seed(key: String, payload: String, fetchedAt: Long = 1L) {
        dao.rows[AnalysisRepository.MODULE to key] =
            PayloadCacheEntity(AnalysisRepository.MODULE, key, payload, fetchedAt)
    }

    private fun cached(key: String) = dao.rows[AnalysisRepository.MODULE to key]

    // ---- queries: network only ---------------------------------------------

    @Test
    @DisplayName("queries decode off the wire and are never cached — PWA parity")
    fun queriesAreNeverCached() = runTest {
        val queries = repository { QUERIES_BODY }.queries()

        assertEquals(listOf("fixture-a", "fixture-b"), queries.map { it.id })
        assertTrue(dao.rows.isEmpty(), "an empty query list offline is honest; a stale one is not")
    }

    @Test
    @DisplayName("a queries 404 propagates typed — that is how the module reads 'disabled server-side'")
    fun queriesNotFoundPropagates() = runTest {
        val failure = assertThrows<AnalysisHttpException> {
            failingRepository(HttpStatusCode.NotFound, """{"detail":"Not Found"}""").queries()
        }

        assertEquals(404, failure.status)
    }

    @Test
    @DisplayName("a queries transport failure propagates: there is no cache to fall back to")
    fun queriesOfflinePropagates() = runTest {
        assertThrows<IOException> { throwingRepository(IOException("down")).queries() }
    }

    // ---- pending and submit: network only ----------------------------------

    @Test
    @DisplayName("pending decodes full rows and writes nothing to the cache")
    fun pendingIsNetworkOnly() = runTest {
        val pending = repository { PENDING_BODY }.pending()

        assertEquals(listOf(44L), pending.map { it.id })
        assertEquals(ReportStatus.RUNNING, pending.single().reportStatus)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    @DisplayName("submit returns the 201 envelope")
    fun submitReturnsEnvelope() = runTest {
        val response = repository { """{"id":45,"status":"pending"}""" }.submit("fixture-a", null)

        assertEquals(45L, response.id)
    }

    @Test
    @DisplayName("a submit 409 reaches the caller typed, with the server's copy intact")
    fun submitConflictPropagates() = runTest {
        val failure = assertThrows<AnalysisHttpException> {
            failingRepository(HttpStatusCode.Conflict, """{"detail":"A query is already in progress."}""")
                .submit("fixture-a", null)
        }

        assertEquals(409, failure.status)
        assertEquals("A query is already in progress.", failure.detail)
    }

    // ---- history -----------------------------------------------------------

    @Test
    @DisplayName("a fresh history is returned and written through, byte for byte")
    fun historyWritesThrough() = runTest {
        val history = repository { HISTORY_BODY }.history()

        assertEquals(listOf(44L, 41L), history.map { it.id })
        val row = requireNotNull(cached(AnalysisRepository.KEY_HISTORY))
        assertEquals(HISTORY_BODY, row.payloadJson)
        assertEquals(now, row.fetchedAt)
    }

    @Test
    @DisplayName("offline, the cached history is served silently")
    fun historyFallsBackOffline() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, HISTORY_BODY, fetchedAt = 42L)

        val history = throwingRepository(IOException("down")).history()

        assertEquals(listOf(44L, 41L), history.map { it.id })
    }

    @Test
    @DisplayName("a 5xx falls back to the cache; the request was fine, the server was not")
    fun historyFallsBackOnServerError() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, HISTORY_BODY)

        val history = failingRepository(HttpStatusCode.InternalServerError).history()

        assertEquals(2, history.size)
    }

    @Test
    @DisplayName("a 4xx propagates even with a cache present")
    fun historyFourHundredPropagates() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, HISTORY_BODY)

        val failure = assertThrows<AnalysisHttpException> {
            failingRepository(HttpStatusCode.BadRequest).history()
        }

        assertEquals(400, failure.status)
    }

    @Test
    @DisplayName("offline with nothing cached rethrows the original failure, not a cache miss")
    fun historyWithoutCacheRethrows() = runTest {
        val failure = assertThrows<IOException> { throwingRepository(IOException("down")).history() }

        assertEquals("down", failure.message)
    }

    @Test
    @DisplayName("a cached copy this build cannot decode is kept, and the original error is what surfaces")
    fun corruptedHistoryCacheRethrowsOriginal() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, """{"not":"a list"}""")

        assertThrows<IOException> { throwingRepository(IOException("down")).history() }

        assertNotNull(cached(AnalysisRepository.KEY_HISTORY), "the row stays: a later build may read it")
        assertTrue(loggedMessages.any { it.contains("failed to decode") })
    }

    @Test
    @DisplayName("a cache write that fails costs the write, never the answer")
    fun historyCacheWriteFailureIsSwallowed() = runTest {
        dao.upsertFailure = IllegalStateException("disk full")

        val history = repository { HISTORY_BODY }.history()

        assertEquals(2, history.size)
        assertTrue(loggedMessages.any { it.contains("cache write failed") })
    }

    @Test
    @DisplayName("a fresh history prunes report bodies that have fallen out of the window")
    fun historyPrunesSupersededReports() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, OLD_HISTORY_BODY)
        seed(AnalysisRepository.reportKey(41L), REPORT_COMPLETED_BODY)
        seed(AnalysisRepository.reportKey(9L), REPORT_COMPLETED_BODY)
        seed(AnalysisRepository.reportKey(8L), REPORT_COMPLETED_BODY)

        repository { HISTORY_BODY }.history()

        // 44 and 41 are in the fresh list; 9 and 8 dropped out of the newest 50.
        assertEquals(setOf("report_9", "report_8"), dao.deleted.toSet())
        assertNotNull(cached(AnalysisRepository.reportKey(41L)), "an id still in the window survives")
        assertNull(cached(AnalysisRepository.reportKey(9L)))
    }

    @Test
    @DisplayName("the polled report's id is in every fresh list by construction, so its row is never pruned")
    fun historyPruneSparesTheActiveReport() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, OLD_HISTORY_BODY)
        seed(AnalysisRepository.reportKey(44L), REPORT_RUNNING_BODY)

        repository { HISTORY_BODY }.history()

        assertNotNull(cached(AnalysisRepository.reportKey(44L)))
        assertFalse(dao.deleted.contains("report_44"))
    }

    @Test
    @DisplayName("a history served from the cache prunes nothing — it is not evidence of anything")
    fun cachedHistoryDoesNotPrune() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, OLD_HISTORY_BODY)
        seed(AnalysisRepository.reportKey(9L), REPORT_COMPLETED_BODY)

        throwingRepository(IOException("down")).history()

        assertTrue(dao.deleted.isEmpty())
        assertNotNull(cached(AnalysisRepository.reportKey(9L)))
    }

    @Test
    @DisplayName("rows this session deleted are filtered out of a fetch that predates the delete")
    fun historyFiltersTombstonedRows() = runTest {
        val history = repository { HISTORY_BODY }.history(deletedIds = setOf(41L))

        assertEquals(listOf(44L), history.map { it.id })
        val row = requireNotNull(cached(AnalysisRepository.KEY_HISTORY))
        assertFalse(
            row.payloadJson.contains("\"id\":41"),
            "a deleted row must not reach disk either, or the next launch restores it",
        )
    }

    @Test
    @DisplayName("with nothing to filter, the raw body is cached exactly as it arrived")
    fun historyCachesVerbatimWhenNothingFiltered() = runTest {
        repository { HISTORY_BODY }.history(deletedIds = setOf(999L))

        assertEquals(HISTORY_BODY, cached(AnalysisRepository.KEY_HISTORY)?.payloadJson)
    }

    @Test
    @DisplayName("the tombstone filter applies to a cached fallback too")
    fun cachedHistoryIsAlsoFiltered() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, HISTORY_BODY)

        val history = throwingRepository(IOException("down")).history(deletedIds = setOf(44L))

        assertEquals(listOf(41L), history.map { it.id })
    }

    @Test
    @DisplayName("an unreadable previous history costs the prune, not the fetch")
    fun unreadablePreviousHistorySkipsPrune() = runTest {
        seed(AnalysisRepository.KEY_HISTORY, "not json at all")

        val history = repository { HISTORY_BODY }.history()

        assertEquals(2, history.size)
        assertTrue(dao.deleted.isEmpty())
        assertTrue(loggedMessages.any { it.contains("unreadable for pruning") })
    }

    // ---- report ------------------------------------------------------------

    @Test
    @DisplayName("a fetched report comes back with the bytes behind it, and writes nothing")
    fun reportReturnsRawAndCachesNothing() = runTest {
        val fetched = repository { REPORT_COMPLETED_BODY }.report(41L)

        assertEquals(ReportStatus.COMPLETED, fetched.detail.reportStatus)
        assertEquals(REPORT_COMPLETED_BODY, fetched.raw)
        assertTrue(
            dao.rows.isEmpty(),
            "caching is the store's call to make; deciding it here re-created reports the store had rejected",
        )
    }

    @Test
    @DisplayName("that holds for every status — the repository has no opinion about which are worth keeping")
    fun reportNeverCachesAnyStatus() = runTest {
        repository { REPORT_FAILED_BODY }.report(43L)
        repository { REPORT_RUNNING_BODY }.report(44L)
        repository { REPORT_UNKNOWN_BODY }.report(46L)

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    @DisplayName("a report the store accepts is written on request, verbatim")
    fun acceptedReportIsCached() = runTest {
        repository { REPORT_COMPLETED_BODY }.cacheAcceptedReport(41L, REPORT_COMPLETED_BODY)

        val row = requireNotNull(cached(AnalysisRepository.reportKey(41L)))
        assertEquals(REPORT_COMPLETED_BODY, row.payloadJson)
        assertEquals(now, row.fetchedAt)
    }

    @Test
    @DisplayName("a cache write that fails costs the write, never the caller")
    fun acceptedReportWriteIsBestEffort() = runTest {
        dao.upsertFailure = IllegalStateException("disk full")

        repository { REPORT_COMPLETED_BODY }.cacheAcceptedReport(41L, REPORT_COMPLETED_BODY)

        assertTrue(loggedMessages.any { it.contains("cache write failed") })
    }

    @Test
    @DisplayName("offline, a cached report is served — and carries no bytes to write back")
    fun reportFallsBackOffline() = runTest {
        seed(AnalysisRepository.reportKey(41L), REPORT_COMPLETED_BODY)

        val fetched = throwingRepository(IOException("down")).report(41L)

        assertEquals(41L, fetched.detail.id)
        assertNull(
            fetched.raw,
            "a cached copy has nothing to re-write; doing so would restore a row a delete had just removed",
        )
    }

    @Test
    @DisplayName("offline with nothing cached rethrows, so the caller can leave the view alone")
    fun reportWithoutCacheRethrows() = runTest {
        assertThrows<IOException> { throwingRepository(IOException("down")).report(41L) }
    }

    @Test
    @DisplayName("a report 404 propagates typed even with a cached copy sitting there")
    fun reportNotFoundPropagates() = runTest {
        seed(AnalysisRepository.reportKey(41L), REPORT_COMPLETED_BODY)

        val failure = assertThrows<AnalysisHttpException> {
            failingRepository(HttpStatusCode.NotFound, """{"detail":"Report not found"}""").report(41L)
        }

        assertEquals(404, failure.status)
        assertEquals("Report not found", failure.detail)
    }

    @Test
    @DisplayName("a cache read that throws does not become the error the user sees")
    fun reportCacheReadFailureRethrowsOriginal() = runTest {
        dao.readFailure = IllegalStateException("database locked")

        assertThrows<IOException> { throwingRepository(IOException("down")).report(41L) }

        assertTrue(loggedMessages.any { it.contains("cache read failed") })
    }

    // ---- delete ------------------------------------------------------------

    @Test
    @DisplayName("a delete drops the report body and rewrites the history from the caller's list")
    fun deleteTidiesTheCache() = runTest {
        seed(AnalysisRepository.reportKey(41L), REPORT_COMPLETED_BODY)
        seed(AnalysisRepository.KEY_HISTORY, HISTORY_BODY)
        val remaining = WellnessJson.decodeFromString(
            ListSerializer(ReportSummaryDto.serializer()),
            HISTORY_BODY,
        ).filter { it.id != 41L }

        repository { """{"deleted":true}""" }.delete(41L, remaining)

        assertNull(cached(AnalysisRepository.reportKey(41L)))
        val rewritten = requireNotNull(cached(AnalysisRepository.KEY_HISTORY))
        assertFalse(rewritten.payloadJson.contains("\"id\":41"))
        assertTrue(rewritten.payloadJson.contains("\"id\":44"))
    }

    @Test
    @DisplayName("cache maintenance that fails does not fail the delete — the report is gone either way")
    fun deleteCacheMaintenanceIsBestEffort() = runTest {
        dao.deleteFailure = IllegalStateException("database locked")
        dao.upsertFailure = IllegalStateException("disk full")

        repository { """{"deleted":true}""" }.delete(41L, emptyList())

        assertTrue(loggedMessages.any { it.contains("cache delete failed") })
        assertTrue(loggedMessages.any { it.contains("cache write failed") })
    }

    @Test
    @DisplayName("a 409 delete propagates typed and touches no cache row")
    fun deleteConflictPropagates() = runTest {
        seed(AnalysisRepository.reportKey(44L), REPORT_RUNNING_BODY)

        val failure = assertThrows<AnalysisHttpException> {
            failingRepository(
                HttpStatusCode.Conflict,
                """{"detail":"Report is still running — wait for it to finish (or time out) before deleting."}""",
            ).delete(44L, emptyList())
        }

        assertEquals(409, failure.status)
        assertNotNull(cached(AnalysisRepository.reportKey(44L)))
        assertTrue(dao.deleted.isEmpty())
    }

    private companion object {
        const val BASE = "http://fixture.invalid/wellness"

        const val QUERIES_BODY =
            """[{"id":"fixture-a","label":"Fixture A","description":"d"},""" +
                """{"id":"fixture-b","label":"Fixture B","description":"d","accepts_location":true}]"""

        const val HISTORY_BODY =
            """[{"id":44,"query_id":"fixture-a","query_label":"Fixture A","status":"running",""" +
                """"created_at":"2031-03-04T12:00:00Z","completed_at":null},""" +
                """{"id":41,"query_id":"fixture-b","query_label":"Fixture B","status":"completed",""" +
                """"created_at":"2031-03-04T09:15:00Z","completed_at":"2031-03-04T09:18:00Z"}]"""

        const val OLD_HISTORY_BODY =
            """[{"id":44,"query_id":"fixture-a","query_label":"Fixture A","status":"running",""" +
                """"created_at":"2031-03-04T12:00:00Z","completed_at":null},""" +
                """{"id":41,"query_id":"fixture-b","query_label":"Fixture B","status":"completed",""" +
                """"created_at":"2031-03-04T09:15:00Z","completed_at":"2031-03-04T09:18:00Z"},""" +
                """{"id":9,"query_id":"fixture-b","query_label":"Fixture B","status":"completed",""" +
                """"created_at":"2031-03-01T09:15:00Z","completed_at":"2031-03-01T09:18:00Z"},""" +
                """{"id":8,"query_id":"fixture-b","query_label":"Fixture B","status":"failed",""" +
                """"created_at":"2031-02-28T09:15:00Z","completed_at":"2031-02-28T09:18:00Z"}]"""

        const val REPORT_COMPLETED_BODY =
            """{"id":41,"query_id":"fixture-b","query_label":"Fixture B","status":"completed",""" +
                """"response_markdown":"# Fixture","created_at":"2031-03-04T09:15:00Z",""" +
                """"completed_at":"2031-03-04T09:18:00Z","error_message":null}"""

        const val REPORT_FAILED_BODY =
            """{"id":43,"query_id":"fixture-b","query_label":"Fixture B","status":"failed",""" +
                """"response_markdown":null,"created_at":"2031-03-04T11:30:00Z",""" +
                """"completed_at":"2031-03-04T11:32:00Z","error_message":"Reaped: fixture"}"""

        const val REPORT_RUNNING_BODY =
            """{"id":44,"query_id":"fixture-a","query_label":"Fixture A","status":"running",""" +
                """"response_markdown":null,"created_at":"2031-03-04T12:00:00Z",""" +
                """"completed_at":null,"error_message":null}"""

        const val REPORT_UNKNOWN_BODY =
            """{"id":46,"query_id":"fixture-a","query_label":"Fixture A","status":"queued",""" +
                """"response_markdown":null,"created_at":"2031-03-04T12:00:00Z",""" +
                """"completed_at":null,"error_message":null}"""

        const val PENDING_BODY = "[" + REPORT_RUNNING_BODY + "]"
    }
}

/** Every fixture body in this suite is JSON; the status is the only thing that varies. */
private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
