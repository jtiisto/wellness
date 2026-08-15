package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The Analysis wire surface: six paths, one request body, and the two error
 * statuses the module actually reasons about.
 *
 * Detection of those two is by **status code**, never by matching the message.
 * The 409 and 404 texts are product copy shown to the user verbatim, so they are
 * the strings most likely to be reworded — and a client that branched on them
 * would lose its 409 recovery the day someone fixed a typo server-side.
 */
class AnalysisApiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val debugLog = mockk<DebugLog>(relaxed = true)

    private fun api(
        body: String = "{}",
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = ContentType.Application.Json.toString(),
    ): AnalysisApi {
        val config = ServerConfig(BASE)
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        return AnalysisApi(buildHttpClient(engine, config, WellnessJson, debugLog), config, WellnessJson)
    }

    private suspend fun bodyText(request: HttpRequestData): String =
        request.body.toByteArray().decodeToString()

    private val paths: List<String> get() = requests.map { it.url.toString().removePrefix(BASE) }

    // ---- paths -------------------------------------------------------------

    @Test
    @DisplayName("all six endpoints land under /wellness/api/analysis with the right method")
    fun canonicalPaths() = runTest {
        val config = ServerConfig(BASE)
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = if (request.method == HttpMethod.Post) {
                    """{"id":45,"status":"pending"}"""
                } else {
                    "[]"
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = AnalysisApi(buildHttpClient(engine, config, WellnessJson, debugLog), config, WellnessJson)

        api.queries()
        api.submit("fixture-bare", null)
        api.reports()
        api.pending()
        api.report(41L)
        api.delete(41L)

        assertEquals(
            listOf(
                "/api/analysis/queries",
                "/api/analysis/reports",
                "/api/analysis/reports",
                "/api/analysis/reports/pending",
                "/api/analysis/reports/41",
                "/api/analysis/reports/41",
            ),
            paths,
        )
        assertEquals(
            listOf(
                HttpMethod.Get,
                HttpMethod.Post,
                HttpMethod.Get,
                HttpMethod.Get,
                HttpMethod.Get,
                HttpMethod.Delete,
            ),
            requests.map { it.method },
        )
    }

    @Test
    @DisplayName("no request in this module carries a client_id")
    fun noClientId() = runTest {
        val api = api(body = "[]")
        api.queries()
        api.reports()
        api.pending()
        api.report(7L)
        api.delete(7L)

        assertTrue(
            requests.none { it.url.toString().contains("client_id", ignoreCase = true) },
            "analysis has no client identity; sending one would be a parameter the server may start validating",
        )
    }

    @Test
    @DisplayName("reads ask for no HTTP caching — a cached poll would never finish")
    fun readsAreUncached() = runTest {
        api(body = "[]").reports()

        val request = requests.single()
        assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", request.headers[HttpHeaders.Pragma])
    }

    // ---- submit body -------------------------------------------------------

    @Test
    @DisplayName("submit sends query_id and, when given one, a location")
    fun submitWithLocation() = runTest {
        val api = api(body = """{"id":45,"status":"pending"}""")
        val response = api.submit("fixture-weekly-review", "Fixture City, FS")

        assertEquals(45L, response.id)
        assertEquals("pending", response.status)

        val sent = Json.parseToJsonElement(bodyText(requests.single())) as JsonObject
        assertEquals(setOf("query_id", "location"), sent.keys)
        assertEquals("\"fixture-weekly-review\"", sent["query_id"].toString())
        assertEquals("\"Fixture City, FS\"", sent["location"].toString())
    }

    @Test
    @DisplayName("a null location is omitted from the body rather than sent as null")
    fun submitOmitsNullLocation() = runTest {
        api(body = """{"id":45,"status":"pending"}""").submit("fixture-bare", null)

        val sent = Json.parseToJsonElement(bodyText(requests.single())) as JsonObject
        assertEquals(setOf("query_id"), sent.keys)
    }

    @Test
    @DisplayName("a blank location is omitted too — the server would substitute it into the prompt")
    fun submitOmitsBlankLocation() = runTest {
        api(body = """{"id":45,"status":"pending"}""").submit("fixture-bare", "   ")

        val sent = Json.parseToJsonElement(bodyText(requests.single())) as JsonObject
        assertEquals(setOf("query_id"), sent.keys)
    }

    // ---- typed failures ----------------------------------------------------

    @Test
    @DisplayName("a 409 submit becomes a typed exception carrying the server's exact copy")
    fun submitConflictIsTyped() = runTest {
        val api = api(
            body = """{"detail":"A query is already in progress."}""",
            status = HttpStatusCode.Conflict,
        )

        val failure = assertThrows<AnalysisHttpException> { api.submit("fixture-bare", null) }

        assertEquals(409, failure.status)
        assertEquals("A query is already in progress.", failure.detail)
        assertFalse(failure.isServerError)
    }

    @Test
    @DisplayName("a 404 report becomes a typed exception carrying 'Report not found'")
    fun reportNotFoundIsTyped() = runTest {
        val api = api(body = """{"detail":"Report not found"}""", status = HttpStatusCode.NotFound)

        val failure = assertThrows<AnalysisHttpException> { api.report(999L) }

        assertEquals(404, failure.status)
        assertEquals("Report not found", failure.detail)
    }

    @Test
    @DisplayName("a 409 delete carries the running-report refusal")
    fun deleteConflictIsTyped() = runTest {
        val api = api(
            body = """{"detail":"Report is still running — wait for it to finish (or time out) before deleting."}""",
            status = HttpStatusCode.Conflict,
        )

        val failure = assertThrows<AnalysisHttpException> { api.delete(44L) }

        assertEquals(409, failure.status)
        assertTrue(failure.detail!!.startsWith("Report is still running"))
    }

    @Test
    @DisplayName("a 5xx is typed too, and says so — that is what makes it cache-eligible upstream")
    fun serverErrorIsTyped() = runTest {
        val api = api(body = "upstream exploded", status = HttpStatusCode.InternalServerError)

        val failure = assertThrows<AnalysisHttpException> { api.queries() }

        assertEquals(500, failure.status)
        assertTrue(failure.isServerError)
    }

    @Test
    @DisplayName("a non-JSON error body yields a null detail rather than a parse failure")
    fun nonJsonErrorBodyHasNoDetail() = runTest {
        val api = api(
            body = "<html>502 Bad Gateway</html>",
            status = HttpStatusCode.BadGateway,
            contentType = ContentType.Text.Html.toString(),
        )

        val failure = assertThrows<AnalysisHttpException> { api.reports() }

        assertEquals(502, failure.status)
        assertNull(failure.detail)
    }

    @Test
    @DisplayName("FastAPI's 422 puts a list in `detail`; half-decoding that into a sentence is worse than nothing")
    fun validationErrorShapeHasNoDetail() = runTest {
        val api = api(
            body = """{"detail":[{"loc":["body","query_id"],"msg":"field required","type":"value_error.missing"}]}""",
            status = HttpStatusCode.UnprocessableEntity,
        )

        val failure = assertThrows<AnalysisHttpException> { api.submit("", null) }

        assertEquals(422, failure.status)
        assertNull(failure.detail)
    }

    @Test
    @DisplayName("a blank detail string is treated as no detail at all")
    fun blankDetailIsNull() = runTest {
        val api = api(body = """{"detail":"   "}""", status = HttpStatusCode.NotFound)

        assertNull(assertThrows<AnalysisHttpException> { api.report(1L) }.detail)
    }

    private companion object {
        const val BASE = "http://fixture.invalid/wellness"
    }
}
