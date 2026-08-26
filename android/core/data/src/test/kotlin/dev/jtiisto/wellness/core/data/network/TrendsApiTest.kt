package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The Trends wire surface: twelve URLs, three query shapes, and one rule about
 * when a parameter is sent at all.
 *
 * An empty `start=` is not the same request as no `start` — the server reads
 * the absence as "no lower bound" and an empty string as a malformed date — so
 * omission is asserted rather than assumed.
 */
class TrendsApiTest {

    private val requestedUrls = mutableListOf<String>()
    private val requests = mutableListOf<HttpRequestData>()
    private val debugLog = mockk<DebugLog>(relaxed = true)

    private fun api(body: String = "{}", status: HttpStatusCode = HttpStatusCode.OK): TrendsApi {
        val config = ServerConfig(BASE)
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            requests += request
            if (status.value >= 400) {
                respondError(status)
            } else {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        return TrendsApi(buildHttpClient(engine, config, WellnessJson, debugLog), config)
    }

    // ---- paths -------------------------------------------------------------

    @Test
    @DisplayName("every endpoint lands under the /wellness/api/trends prefix")
    fun canonicalPaths() = runTest {
        val api = api()
        api.overview()
        api.weight(null, END)
        api.strengthExercises(null, END)
        api.strengthExercise("fixture-press", null, END)
        api.strengthVolume(null, END)
        api.cardio(null, END)
        api.journalTrackers()
        api.journalTracker("fixture-tracker", null, END)
        api.healthRecovery(null, END)
        api.healthSleep(null, END)
        api.healthComposition(END)
        api.healthLabs(END)

        val paths = requestedUrls.map { it.substringBefore('?').removePrefix(BASE) }
        assertEquals(
            listOf(
                "/api/trends/overview",
                "/api/trends/weight",
                "/api/trends/strength/exercises",
                "/api/trends/strength/exercise/fixture-press",
                "/api/trends/strength/volume",
                "/api/trends/cardio",
                "/api/trends/journal/trackers",
                "/api/trends/journal/tracker/fixture-tracker",
                "/api/trends/health/recovery",
                "/api/trends/health/sleep",
                "/api/trends/health/composition",
                "/api/trends/health/labs",
            ),
            paths,
        )
        assertTrue(requests.all { it.method == HttpMethod.Get }, "trends only ever reads")
    }

    @Test
    @DisplayName("a trailing slash on the base URL produces the same canonical URL")
    fun trailingSlashNormalized() = runTest {
        val config = ServerConfig("$BASE/")
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        TrendsApi(buildHttpClient(engine, config, WellnessJson, debugLog), config).overview()

        assertEquals("$BASE/api/trends/overview", requestedUrls.single())
    }

    // ---- query shapes ------------------------------------------------------

    @Test
    @DisplayName("a bounded range sends both start and end")
    fun boundedRangeSendsBothParams() = runTest {
        api().weight(START, END)

        val url = requestedUrls.single()
        assertTrue(url.contains("start=$START"), url)
        assertTrue(url.contains("end=$END"), url)
    }

    @Test
    @DisplayName("the All range omits start entirely rather than sending it empty")
    fun allRangeOmitsStart() = runTest {
        api().cardio(null, END)

        val url = requestedUrls.single()
        assertFalse(url.contains("start"), "an absent lower bound must not appear at all: $url")
        assertTrue(url.contains("end=$END"), url)
    }

    @Test
    @DisplayName("/overview and /journal/trackers send no parameters at all")
    fun parameterlessEndpoints() = runTest {
        val api = api()
        api.overview()
        api.journalTrackers()

        assertEquals("$BASE/api/trends/overview", requestedUrls[0])
        assertEquals("$BASE/api/trends/journal/trackers", requestedUrls[1])
    }

    @Test
    @DisplayName("composition and labs take end only — they are range-immune by design")
    fun endOnlyEndpoints() = runTest {
        val api = api()
        api.healthComposition(END)
        api.healthLabs(END)

        for (url in requestedUrls) {
            assertTrue(url.endsWith("?end=$END"), "expected an end-only query: $url")
        }
    }

    @Test
    @DisplayName("the client always sends end, so the window is the DEVICE's today")
    fun endIsAlwaysSent() = runTest {
        val api = api()
        api.weight(START, END)
        api.strengthExercises(null, END)
        api.strengthExercise("fixture-press", START, END)
        api.strengthVolume(null, END)
        api.cardio(START, END)
        api.journalTracker("fixture-tracker", null, END)
        api.healthRecovery(START, END)
        api.healthSleep(START, END)

        assertTrue(requestedUrls.all { it.contains("end=$END") }, requestedUrls.toString())
    }

    @Test
    @DisplayName("sleep takes the same start?/end shape as recovery, All range included")
    fun sleepQueryShape() = runTest {
        val api = api()
        api.healthSleep(START, END)
        api.healthSleep(null, END)

        assertTrue(requestedUrls[0].contains("start=$START"), requestedUrls[0])
        assertTrue(requestedUrls[0].contains("end=$END"), requestedUrls[0])
        // The server's ledger replays from history's start regardless, so an
        // absent lower bound clips only what comes back — but it still has to
        // be absent rather than empty, or the date pattern rejects it.
        assertTrue(requestedUrls[1].endsWith("?end=$END"), requestedUrls[1])
    }

    // ---- encoding ----------------------------------------------------------

    @Test
    @DisplayName("a slug needing encoding is encoded in the path")
    fun slugIsUrlEncoded() = runTest {
        api().strengthExercise("fixture press/incline #2", null, END)

        val url = requestedUrls.single()
        assertTrue(url.contains("fixture%20press%2Fincline%20%232"), "slug not encoded: $url")
        // The extra segment would silently address a different endpoint.
        assertFalse(url.contains("exercise/fixture press/incline"), url)
    }

    @Test
    @DisplayName("a tracker id needing encoding is encoded in the path")
    fun trackerIdIsUrlEncoded() = runTest {
        api().journalTracker("fixture/tracker?x", null, END)

        assertTrue(requestedUrls.single().contains("fixture%2Ftracker%3Fx"), requestedUrls.single())
    }

    // ---- responses ---------------------------------------------------------

    @Test
    @DisplayName("the body comes back as raw text, so the cache stores exactly what arrived")
    fun bodyIsReturnedVerbatim() = runTest {
        val payload = """{"available":true,"series":[],"unknown":1}"""

        assertEquals(payload, api(payload).weight(null, END))
    }

    @Test
    @DisplayName("both no-cache header dialects go out — an HTTP cache would defeat the stale badge")
    fun noCacheHeaders() = runTest {
        api().overview()

        assertEquals("no-store", requests.single().headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", requests.single().headers[HttpHeaders.Pragma])
    }

    @Test
    @DisplayName("a non-2xx response surfaces as an exception rather than a body")
    fun nonSuccessThrows() = runTest {
        val api = api(status = HttpStatusCode.InternalServerError)

        assertThrows<Exception> { api.overview() }
    }

    private companion object {
        const val BASE = "http://localhost:9001/wellness"
        const val START = "2026-05-16"
        const val END = "2026-08-08"
    }
}
