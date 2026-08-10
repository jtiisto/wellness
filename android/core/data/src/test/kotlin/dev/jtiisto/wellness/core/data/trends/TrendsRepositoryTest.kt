package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.TrendsApi
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
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
 * The network-first fetch and its failure matrix.
 *
 * The rules being pinned all cut the same way: a fetch that worked must not be
 * failed by a broken cache, and a fetch that failed must not be quietly turned
 * into a success by a cache that happens to hold something. Everything in
 * between — which failures may fall back, which may not — is the difference
 * between "you are offline" and "that chart is wrong and nobody said so".
 */
class TrendsRepositoryTest {

    private val loggedMessages = mutableListOf<String>()
    private val debugLog = mockk<DebugLog>().also { mock ->
        every { mock.log(any(), any(), any()) } answers { loggedMessages += secondArg<String>() }
    }

    private var now = 1_000_000L

    private class FakeCacheDao : PayloadCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PayloadCacheEntity>()
        var upsertFailure: Throwable? = null
        var readFailure: Throwable? = null
        var reads = 0
        var writes = 0

        override suspend fun upsert(entry: PayloadCacheEntity) {
            writes += 1
            upsertFailure?.let { throw it }
            rows[entry.module to entry.key] = entry
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? {
            reads += 1
            readFailure?.let { throw it }
            return rows[module to key]
        }

        override suspend fun delete(module: String, key: String) {
            rows.remove(module to key)
        }

        override suspend fun clearModule(module: String) {
            rows.keys.removeAll { it.first == module }
        }
    }

    private val dao = FakeCacheDao()

