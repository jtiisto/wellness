package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.hr.GuideEventsBatchRequest
import dev.jtiisto.wellness.core.data.hr.IngestResponseDto
import dev.jtiisto.wellness.core.data.hr.SamplesBatchRequest
import dev.jtiisto.wellness.core.data.hr.SessionsBatchRequest
import dev.jtiisto.wellness.core.data.hr.SetEventsBatchRequest
import dev.jtiisto.wellness.core.data.hr.StatusResponseDto
import dev.jtiisto.wellness.core.data.hr.UpsertResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * The heart-rate module's endpoints: one probe and four batch ingests.
 *
 * Upload-only, unlike every other API here — the server answers with counts and
 * nothing else, so there is no pull, no watermark and no arbitration. The
 * bodies are typed DTOs rather than hand-built [kotlinx.serialization.json.JsonObject]s
 * (coach's rows are opaque blobs; these are a protocol this client authored),
 * which means content negotiation serializes them with the shared Json and its
 * omit-never-null rules.
 *
 * Non-2xx responses surface as exceptions, as everywhere else. The distinction
 * the caller draws between them is the whole error contract — see
 * [dev.jtiisto.wellness.core.data.hr.HrSyncStore].
 */
class HrApi(
    private val client: HttpClient,
    private val config: ServerConfig,
) {
    /**
     * Row counts and liveness, for the configuration screen's reachability
     * check. No-cache for the same reason the sync pulls carry it: a cached
     * count is a count from a different moment, which is the one thing this is
     * asked for.
     */
    suspend fun getStatus(): StatusResponseDto =
        client.get(config.endpoint(STATUS_PATH)) {
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.body()

    /** `INSERT OR IGNORE` on `(deviceId, timestampMs, seq)`: retries are free. */
    suspend fun postSamples(batch: SamplesBatchRequest): IngestResponseDto =
        client.post(config.endpoint(SAMPLES_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }.body()

    /** `INSERT OR IGNORE` on `eventId`. */
    suspend fun postSetEvents(batch: SetEventsBatchRequest): IngestResponseDto =
        client.post(config.endpoint(SET_EVENTS_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }.body()

    /** `INSERT OR IGNORE` on `eventId`, as the set-event log is. */
    suspend fun postGuideEvents(batch: GuideEventsBatchRequest): IngestResponseDto =
        client.post(config.endpoint(GUIDE_EVENTS_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }.body()

    /**
     * Full-row upsert on `sessionId`, last write wins — a session legitimately
     * changes after its first upload (started → workout-anchored → ended) and
     * has exactly one writer, so there is nothing to arbitrate.
     */
    suspend fun postSessions(batch: SessionsBatchRequest): UpsertResponseDto =
        client.post(config.endpoint(SESSIONS_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }.body()

    companion object {
        const val STATUS_PATH = "/api/hr/status"
        const val SAMPLES_PATH = "/api/hr/samples/batch"
        const val SET_EVENTS_PATH = "/api/hr/set-events/batch"
        const val GUIDE_EVENTS_PATH = "/api/hr/guide-events/batch"
        const val SESSIONS_PATH = "/api/hr/sessions/batch"
    }
}
