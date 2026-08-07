package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    // ---- delta pull ------------------------------------------------------

    @Test
    @DisplayName("the first delta omits `since` entirely rather than sending it empty")
    fun deltaOmitsSinceOnFirstSync() = runTest {
        api(LOCAL_BASE) { json(EMPTY_DELTA) }.syncDelta(clientId = "c1", since = null)

        val url = requestedUrls.single()
        assertTrue(url.startsWith("$LOCAL_BASE/api/journal/sync/delta"), "wrong prefix: $url")
        assertTrue(url.contains("client_id=c1"), "missing client id: $url")
        assertFalse(url.contains("since"), "an absent watermark must not appear at all: $url")
    }

    @Test
    @DisplayName("a later delta carries the watermark, URL-encoded")
    fun deltaSendsSince() = runTest {
        api(LOCAL_BASE) { json(EMPTY_DELTA) }.syncDelta(clientId = "c1", since = "2026-08-06T10:00:00.123456Z")

        val url = requestedUrls.single()
        assertTrue(url.contains("since=2026-08-06T10%3A00%3A00.123456Z"), "watermark not encoded: $url")
    }

    @Test
    @DisplayName("the delta asks for no caching in both header dialects")
    fun deltaSendsNoCacheHeaders() = runTest {
        var headers: Headers? = null
        val config = ServerConfig(LOCAL_BASE)
        val engine = MockEngine { request ->
            headers = request.headers
            json(EMPTY_DELTA)
        }
        JournalApi(buildHttpClient(engine, config, WellnessJson, debugLog), config)
            .syncDelta(clientId = "c1", since = null)

        assertEquals("no-store", headers?.get(HttpHeaders.CacheControl))
        assertEquals("no-cache", headers?.get(HttpHeaders.Pragma))
    }

    @Test
    @DisplayName("a delta payload decodes, including a soft-deleted tracker id and the watermark")
    fun deltaDecodes() = runTest {
        val response = api(LOCAL_BASE) {
            json(
                """
                {"config":[{"id":"t1","name":"Water","type":"simple","lastModifiedAt":"s1","unit":"ml"}],
                 "days":{"2026-08-06":{"t1":{"value":3,"completed":true,"lastModifiedAt":"s1"}}},
                 "deletedTrackers":["t9"],"serverTime":"s2","futureField":42}
                """.trimIndent(),
            )
        }.syncDelta(clientId = "c1", since = null)

        assertEquals(listOf("t1"), response.config.map { it.id })
        assertEquals("ml", response.config.single().extras["unit"]?.jsonPrimitive?.content)
        assertEquals(true, response.days.getValue("2026-08-06").getValue("t1").completed)
        assertEquals(listOf("t9"), response.deletedTrackers)
        assertEquals("s2", response.serverTime)
    }

    // ---- upload ----------------------------------------------------------

    @Test
    @DisplayName("the upload POSTs the payload verbatim as JSON to the update endpoint")
    fun updatePostsPayload() = runTest {
        var method: HttpMethod? = null
        var body: String? = null
        val config = ServerConfig(LOCAL_BASE)
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            method = request.method
            body = request.body.toByteArray().decodeToString()
            json("""{"serverTime":"s5"}""")
        }
        val payload = buildJsonObject {
            put("clientId", "c1")
            put("config", buildJsonArray { })
            put(
                "days",
                buildJsonObject {
                    put(
                        "2026-08-06",
                        buildJsonObject {
                            put(
                                "t1",
                                buildJsonObject {
                                    put("value", JsonNull)
                                    put("completed", JsonNull)
                                },
                            )
                        },
                    )
                },
            )
        }

        JournalApi(buildHttpClient(engine, config, WellnessJson, debugLog), config).syncUpdate(payload)

        assertEquals(HttpMethod.Post, method)
        assertEquals(listOf("$LOCAL_BASE/api/journal/sync/update"), requestedUrls)
        // The null-omitting Json configuration must not touch a JsonObject's
        // own entries: the server reads these keys.
        assertEquals(payload.toString(), body)
    }

    @Test
    @DisplayName("an upload response decodes accepted and rejected rows, serverRow included")
    fun updateDecodesResponse() = runTest {
        val response = api(LOCAL_BASE) {
            json(
                """
                {"serverTime":"s5",
                 "acceptedTrackers":[{"id":"t1","lastModifiedAt":"s5"}],
                 "acceptedEntries":[{"date":"2026-08-06","trackerId":"t1","lastModifiedAt":"s5"}],
                 "rejectedTrackers":[{"id":"t2","errorKind":"stale",
                   "serverRow":{"id":"t2","name":"server","deleted":false,"lastModifiedAt":"s9"}}],
                 "rejectedEntries":[{"date":"2026-08-06","trackerId":"t2","errorKind":"missing","serverRow":null}]}
                """.trimIndent(),
            )
        }.syncUpdate(buildJsonObject { put("clientId", "c1") })

        assertEquals("s5", response.serverTime)
        assertEquals("t1", response.acceptedTrackers.single().id)
        assertEquals("2026-08-06", response.acceptedEntries.single().date)
        assertEquals("server", response.rejectedTrackers.single().serverRow?.name)
        assertEquals(false, response.rejectedTrackers.single().serverRow?.deleted)
        assertEquals("missing", response.rejectedEntries.single().errorKind)
        assertNull(response.rejectedEntries.single().serverRow)
    }

    private companion object {
        const val TAILNET_BASE = "https://pop-os.tailexample.ts.net:9443/wellness"
        const val LOCAL_BASE = "http://localhost:9000/wellness"
        const val EMPTY_DELTA = """{"config":[],"days":{},"deletedTrackers":[],"serverTime":"s1"}"""
    }
}