    /** [handle] decides what the server does; anything it throws reaches the client. */
    private fun repository(handle: () -> String): TrendsRepository {
        val config = ServerConfig(BASE)
        val engine = MockEngine {
            respond(
                content = handle(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return build(engine, config)
    }

    private fun failingRepository(status: HttpStatusCode): TrendsRepository {
        val config = ServerConfig(BASE)
        return build(MockEngine { respondError(status) }, config)
    }

    private fun throwingRepository(error: Throwable): TrendsRepository {
        val config = ServerConfig(BASE)
        return build(MockEngine { throw error }, config)
    }

    private fun build(engine: MockEngine, config: ServerConfig) = TrendsRepository(
        session = ServerSessionGate(),
        api = TrendsApi(buildHttpClient(engine, config, WellnessJson, mockk(relaxed = true)), config),
        cacheDao = dao,
        debugLog = debugLog,
        json = WellnessJson,
        clock = { now },
    )

    private fun seedCache(key: String, payload: String, fetchedAt: Long) {
        dao.rows[TrendsRepository.MODULE to key] = PayloadCacheEntity(
            module = TrendsRepository.MODULE,
            key = key,
            payloadJson = payload,
            fetchedAt = fetchedAt,
        )
    }

    // ---- row 1: success ----------------------------------------------------

    @Test
    @DisplayName("a fresh fetch decodes, caches the raw body, and reports itself fresh")
    fun freshFetchCaches() = runTest {
        val result = repository { WEIGHT }.weight(START, END, "12w")

        assertNull(result.staleFetchedAt, "a network result is never stale")
        assertEquals(1, result.value.series.size)
        val cached = dao.rows.getValue(TrendsRepository.MODULE to "weight:12w")
        // The RAW body, not a re-encode: a server field this build ignores must
        // still be there for the build that understands it.
        assertEquals(WEIGHT, cached.payloadJson)
        assertEquals(now, cached.fetchedAt)
        assertEquals(0, dao.reads, "a successful fetch never reads the cache")
    }

    @Test
    @DisplayName("a cache write that fails still returns the fresh result")
    fun upsertFailureDoesNotFailTheFetch() = runTest {
        dao.upsertFailure = IllegalStateException("disk full")

        val result = repository { WEIGHT }.weight(START, END, "12w")

        assertEquals(1, result.value.series.size)
        assertNull(result.staleFetchedAt)
        assertTrue(
            loggedMessages.any { it.contains("cache write failed for weight:12w") },
            "a sick cache must be visible in the log: $loggedMessages",
        )
    }

    // ---- row 2: fresh-body decode failure -----------------------------------

    @Test
    @DisplayName("an undecodable fresh body propagates and leaves the old cached copy alone")
    fun freshDecodeFailurePreservesTheCache() = runTest {
        seedCache("weight:12w", WEIGHT, 500L)

        assertThrows<SerializationException> {
            repository { """{"available":true,"series":[{"date":"2026-07-01"}]}""" }
                .weight(START, END, "12w")
        }

        assertEquals(0, dao.writes, "a body we cannot read must not overwrite one we can")
        assertEquals(0, dao.reads, "a decode failure is not an offline fallback")
        assertEquals(WEIGHT, dao.rows.getValue(TrendsRepository.MODULE to "weight:12w").payloadJson)
    }

    // ---- row 3: network / 5xx fallback --------------------------------------

    @Test
    @DisplayName("a network failure serves the cached copy, stamped with ITS age")
    fun networkFailureServesCache() = runTest {
        seedCache("weight:12w", WEIGHT, 500L)

        val result = throwingRepository(IOException("offline")).weight(START, END, "12w")

        assertEquals(1, result.value.series.size)
        // The stamp is when the copy was stored, never when it was served.
        assertEquals(500L, result.staleFetchedAt)
    }

    @Test
    @DisplayName("a 5xx falls back the same way a dropped connection does")
    fun serverErrorServesCache() = runTest {
        seedCache("cardio:4w", CARDIO, 700L)

        val result = failingRepository(HttpStatusCode.BadGateway).cardio(START, END, "4w")

        assertEquals(700L, result.staleFetchedAt)
        assertTrue(result.value.weeks.isEmpty())
    }

    @Test
    @DisplayName("a network failure with no cached copy rethrows rather than inventing one")
    fun networkFailureWithoutCacheRethrows() = runTest {
        val error = assertThrows<Exception> {
            throwingRepository(IOException("offline")).weight(START, END, "12w")
        }

        assertTrue(error is IOException || error.cause is IOException, "lost the original error: $error")
        assertEquals(1, dao.reads)
    }

    @Test
    @DisplayName("a cached copy this build cannot decode rethrows the ORIGINAL network error")
    fun undecodableCacheRethrowsTheNetworkError() = runTest {
        seedCache("weight:12w", "{not json", 500L)

        val error = assertThrows<Exception> {
            throwingRepository(IOException("offline")).weight(START, END, "12w")
        }

        // The user is offline; that is the fact worth surfacing, not the shape
        // of a row they will never see.
        assertFalse(error is SerializationException, "surfaced the cache's problem instead: $error")
        assertTrue(
            loggedMessages.any { it.contains("cached payload for weight:12w failed to decode") },
            loggedMessages.toString(),
        )
        // The row stays: a later build may well read it.
        assertNotNull(dao.rows[TrendsRepository.MODULE to "weight:12w"])
    }

    @Test
    @DisplayName("a cache READ that throws is treated as a miss, and the network error still wins")
    fun cacheReadFailureRethrowsTheNetworkError() = runTest {
        dao.readFailure = IllegalStateException("database closed")

        val error = assertThrows<Exception> {
            throwingRepository(IOException("offline")).weight(START, END, "12w")
        }

        assertFalse(error is IllegalStateException, "the cache's failure masked the network's: $error")
        assertTrue(
            loggedMessages.any { it.contains("cache read failed for weight:12w") },
            loggedMessages.toString(),
        )
    }

    // ---- row 4: 4xx and everything else --------------------------------------

    @Test
    @DisplayName("a 4xx never falls back — a wrong request must not be answered with old data")
    fun clientErrorNeverReadsTheCache() = runTest {
        seedCache("strength/fixture-press:12w", EXERCISE_DETAIL, 500L)

        assertThrows<ClientRequestException> {
            failingRepository(HttpStatusCode.NotFound)
                .strengthExercise("fixture-press", START, END, "12w")
        }

        assertEquals(0, dao.reads, "a 404 is not an offline state")
    }

    @Test
    @DisplayName("a 5xx is a ServerResponseException, which is what makes the fallback type-based")
    fun serverErrorTypeIsWhatDrivesTheFallback() = runTest {
        val error = assertThrows<Exception> {
            failingRepository(HttpStatusCode.InternalServerError).overview()
        }

        assertTrue(error is ServerResponseException, "classification would fall back to messages: $error")
    }

    // ---- row 5: cancellation --------------------------------------------------

    @Test
    @DisplayName("cancellation propagates untouched and never becomes a cache read")
    fun cancellationIsNeverClassified() = runTest {
        seedCache("weight:12w", WEIGHT, 500L)

        assertThrows<CancellationException> {
            throwingRepository(CancellationException("superseded")).weight(START, END, "12w")
        }

        assertEquals(0, dao.reads, "a cancelled request must not resurrect as a cache hit")
    }

    @Test
    @DisplayName("cancellation during the cache WRITE propagates rather than returning a result")
    fun cancellationDuringUpsertPropagates() = runTest {
        dao.upsertFailure = CancellationException("superseded")

        // Swallowing this alongside the ordinary write failures would let a
        // cancelled load return normally and write state for a request whose
        // screen is gone.
        assertThrows<CancellationException> { repository { WEIGHT }.weight(START, END, "12w") }

        assertTrue(loggedMessages.isEmpty(), "a cancellation is not a sick cache: $loggedMessages")
    }

    @Test
    @DisplayName("cancellation during the cache READ propagates rather than passing as a miss")
    fun cancellationDuringCacheReadPropagates() = runTest {
        dao.readFailure = CancellationException("superseded")

        assertThrows<CancellationException> {
            throwingRepository(IOException("offline")).weight(START, END, "12w")
        }

        assertTrue(loggedMessages.isEmpty(), "a cancellation is not a broken cache: $loggedMessages")
    }

    // ---- staleness across a shared key ----------------------------------------

    @Test
    @DisplayName("Overview and Health share weight:{range}, and each holds its own freshness")
    fun sharedWeightKeyKeepsPerCallerFreshness() = runTest {
        // Overview fetches it fresh; the row lands in the cache.
        val fresh = repository { WEIGHT }.weight(START, END, "12w")
        assertNull(fresh.staleFetchedAt)

        // Health then loses the network and is served the same row — and knows
        // that IT is holding a cached copy, which a shared staleness map could
        // never have expressed for both callers at once.
        now += 60_000
        val stale = throwingRepository(IOException("offline")).weight(START, END, "12w")
        assertEquals(1_000_000L, stale.staleFetchedAt)
        assertEquals(fresh.value, stale.value)
    }

    @Test
    @DisplayName("a re-fetch of the same key overwrites the row and clears its staleness")
    fun refetchClearsStaleness() = runTest {
        seedCache("weight:12w", WEIGHT, 500L)

        val stale = throwingRepository(IOException("offline")).weight(START, END, "12w")
        assertEquals(500L, stale.staleFetchedAt)

        now = 2_000_000L
        val fresh = repository { WEIGHT }.weight(START, END, "12w")
        assertNull(fresh.staleFetchedAt)
        assertEquals(2_000_000L, dao.rows.getValue(TrendsRepository.MODULE to "weight:12w").fetchedAt)
    }

    // ---- cache-key inventory ---------------------------------------------------

    @Test
    @DisplayName("every method writes the PWA's cache key, slug and id raw")
    fun cacheKeyInventory() = runTest {
        val api = repository { EMPTY_FOR_EVERY_SHAPE }

        api.overview()
        api.weight(START, END, "4w")
        api.strengthExercises(START, END, "4w")
        api.strengthExercise("fixture press/2", START, END, "4w")
        api.strengthVolume(START, END, "4w")
        api.cardio(START, END, "4w")
        api.journalTrackers()
        api.journalTracker("fixture/tracker", START, END, "4w")
        api.healthRecovery(START, END, "4w")
        api.healthComposition(END)
        api.healthLabs(END)

        assertEquals(
            listOf(
                "cardio:4w",
                "health/composition",
                "health/labs",
                "health/recovery:4w",
                "journal/fixture/tracker:4w",
                "journal/trackers",
                "overview",
                "strength/exercises:4w",
                "strength/fixture press/2:4w",
                "volume:4w",
                "weight:4w",
            ),
            dao.rows.keys.map { it.second }.sorted(),
        )
        assertTrue(dao.rows.keys.all { it.first == "trends" })
    }

    @Test
    @DisplayName("composition and labs keys carry no date, so they overwrite daily")
    fun dateLessKeys() = runTest {
        repository { COMPOSITION }.healthComposition("2026-08-08")
        now += 86_400_000
        repository { COMPOSITION }.healthComposition("2026-08-09")

        assertEquals(1, dao.rows.count { it.key.second == "health/composition" })
    }

    // ---- error display ----------------------------------------------------------

    @Test
    @DisplayName("describeFetchError names the status, the network, or the shape — never a body")
    fun errorDescriptions() = runTest {
        val http = assertThrows<Exception> {
            failingRepository(HttpStatusCode.NotFound).overview()
        }
        assertEquals("HTTP 404", describeFetchError(http))

        val offline = assertThrows<Exception> { throwingRepository(IOException("no route")).overview() }
        assertEquals("Offline — check connection", describeFetchError(offline))

        assertEquals(
            "Unexpected server response",
            describeFetchError(SerializationException("bad shape")),
        )
        assertEquals("Unexpected error", describeFetchError(IllegalStateException("who knows")))
    }

    private companion object {
        const val BASE = "http://localhost:9001/wellness"
        const val START = "2026-05-16"
        const val END = "2026-08-08"
        const val WEIGHT = """{"available":true,"series":[{"date":"2026-07-01","kg":80}]}"""
        const val CARDIO = """{"weeks":[],"steady_sessions":[]}"""
        const val COMPOSITION = """{"available":false,"scans":[]}"""
        const val EXERCISE_DETAIL =
            """{"exercise":{"slug":"fixture-press","name":"Fixture Press","equipment":null,""" +
                """"category":null},"unit":"kg","sessions":[]}"""

        /**
         * One body that decodes as every payload on the list — the key inventory
         * is about *which key* each method writes, not about what came back.
         */
        const val EMPTY_FOR_EVERY_SHAPE =
            """{"zone2":{"this_week_min":0,"last_week_min":null,"four_week_avg_min":null,"sparkline":[]},""" +
                """"tonnage":{"this_week_kg":0,"last_week_kg":null,"four_week_avg_kg":null,"sparkline":[]},""" +
                """"adherence_focus":[],"prs":{"count_30d":0,"latest":null},""" +
                """"available":true,"series":[],"exercises":[],"weeks":[],"steady_sessions":[],""" +
                """"trackers":[],"days":[],"scans":[],"panels":[],""" +
                """"exercise":{"slug":"s","name":"n","equipment":null,"category":null},"unit":"kg",""" +
                """"sessions":[],"tracker":{"id":"i","name":null,"type":null,"unit":null,""" +
                """"polarity":null,"actionable":false,"has_target":false,"first_entry":"2026-01-01",""" +
                """"last_entry":"2026-01-01"},"values":[],"target_segments":[],""" +
                """"weekly_adherence":[],"streaks":{"current":0,"best":0}}"""
    }
}
