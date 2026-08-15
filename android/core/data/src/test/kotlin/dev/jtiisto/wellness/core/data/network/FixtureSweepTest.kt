package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.WorkoutStatusDto
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
 * The fixture-gap sweep: the wire shapes that had no golden fixture until now.
 *
 * Two rules are pinned here as much as the shapes are. Endpoints whose success
 * body the client **ignores** get no success-body fixture — a fixture for a body
 * nothing decodes pins a contract we do not depend on — so those are asserted by
 * method, path and status instead. And the shared error envelope is asserted
 * *differently per module*, because only Analysis extracts `detail` from it.
 */
class FixtureSweepTest {

    private val json = WellnessJson

    private val debugLog = mockk<DebugLog>(relaxed = true).also {
        every { it.log(any(), any(), any()) } returns Unit
    }

    private val config = ServerConfig("http://localhost:9001/wellness")

    private fun fixture(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/$path")) {
            "missing golden fixture golden/$path"
        }.use { it.readBytes().decodeToString() }

    private fun engine(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        record: MutableList<HttpRequestData>? = null,
        capture: MutableList<String>? = null,
    ) = MockEngine { request ->
        record?.add(request)
        capture?.add(request.body.toByteArray().decodeToString())
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    // ---- journal sync status ---------------------------------------------

    @Test
    @DisplayName("sync status with a watermark decodes to the stamp")
    fun syncStatusWithValue() = runTest {
        val api = JournalApi(
            buildHttpClient(engine(body = fixture("journal/sync-status-value.json")), config, json, debugLog),
            config,
        )

        assertEquals("2026-08-09T12:00:00.000000Z", api.syncStatus().lastModified)
    }

    @Test
    @DisplayName("sync status decodes an explicit null — FastAPI emits it, it does not omit the key")
    fun syncStatusWithNull() = runTest {
        // Omitting optional fields is *our* upload convention, and it is not
        // symmetric: the decoder has to accept a null the server really sends.
        val api = JournalApi(
            buildHttpClient(engine(body = fixture("journal/sync-status-null.json")), config, json, debugLog),
            config,
        )

        assertNull(api.syncStatus().lastModified)
    }

    // ---- analysis submit request -----------------------------------------

    @Test
    @DisplayName("a submit with a location matches the request fixture byte for byte")
    fun submitRequestWithLocation() = runTest {
        val bodies = mutableListOf<String>()
        val api = AnalysisApi(
            buildHttpClient(engine(body = """{"id":1,"status":"pending"}""", capture = bodies), config, json, debugLog),
            config,
            json,
        )

        api.submit("fixture-query", "fixture-location")

        assertEquals(fixture("analysis/submit-request-with-location.json").trim(), bodies.single())
    }

    @Test
    @DisplayName("a submit without a location OMITS the key rather than sending null")
    fun submitRequestWithoutLocation() = runTest {
        val bodies = mutableListOf<String>()
        val api = AnalysisApi(
            buildHttpClient(engine(body = """{"id":1,"status":"pending"}""", capture = bodies), config, json, debugLog),
            config,
            json,
        )

        api.submit("fixture-query", null)

        // A persisted null is a value the server would store.
        assertEquals(fixture("analysis/submit-request-without-location.json").trim(), bodies.single())
        assertFalse(bodies.single().contains("location"))
    }

    @Test
    @DisplayName("a blank location is treated as no location at all")
    fun blankLocationIsOmitted() = runTest {
        val bodies = mutableListOf<String>()
        val api = AnalysisApi(
            buildHttpClient(engine(body = """{"id":1,"status":"pending"}""", capture = bodies), config, json, debugLog),
            config,
            json,
        )

        api.submit("fixture-query", "   ")

        assertEquals(fixture("analysis/submit-request-without-location.json").trim(), bodies.single())
    }

    // ---- coach workout-status matrix -------------------------------------

    @Test
    @DisplayName("hooks configured, neither fired")
    fun workoutStatusIdle() {
        val status = json.decodeFromString(
            WorkoutStatusDto.serializer(),
            fixture("coach/workout-status-idle-both-available.json"),
        )

        assertTrue(status.actionsAvailable.start)
        assertTrue(status.actionsAvailable.end)
        assertNull(status.start)
        assertNull(status.end)
    }

    @Test
    @DisplayName("a fired start carries its exit code and free-form payload")
    fun workoutStatusStartFired() {
        val status = json.decodeFromString(
            WorkoutStatusDto.serializer(),
            fixture("coach/workout-status-start-fired.json"),
        )

        assertEquals(0, status.start?.exitCode)
        assertEquals(
            "fixture-hook-payload",
            status.start?.data?.getValue("fixture_note")?.jsonPrimitive?.content,
        )
    }

    @Test
    @DisplayName("a failed start is a non-zero exit code, not an absent result")
    fun workoutStatusStartFailed() {
        val status = json.decodeFromString(
            WorkoutStatusDto.serializer(),
            fixture("coach/workout-status-start-failed.json"),
        )

        assertEquals(3, status.start?.exitCode)
    }

    @Test
    @DisplayName("a null exit code means still running, and must not decode as absent")
    fun workoutStatusEndPending() {
        val status = json.decodeFromString(
            WorkoutStatusDto.serializer(),
            fixture("coach/workout-status-end-pending.json"),
        )

        // "Running" and "never fired" are different states: the end result is
        // present, its exit code is not.
        assertNull(status.end?.exitCode)
        assertEquals("2026-08-09T11:05:00.000000Z", status.end?.firedAt)
    }

    @Test
    @DisplayName("no hooks configured decodes to both actions unavailable")
    fun workoutStatusNoneAvailable() {
        val status = json.decodeFromString(
            WorkoutStatusDto.serializer(),
            fixture("coach/workout-status-none-available.json"),
        )

        assertFalse(status.actionsAvailable.start)
        assertFalse(status.actionsAvailable.end)
    }

    // ---- ignored-body endpoints ------------------------------------------

    @Test
    @DisplayName("firing a hook asserts method and path, not a body nothing decodes")
    fun fireHookIsMethodAndPath() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = CoachApi(
            buildHttpClient(engine(body = """{"anything":"ignored"}""", record = requests), config, json, debugLog),
            config,
        )

        api.fireWorkoutHook(42, HookAction.START)

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/wellness/api/coach/workout/42/start", requests.single().url.encodedPath)
    }

