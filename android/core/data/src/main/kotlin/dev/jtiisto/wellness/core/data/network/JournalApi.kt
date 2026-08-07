package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.journal.DeltaResponseDto
import dev.jtiisto.wellness.core.data.journal.UpdateResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/journal/sync/status`. [lastModified] is null on an empty server.
 */
@Serializable
data class JournalSyncStatusDto(val lastModified: SyncStamp? = null)

/**
 * The journal sync endpoints: a status probe, the delta pull, and the upload.
 */
class JournalApi(
    private val client: HttpClient,
    private val config: ServerConfig,
) {
    suspend fun syncStatus(): JournalSyncStatusDto = client.get(config.endpoint(SYNC_STATUS_PATH)).body()

    /**
     * Everything the server has that we have not seen since [since].
     *
     * [since] is **omitted entirely** on a first sync — an empty `since=` is not
     * the same request, and the server reads its absence as "send everything".
     *
     * The no-cache headers are belt and braces: no HTTP cache is installed
     * today, but a delta served from one would silently skip changes forever,
     * since the watermark advances regardless.
     */
    suspend fun syncDelta(clientId: String, since: SyncStamp?): DeltaResponseDto =
        client.get(config.endpoint(SYNC_DELTA_PATH)) {
            if (since != null) parameter("since", since)
            parameter("client_id", clientId)
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.body()

    /**
     * Upload the dirty set. [payload] is a raw [JsonObject] rather than a typed
     * DTO because entry objects must carry explicit `"value": null` /
     * `"completed": null`, which the null-omitting serializer configuration
     * would drop from a data class.
     */
    suspend fun syncUpdate(payload: JsonObject): UpdateResponseDto =
        client.post(config.endpoint(SYNC_UPDATE_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()

    companion object {
        const val SYNC_STATUS_PATH = "/api/journal/sync/status"
        const val SYNC_DELTA_PATH = "/api/journal/sync/delta"
        const val SYNC_UPDATE_PATH = "/api/journal/sync/update"
    }
}
