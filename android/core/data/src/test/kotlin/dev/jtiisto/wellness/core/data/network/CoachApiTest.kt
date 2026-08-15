package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The coach wire contract.
 *
 * Coach uses **one path for both directions**, so the tests pin method as well
 * as URL — a GET that went out as a POST would upload nothing and look like an
 * empty server. Built through [buildHttpClient] so these run against the real
 * client configuration.
 */
class CoachApiTest {

    private val requestedUrls = mutableListOf<String>()
    private val requests = mutableListOf<HttpRequestData>()

    private val debugLog = mockk<DebugLog>(relaxed = true).also {
        every { it.log(any(), any(), any()) } returns Unit
    }

    private fun api(
        baseUrl: String = LOCAL_BASE,
        respondWith: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CoachApi {
        val config = ServerConfig(baseUrl)
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            requests += request
            respondWith(request)
        }
        return CoachApi(buildHttpClient(engine, config, WellnessJson, debugLog), config)
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    // ---- URLs ------------------------------------------------------------

    @Test
    @DisplayName("every endpoint lands under the /wellness prefix")
    fun canonicalUrls() = runTest {
        api(TAILNET_BASE) { json(EMPTY_SYNC) }.sync(clientId = "c1", lastSyncTime = null)
        api(TAILNET_BASE) { json("""{"version":"v1"}""") }.plansVersion()

        assertTrue(
            requestedUrls[0].startsWith("$TAILNET_BASE/api/coach/sync"),
            "wrong prefix: ${requestedUrls[0]}",
        )
        assertEquals("$TAILNET_BASE/api/coach/plans-version", requestedUrls[1])
    }

    @Test
    @DisplayName("a trailing slash on the base URL produces the same canonical URL")
    fun trailingSlashNormalized() = runTest {
        api("$TAILNET_BASE/") { json("""{"version":"v1"}""") }.plansVersion()

        assertEquals("$TAILNET_BASE/api/coach/plans-version", requestedUrls.single())
    }

    @Test
    @DisplayName("the first pull omits last_sync_time entirely rather than sending it empty")
    fun firstPullOmitsWatermark() = runTest {
        api { json(EMPTY_SYNC) }.sync(clientId = "c1", lastSyncTime = null)

        val url = requestedUrls.single()
        assertEquals(HttpMethod.Get, requests.single().method)
        assertTrue(url.contains("client_id=c1"), "missing client id: $url")
        assertFalse(url.contains("last_sync_time"), "an absent watermark must not appear at all: $url")
    }

    @Test
    @DisplayName("a later pull carries the watermark, URL-encoded")
    fun laterPullSendsWatermark() = runTest {
        api { json(EMPTY_SYNC) }.sync(clientId = "c1", lastSyncTime = "2026-08-07T10:00:00.123456Z")

        assertTrue(
            requestedUrls.single().contains("last_sync_time=2026-08-07T10%3A00%3A00.123456Z"),
            "watermark not encoded: ${requestedUrls.single()}",
        )
    }

    @Test
    @DisplayName("both GETs ask for no caching in either header dialect")
    fun bothGetsSendNoCacheHeaders() = runTest {
        api { json(EMPTY_SYNC) }.sync(clientId = "c1", lastSyncTime = null)
        api { json("""{"version":"v1"}""") }.plansVersion()

        for (request in requests) {
            assertEquals("no-store", request.headers[HttpHeaders.CacheControl], "on ${request.url}")
            assertEquals("no-cache", request.headers[HttpHeaders.Pragma], "on ${request.url}")
        }
    }

    // ---- upload ----------------------------------------------------------

    @Test
    @DisplayName("the upload POSTs {clientId, logs} verbatim as JSON to the same sync path")
    fun uploadBodyIsExact() = runTest {
        var body: String? = null
        val logs = WellnessJson.parseToJsonElement(
            """{"2030-01-03":{"session_feedback":{},"extra_zone2":{"duration_min":30,""" +
                """"_lastModified":"t1","_baseLastModifiedAt":"t1"},"_baseLastModifiedAt":"day-tok"}}""",
        ).jsonObject

        api {
            body = it.body.toByteArray().decodeToString()
            json("""{"success":true,"results":{},"serverTime":"s1"}""")
        }.syncPost(
            clientId = "c1",
            logs = logs.mapValues { (_, value) -> value.jsonObject },
        )

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$LOCAL_BASE/api/coach/sync", requestedUrls.single())
        assertEquals(ContentType.Application.Json, request.body.contentType)
        // Asserted on the raw bytes, not a decode-back: key ORDER is the
        // contract here. The stored day is uploaded exactly as `withBaseTokens`
        // built it, and the golden request fixture is compared byte for byte.
        assertEquals("""{"clientId":"c1","logs":$logs}""", body)
    }

    @Test
    @DisplayName("an empty logs map still serializes as an object, not as null or an array")
    fun emptyUploadIsAnObject() = runTest {
        var body: String? = null
        api {
            body = it.body.toByteArray().decodeToString()
            json("""{"success":true,"results":{},"serverTime":"s1"}""")
        }.syncPost(clientId = "c1", logs = emptyMap())

        assertEquals("""{"clientId":"c1","logs":{}}""", body)
    }

    // ---- decoding --------------------------------------------------------

    @Test
    @DisplayName("a pull payload decodes: plans, logs, watermark, window start and plan tombstones")
    fun syncResponseDecodes() = runTest {
        val response = api {
            json(
                """
                {"plans":{"2030-01-03":{"session_id":1,"day_name":"Push","_lastModified":"p1"}},
                 "logs":{"2030-01-03":{"session_feedback":{},"extra_zone2":{"duration_min":30}}},
                 "serverTime":"s2","earliestDate":"2026-06-08","deletedPlanDates":["2026-08-01"],
                 "futureField":42}
                """.trimIndent(),
            )
        }.sync(clientId = "c1", lastSyncTime = null)

        assertEquals("Push", response.plans.getValue("2030-01-03")["day_name"]?.jsonPrimitive?.content)
        assertEquals(
            30.0,
            response.logs.getValue("2030-01-03").getValue("extra_zone2")
                .jsonObject["duration_min"]?.jsonPrimitive?.content?.toDouble(),
        )
        assertEquals("s2", response.serverTime)
        assertEquals("2026-06-08", response.earliestDate)
        assertEquals(listOf("2026-08-01"), response.deletedPlanDates)
    }

    @Test
    @DisplayName("an empty pull decodes to empty maps rather than failing")
    fun emptySyncResponseDecodes() = runTest {
        val response = api { json("{}") }.sync(clientId = "c1", lastSyncTime = null)

        assertTrue(response.plans.isEmpty())
        assertTrue(response.logs.isEmpty())
        assertNull(response.serverTime)
        assertNull(response.earliestDate)
        assertTrue(response.deletedPlanDates.isEmpty())
    }

    @Test
    @DisplayName("an upload response decodes the merged day per date")
    fun postResponseDecodes() = runTest {
        val response = api {
            json(
                """{"success":true,"results":{"2030-01-03":{"session_feedback":{},""" +
                    """"extra_zone2":{"duration_min":30,"_lastModified":"t2"},"_lastModified":"d2"}},""" +
                    """"serverTime":"s5"}""",
            )
        }.syncPost(clientId = "c1", logs = emptyMap())

        assertTrue(response.success)
        assertEquals("s5", response.serverTime)
        val day: JsonObject = response.results.getValue("2030-01-03")
        assertEquals("d2", day["_lastModified"]?.jsonPrimitive?.content)
        assertEquals("t2", day.getValue("extra_zone2").jsonObject["_lastModified"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("the plans-version probe decodes, null included")
    fun plansVersionDecodes() = runTest {
        assertEquals("v9", api { json("""{"version":"v9"}""") }.plansVersion().version)
        assertNull(api { json("""{"version":null}""") }.plansVersion().version)
        assertNull(api { json("{}") }.plansVersion().version)
    }

    // ---- workout hooks -----------------------------------------------------

    @Test
    @DisplayName("the hook endpoints address one session by method and path")
    fun hookEndpoints() = runTest {
        api { json(EMPTY_STATUS) }.workoutStatus(42)
        api { respond(content = "", status = HttpStatusCode.OK) }.fireWorkoutHook(42, HookAction.START)
        api { respond(content = "", status = HttpStatusCode.OK) }.undoWorkoutHook(42, HookAction.END)

        assertEquals("$LOCAL_BASE/api/coach/workout/42/status", requestedUrls[0])
        assertEquals(HttpMethod.Get, requests[0].method)
        assertEquals("$LOCAL_BASE/api/coach/workout/42/start", requestedUrls[1])
        assertEquals(HttpMethod.Post, requests[1].method)
        assertEquals("$LOCAL_BASE/api/coach/workout/42/end", requestedUrls[2])
        assertEquals(HttpMethod.Delete, requests[2].method)
    }

    @Test
    @DisplayName("the status read asks for no caching — a cached status hides a finished hook")
    fun statusIsUncached() = runTest {
        api { json(EMPTY_STATUS) }.workoutStatus(42)

        assertEquals("no-store", requests.single().headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", requests.single().headers[HttpHeaders.Pragma])
    }

    @Test
    @DisplayName("a status payload decodes both phases, availability included")
    fun statusDecodes() = runTest {
        val status = api {
            json(
                """{"start":{"fired_at":"2026-08-08T10:00:00Z","exit_code":0,"data":{"hr":"52"}},""" +
                    """"end":{"fired_at":"2026-08-08T11:00:00Z","exit_code":null},""" +
                    """"actions_available":{"start":true,"end":false}}""",
            )
        }.workoutStatus(42)

        assertEquals(0, status.start?.exitCode)
        assertEquals("2026-08-08T10:00:00Z", status.start?.firedAt)
        assertEquals("52", status.start?.data?.get("hr")?.jsonPrimitive?.content)
        assertNull(status.end?.exitCode)
        assertTrue(status.actionsAvailable.start)
        assertFalse(status.actionsAvailable.end)
    }

    @Test
    @DisplayName("a status with neither phase recorded decodes to defaults, not a failure")
    fun emptyStatusDecodes() = runTest {
        val status = api { json("""{"start":null,"end":null,"actions_available":{}}""") }.workoutStatus(42)

        assertNull(status.start)
        assertNull(status.end)
        assertFalse(status.actionsAvailable.start)
        assertFalse(status.actionsAvailable.end)
    }

    @Test
    @DisplayName("a hook the server has not configured (400) surfaces as an exception")
    fun unconfiguredHookThrows() = runTest {
        val api = api { respondError(HttpStatusCode.BadRequest) }

        assertThrows<Exception> { api.fireWorkoutHook(42, HookAction.START) }
    }

    @Test
    @DisplayName("an undo with nothing to undo (404) surfaces as an exception")
    fun undoWithNothingToUndoThrows() = runTest {
        val api = api { respondError(HttpStatusCode.NotFound) }

        assertThrows<Exception> { api.undoWorkoutHook(42, HookAction.START) }
    }

    @Test
    @DisplayName("a non-2xx response surfaces as an exception")
    fun nonSuccessThrows() = runTest {
        val api = api { respondError(HttpStatusCode.InternalServerError) }

        assertThrows<Exception> { api.plansVersion() }
    }

    @Test
    @DisplayName("HTTP logging records method, URL and status but never a body")
    fun loggingOmitsBodies() = runTest {
        val loggedMessages = mutableListOf<String>()
        val log = mockk<DebugLog>().also { mock ->
            every { mock.log(any(), any(), any()) } answers { loggedMessages += secondArg<String>() }
        }
        val config = ServerConfig(LOCAL_BASE)
        val secret = "fixture-private-note"
        val engine = MockEngine {
            respond(
                content = """{"plans":{},"logs":{"2030-01-03":{"session_feedback":{"general_notes":"$secret"}}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        CoachApi(buildHttpClient(engine, config, WellnessJson, log), config)
            .sync(clientId = "c1", lastSyncTime = null)

        val logged = loggedMessages.joinToString("\n")
        assertTrue(logged.contains("/wellness/api/coach/sync"), "URL missing from: $logged")
        assertTrue(logged.contains("200"), "status missing from: $logged")
        // The debug log is meant to be shareable; coach days are not.
        assertFalse(logged.contains(secret), "a log body leaked into the debug log: $logged")
    }

    private companion object {
        const val TAILNET_BASE = "https://pop-os.tailexample.ts.net:9443/wellness"
        const val LOCAL_BASE = "http://localhost:9001/wellness"
        const val EMPTY_SYNC = """{"plans":{},"logs":{},"serverTime":"s1","deletedPlanDates":[]}"""
        const val EMPTY_STATUS = """{"actions_available":{"start":true,"end":true}}"""
    }
}
