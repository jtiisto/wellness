package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.coach.CoachSyncPostResponseDto
import dev.jtiisto.wellness.core.data.coach.CoachSyncResponseDto
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.PlansVersionDto
import dev.jtiisto.wellness.core.data.coach.WorkoutStatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The coach sync endpoints: the pull, the upload, and the cheap version probe.
 *
 * One path serves both directions — `GET` pulls, `POST` uploads — so the two
 * halves of a cycle are told apart by method, not by URL.
 */
class CoachApi(
    private val client: HttpClient,
    private val config: ServerConfig,
) {
    /**
     * Everything the server has changed since [lastSyncTime], or the whole
     * 60-day window when it is null.
     *
     * The watermark is **omitted entirely** on a first sync: the server reads
     * its absence as "send the window", and an empty parameter is not the same
     * request. The no-cache headers matter more here than for most calls — an
     * incremental pull served from a cache would skip changes forever, because
     * the watermark advances regardless.
     */
    suspend fun sync(clientId: String, lastSyncTime: SyncStamp?): CoachSyncResponseDto =
        client.get(config.endpoint(SYNC_PATH)) {
            parameter("client_id", clientId)
            if (lastSyncTime != null) parameter("last_sync_time", lastSyncTime)
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.body()

    /**
     * Upload whole days. [logs] is a map of raw [JsonObject] days rather than a
     * typed DTO because exercise keys are arbitrary and the base-token protocol
     * lives inside the day object — the payload has to go out exactly as
     * `withBaseTokens` built it.
     */
    suspend fun syncPost(
        clientId: String,
        logs: Map<DateString, JsonObject>,
    ): CoachSyncPostResponseDto =
        client.post(config.endpoint(SYNC_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("clientId", clientId)
                    put("logs", JsonObject(logs))
                },
            )
        }.body()

    /**
     * The poll pre-check: one timestamp folding in plan edits, plan deletions,
     * log writes and log-entry deletions. Cheap enough to run every poll tick
     * so the full sync only runs when something actually moved.
     */
    suspend fun plansVersion(): PlansVersionDto =
        client.get(config.endpoint(PLANS_VERSION_PATH)) {
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.body()

    /**
     * The recorded state of this session's Start/End hooks, plus which of them
     * the server has configured at all.
     *
     * No-cache for the same reason the pull carries it: a status served from a
     * cache would keep a finished hook looking like it is still running.
     */
    suspend fun workoutStatus(sessionId: Long): WorkoutStatusDto =
        client.get(config.endpoint(workoutPath(sessionId, "status"))) {
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.body()

    /**
     * Fire a hook. The server records it and spawns the script asynchronously,
     * so a 2xx means "accepted and running", not "succeeded" — the exit code
     * arrives later through [workoutStatus].
     *
     * 400 (no such hook configured) and 404 (no such session) surface as
     * exceptions like any other failure; the caller's state machine treats every
     * failure identically.
     */
    suspend fun fireWorkoutHook(sessionId: Long, action: HookAction) {
        client.post(config.endpoint(workoutPath(sessionId, action.path)))
    }

    /** Undo a fired hook — the server deletes the recorded result, or 404s. */
    suspend fun undoWorkoutHook(sessionId: Long, action: HookAction) {
        client.delete(config.endpoint(workoutPath(sessionId, action.path)))
    }

    private fun workoutPath(sessionId: Long, suffix: String): String =
        "$WORKOUT_PATH/$sessionId/$suffix"

    companion object {
        const val SYNC_PATH = "/api/coach/sync"
        const val PLANS_VERSION_PATH = "/api/coach/plans-version"
        const val WORKOUT_PATH = "/api/coach/workout"
    }
}