    @Test
    @DisplayName("undoing a hook is a DELETE on the same path")
    fun undoHookIsMethodAndPath() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = CoachApi(
            buildHttpClient(engine(body = "", record = requests), config, json, debugLog),
            config,
        )

        api.undoWorkoutHook(42, HookAction.END)

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/wellness/api/coach/workout/42/end", requests.single().url.encodedPath)
    }

    @Test
    @DisplayName("deleting a report asserts method and path; its success body is ignored")
    fun deleteReportIsMethodAndPath() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = AnalysisApi(
            buildHttpClient(engine(body = "", record = requests), config, json, debugLog),
            config,
            json,
        )

        api.delete(7)

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/wellness/api/analysis/reports/7", requests.single().url.encodedPath)
    }

    // ---- the shared error envelope ---------------------------------------

    @Test
    @DisplayName("analysis extracts `detail` from the envelope into its own exception")
    fun analysisExtractsDetail() = runTest {
        val api = AnalysisApi(
            buildHttpClient(
                engine(status = HttpStatusCode.Conflict, body = fixture("error-envelope.json")),
                config,
                json,
                debugLog,
            ),
            config,
            json,
        )

        val error = assertThrows<AnalysisHttpException> { api.pending() }

        assertEquals(409, error.status)
        assertEquals("fixture-error-detail", error.detail)
    }

    @Test
    @DisplayName("the journal only propagates the non-2xx — it never reads the envelope")
    fun journalPropagatesNonSuccess() = runTest {
        val api = JournalApi(
            buildHttpClient(
                engine(status = HttpStatusCode.BadRequest, body = fixture("error-envelope.json")),
                config,
                json,
                debugLog,
            ),
            config,
        )

        val error = assertThrows<ClientRequestException> { api.syncStatus() }

        assertEquals(400, error.response.status.value)
    }

    @Test
    @DisplayName("coach propagates the non-2xx the same way")
    fun coachPropagatesNonSuccess() = runTest {
        val api = CoachApi(
            buildHttpClient(
                engine(status = HttpStatusCode.BadRequest, body = fixture("error-envelope.json")),
                config,
                json,
                debugLog,
            ),
            config,
        )

        assertThrows<ClientRequestException> { api.plansVersion() }
    }

    @Test
    @DisplayName("trends propagates the non-2xx the same way")
    fun trendsPropagatesNonSuccess() = runTest {
        val api = TrendsApi(
            buildHttpClient(
                engine(status = HttpStatusCode.BadRequest, body = fixture("error-envelope.json")),
                config,
                json,
                debugLog,
            ),
            config,
        )

        assertThrows<ClientRequestException> { api.overview() }
    }

    @Test
    @DisplayName("the envelope itself is the FastAPI shape the four modules all receive")
    fun envelopeShape() {
        val envelope = json.parseToJsonElement(fixture("error-envelope.json")).jsonObject

        assertEquals(setOf("detail"), envelope.keys)
        assertEquals("fixture-error-detail", envelope.getValue("detail").jsonPrimitive.content)
    }
}
