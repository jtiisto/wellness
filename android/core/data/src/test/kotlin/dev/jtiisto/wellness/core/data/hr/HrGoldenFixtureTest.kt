package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.network.HrApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The HR wire contract, against the SHARED fixtures at the repo root's
 * `test/hr/golden/` (see the README there) — the same files the server's
 * pytest suite POSTs at the real endpoints, staged onto this module's
 * unit-test classpath by the Sync task in its build script.
 *
 * This is the client half of the contract: the requests are built from
 * **Room rows**, not from hand-written DTOs, so the mappers, the property
 * order, the camelCase spelling and the omit-never-null rule are all pinned
 * by the same assertion, against the same bytes the server validates.
 */
class HrGoldenFixtureTest {

    private val json = WellnessJson

    private val debugLog = mockk<DebugLog>(relaxed = true).also {
        every { it.log(any(), any(), any()) } returns Unit
    }

    private val config = ServerConfig("http://localhost:9001/wellness")

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/hr/$name")) {
            "missing golden fixture golden/hr/$name"
        }.use { it.readBytes().decodeToString() }

    /**
     * The fixture with only its insignificant whitespace removed.
     *
     * The request files are wrapped across lines for review and no value in
     * them contains whitespace, so re-encoding the parsed document is
     * whitespace-only: key order, key names, number literals and which
     * optionals are present all survive it, and those are what the comparison
     * is for.
     */
    private fun compact(text: String): String =
        json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(text))

    /** The response shape each endpoint answers with, keyed by its path. */
    private fun replyFor(path: String): String =
        if (path.endsWith("sessions/batch")) fixture("upsert-response.json") else fixture("ingest-response.json")

    /** Captures what the client actually put on the wire, through the real API. */
    private fun apiRecording(bodies: MutableList<String>): HrApi {
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(
                content = replyFor(request.url.encodedPath),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return HrApi(buildHttpClient(engine, config, json, debugLog), config)
    }

    // ---- request payloads ------------------------------------------------

    @Test
    @DisplayName("three stored RR intervals serialize to the samples fixture")
    fun samplesRequestMatchesFixture() = runTest {
        val bodies = mutableListOf<String>()
        val rows = listOf(
            HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = 1770000000000,
                seq = 0,
                heartRateBpm = 142,
                rrIntervalMs = 423,
                sessionId = SESSION,
            ),
            // A zero RR interval is a legal artifact sentinel, and the second
            // beat of the same millisecond is what `seq` exists for.
            HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = 1770000000000,
                seq = 1,
                heartRateBpm = 142,
                rrIntervalMs = 0,
                sessionId = SESSION,
            ),
            HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = 1770000004100,
                seq = 0,
                heartRateBpm = 141,
                rrIntervalMs = 431,
                isGapBefore = true,
                sessionId = SESSION,
            ),
        )

        apiRecording(bodies).postSamples(SamplesBatchRequest(CLIENT, rows.map { it.toDto() }))

        assertEquals(compact(fixture("samples-batch-request.json")), bodies.single())
        // The local sync columns are bookkeeping about what this device has
        // sent; the server has its own and must never be told ours.
        assertFalse(bodies.single().contains("isSynced"))
        assertFalse(bodies.single().contains("isQuarantined"))
    }

    @Test
    @DisplayName("a set tick, its undo and a checklist toggle serialize to the events fixture")
    fun setEventsRequestMatchesFixture() = runTest {
        val bodies = mutableListOf<String>()
        val rows = listOf(
            SetEventEntity(
                eventId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                date = "2030-01-03",
                exerciseKey = "fixture-adhoc-lift",
                setNum = 1,
                action = SetEventEntity.ACTION_CHECK,
                clientTimestampMs = 1770000010000,
                sessionId = SESSION,
            ),
            SetEventEntity(
                eventId = "ffffffff-0000-1111-2222-333333333333",
                date = "2030-01-03",
                exerciseKey = "fixture-adhoc-lift",
                setNum = 1,
                action = SetEventEntity.ACTION_UNCHECK,
                clientTimestampMs = 1770000015000,
                sessionId = SESSION,
            ),
            // A checklist toggle with no capture running: two optionals absent
            // in one row, which is the case a null would have broken.
            SetEventEntity(
                eventId = "44444444-5555-6666-7777-888888888888",
                date = "2030-01-04",
                exerciseKey = "fixture-adhoc-checklist",
                itemKey = "fixture-item-a",
                action = SetEventEntity.ACTION_CHECK,
                clientTimestampMs = 1770000020000,
            ),
        )

        apiRecording(bodies).postSetEvents(SetEventsBatchRequest(CLIENT, rows.map { it.toDto() }))

        assertEquals(compact(fixture("set-events-batch-request.json")), bodies.single())
        assertFalse(bodies.single().contains("null"), "optionals are omitted, never null")
    }

    @Test
    @DisplayName("an open, workout-anchored session serializes to the sessions fixture")
    fun sessionsRequestMatchesFixture() = runTest {
        val bodies = mutableListOf<String>()
        val row = HrSessionEntity(
            sessionId = SESSION,
            deviceId = DEVICE,
            startedAtMs = 1769999990000,
            workoutDate = "2030-01-03",
            workoutSessionId = 42,
        )

        apiRecording(bodies).postSessions(SessionsBatchRequest(CLIENT, listOf(row.toDto())))

        assertEquals(compact(fixture("sessions-batch-request.json")), bodies.single())
        // An open session omits endedAtMs entirely. Sending it as null would
        // be a different contract — the upsert replaces every column, so what
        // is absent here is what the stored row loses.
        assertFalse(bodies.single().contains("endedAtMs"))
    }

    @Test
    @DisplayName("the three POSTs go to their own unversioned paths")
    fun endpointPaths() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            respond(
                content = replyFor(request.url.encodedPath),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = HrApi(buildHttpClient(engine, config, json, debugLog), config)

        api.postSamples(SamplesBatchRequest(CLIENT, emptyList()))
        api.postSetEvents(SetEventsBatchRequest(CLIENT, emptyList()))
        api.postSessions(SessionsBatchRequest(CLIENT, emptyList()))

        assertEquals(
            listOf(
                HttpMethod.Post to "/wellness/api/hr/samples/batch",
                HttpMethod.Post to "/wellness/api/hr/set-events/batch",
                HttpMethod.Post to "/wellness/api/hr/sessions/batch",
            ),
            requests,
        )
    }

    // ---- responses -------------------------------------------------------

    @Test
    @DisplayName("the shared ingest response decodes, and its counts reconcile")
    fun ingestResponseDecodes() {
        val response = json.decodeFromString(IngestResponseDto.serializer(), fixture("ingest-response.json"))

        assertEquals(3, response.accepted)
        assertEquals(0, response.duplicates)
        assertEquals(3, response.totalReceived)
        assertEquals(response.totalReceived, response.accepted + response.duplicates)
    }

    @Test
    @DisplayName("the sessions upsert response decodes to its own distinct shape")
    fun upsertResponseDecodes() {
        val response = json.decodeFromString(UpsertResponseDto.serializer(), fixture("upsert-response.json"))

        assertEquals(1, response.upserted)
        assertEquals(1, response.totalReceived)
        // No `accepted`/`duplicates`: an upsert cannot report duplicates,
        // which is why the two endpoints do not share a response type.
        assertFalse(fixture("upsert-response.json").contains("duplicates"))
    }

    @Test
    @DisplayName("a response missing a count fails to decode rather than reading as zero")
    fun partialIngestResponseIsRejected() {
        // Deliberate: the counts carry no defaults. A body we cannot read is
        // not a protocol state, and re-uploading an idempotent batch is the
        // cheaper mistake than believing a count that was never sent.
        val partial = """{"accepted":3,"duplicates":0}"""

        val failed = runCatching {
            json.decodeFromString(IngestResponseDto.serializer(), partial)
        }

        assertTrue(failed.isFailure)
    }

    @Test
    @DisplayName("the status probe decodes the three row counts")
    fun statusDecodes() = runTest {
        // No fixture: the server repo has none, and inventing one here would
        // break the byte-identical pact. This is the example from the
        // protocol's own documentation instead.
        val body = """{"status":"ok","samplesCount":12345,"setEventsCount":210,"sessionsCount":17}"""
        val engine = MockEngine {
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val status = HrApi(buildHttpClient(engine, config, json, debugLog), config).getStatus()

        assertEquals("ok", status.status)
        assertEquals(12345, status.samplesCount)
        assertEquals(210, status.setEventsCount)
        assertEquals(17, status.sessionsCount)
    }

    // ---- the omit-never-null rule, from the row side ---------------------

    @Test
    @DisplayName("a sample that is not after a gap omits isGapBefore entirely")
    fun defaultBooleanIsOmitted() {
        val encoded = json.encodeToString(
            SampleDto.serializer(),
            HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = 1770000000000,
                seq = 0,
                heartRateBpm = 142,
                rrIntervalMs = 423,
                sessionId = SESSION,
            ).toDto(),
        )

        assertFalse(encoded.contains("isGapBefore"), "false is the documented default and is not sent")
    }

    @Test
    @DisplayName("an unknown field in a response is ignored, not a decode failure")
    fun unknownResponseFieldIsIgnored() {
        // A server running ahead of this client must not break its uploads.
        val response = json.decodeFromString(
            IngestResponseDto.serializer(),
            """{"accepted":3,"duplicates":0,"totalReceived":3,"serverNote":"fixture"}""",
        )

        assertEquals(3, response.accepted)
    }

    @Test
    @DisplayName("a session row carries its nulls as absent keys through the mapper")
    fun sessionMapperOmitsAbsentAnchors() {
        val dto = HrSessionEntity(sessionId = SESSION, deviceId = DEVICE, startedAtMs = 1L).toDto()

        assertNull(dto.endedAtMs)
        assertNull(dto.workoutDate)
        assertNull(dto.workoutSessionId)
        assertEquals(
            """{"sessionId":"$SESSION","deviceId":"$DEVICE","startedAtMs":1}""",
            json.encodeToString(SessionDto.serializer(), dto),
        )
    }

    private companion object {
        const val CLIENT = "fixture-client-hr-0001"
        const val DEVICE = "AA:BB:CC:DD:EE:FF"
        const val SESSION = "11111111-2222-3333-4444-555555555555"
    }
}
