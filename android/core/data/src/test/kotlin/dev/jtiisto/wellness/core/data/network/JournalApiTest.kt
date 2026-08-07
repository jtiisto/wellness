package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pins the URL join above everything else: the `/wellness` prefix lives in the
 * base URL and every endpoint path must land *under* it. Ktor resolves a
 * leading-slash path against the host root, which would drop the prefix and
 * turn every call into a 404 that reads like a server fault.
 *
 * Built through [buildHttpClient] so these run against the real client
 * configuration — `expectSuccess`, content negotiation, logging and all.
 */
class JournalApiTest {

    private val requestedUrls = mutableListOf<String>()
    private val loggedMessages = mutableListOf<String>()

    private val debugLog = mockk<DebugLog>().also { log ->
        every { log.log(any(), any(), any()) } answers { loggedMessages += secondArg<String>() }
    }

    private fun api(
        baseUrl: String,
        respondWith: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): JournalApi {
        val config = ServerConfig(baseUrl)
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            respondWith(request)
        }
        return JournalApi(buildHttpClient(engine, config, WellnessJson, debugLog), config)
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    @Test
    @DisplayName("syncStatus appends to the base path, keeping the /wellness prefix")
    fun canonicalUrl() = runTest {
        api(TAILNET_BASE) { json("""{"lastModified":"2026-08-06T10:00:00.123456Z"}""") }.syncStatus()

        assertEquals(listOf("$TAILNET_BASE/api/journal/sync/status"), requestedUrls)
    }

    @Test
    @DisplayName("a trailing slash on the base URL produces the same canonical URL")
    fun trailingSlashNormalized() = runTest {
        api("$TAILNET_BASE/") { json("{}") }.syncStatus()

        assertEquals(listOf("$TAILNET_BASE/api/journal/sync/status"), requestedUrls)
    }

    @Test
    @DisplayName("the status payload decodes and unknown fields are ignored")
    fun decodesStatus() = runTest {
        val status = api(LOCAL_BASE) {
            json("""{"lastModified":"2026-08-06T10:00:00.123456Z","serverBuild":"9","extra":{"a":1}}""")
        }.syncStatus()

        assertEquals("2026-08-06T10:00:00.123456Z", status.lastModified)
    }

    @Test
    @DisplayName("an empty server reports a null lastModified")
    fun decodesEmptyStatus() = runTest {
        assertNull(api(LOCAL_BASE) { json("{}") }.syncStatus().lastModified)
    }

    @Test
    @DisplayName("a non-2xx response surfaces as an exception")
    fun nonSuccessThrows() = runTest {
        val api = api(LOCAL_BASE) { respondError(HttpStatusCode.Forbidden) }

        assertThrows<Exception> { api.syncStatus() }
    }

    @Test
    @DisplayName("malformed JSON surfaces as an exception")
    fun malformedJsonThrows() = runTest {
        val api = api(LOCAL_BASE) { json("not json at all") }

        assertThrows<Exception> { api.syncStatus() }
    }

    @Test
    @DisplayName("HTTP logging records the URL but never the response body")
    fun loggingOmitsBodies() = runTest {
        val secret = "8f3c-private-payload"
        api(LOCAL_BASE) { json("""{"lastModified":"$secret"}""") }.syncStatus()

        val logged = loggedMessages.joinToString("\n")
        assertTrue(logged.contains("/wellness/api/journal/sync/status"), "expected the URL in: $logged")
        assertFalse(logged.contains(secret), "response body leaked into the debug log: $logged")
    }

    @Test
    @DisplayName("HTTP logging pins method + URL + status and excludes headers in both directions")
    fun loggingRecordsMethodAndStatusOnly() = runTest {
        val requestSecret = "tok-request-3f9a"
        val responseSecret = "srv-response-71bc"
        val config = ServerConfig(LOCAL_BASE)
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            respond(
                content = """{"lastModified":"2026-08-06T10:00:00.000000Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "X-Server-Token" to listOf(responseSecret),
                ),
            )
        }
        val client = buildHttpClient(engine, config, WellnessJson, debugLog)

        client.get(config.endpoint(JournalApi.SYNC_STATUS_PATH)) {
            header("X-Client-Token", requestSecret)
        }

        val logged = loggedMessages.joinToString("\n")
        assertTrue(logged.contains("GET", ignoreCase = true), "method missing from: $logged")
        assertTrue(logged.contains("200"), "response status missing from: $logged")
        assertTrue(logged.contains("/wellness/api/journal/sync/status"), "URL missing from: $logged")
        assertFalse(logged.contains(requestSecret), "request header leaked into the debug log: $logged")
        assertFalse(logged.contains(responseSecret), "response header leaked into the debug log: $logged")
    }

    private companion object {
        const val TAILNET_BASE = "https://pop-os.tailexample.ts.net:9443/wellness"
        const val LOCAL_BASE = "http://localhost:9000/wellness"
    }
}
