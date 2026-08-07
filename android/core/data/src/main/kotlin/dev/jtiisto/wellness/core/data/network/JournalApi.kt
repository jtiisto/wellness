package dev.jtiisto.wellness.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

/**
 * `GET /api/journal/sync/status`. [lastModified] is null on an empty server.
 */
@Serializable
data class JournalSyncStatusDto(val lastModified: SyncStamp? = null)

/**
 * The journal endpoints. Phase 1 covers the status probe only; delta pull and
 * upload arrive with the journal store in Phase 2.
 */
class JournalApi(
    private val client: HttpClient,
    private val config: ServerConfig,
) {
    suspend fun syncStatus(): JournalSyncStatusDto = client.get(config.endpoint(SYNC_STATUS_PATH)).body()

    companion object {
        const val SYNC_STATUS_PATH = "/api/journal/sync/status"
    }
}
