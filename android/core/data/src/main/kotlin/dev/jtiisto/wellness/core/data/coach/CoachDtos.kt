package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/coach/sync`.
 *
 * Plans and logs stay [JsonObject] the whole way down the sync path. A log day
 * has arbitrary exercise keys and the client must be able to upload it back
 * byte-for-byte; a plan is server-owned and only *rendered*, never edited. Both
 * are stored as the opaque JSON they arrive as, and typed only on read.
 */
@Serializable
data class CoachSyncResponseDto(
    val plans: Map<DateString, JsonObject> = emptyMap(),
    val logs: Map<DateString, JsonObject> = emptyMap(),
    val serverTime: SyncStamp? = null,
    val earliestDate: DateString? = null,
    val deletedPlanDates: List<DateString> = emptyList(),
)

/**
 * `POST /api/coach/sync`. There is no whole-upload rejection: the server
 * reconciles each date per record and returns the merged day in [results],
 * carrying every record's fresh `_lastModified`.
 */
@Serializable
data class CoachSyncPostResponseDto(
    val success: Boolean = false,
    val results: Map<DateString, JsonObject> = emptyMap(),
    val serverTime: SyncStamp? = null,
)

/** `GET /api/coach/plans-version` — the cheap poll probe. */
@Serializable
data class PlansVersionDto(val version: String? = null)

/**
 * A day's plan, typed for rendering only.
 *
 * **The wire is snake_case** and [dev.jtiisto.wellness.core.data.WellnessJson]
 * has no naming strategy, so every property needs its own `@SerialName` —
 * without one a required field simply goes missing and the decode fails.
 *
 * Decode-on-read and never re-encoded: the stored blob is the source of truth
 * and the client never uploads a plan, so a server field this class does not
 * know about is preserved by simply not being touched.
 */
@Serializable
data class PlanDto(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("day_name") val dayName: String? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("phase") val phase: String? = null,
    @SerialName("total_duration_min") val totalDurationMin: Int? = null,
    @SerialName("blocks") val blocks: List<PlanBlockDto> = emptyList(),
)

@Serializable
data class PlanBlockDto(
    @SerialName("block_index") val blockIndex: Int,
    @SerialName("block_type") val blockType: String,
    @SerialName("title") val title: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    @SerialName("rest_guidance") val restGuidance: String = "",
    @SerialName("rounds") val rounds: Int? = null,
    @SerialName("work_duration_sec") val workDurationSec: Int? = null,
    @SerialName("rest_duration_sec") val restDurationSec: Int? = null,
    @SerialName("exercises") val exercises: List<PlanExerciseDto> = emptyList(),
)

/**
 * One planned exercise. [id] is the `exercise_key` a log entry is filed under —
 * the join between plan and log.
 *
 * [targetReps], [targetRpe] and [targetLoad] are **strings**: the server stores
 * free-form coaching text there (`"8-10"`, `"6-7"`, `"70%"`), not numbers.
 */
@Serializable
data class PlanExerciseDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("type") val type: String,
    @SerialName("target_sets") val targetSets: Int? = null,
    @SerialName("target_reps") val targetReps: String? = null,
    @SerialName("target_duration_min") val targetDurationMin: Int? = null,
    @SerialName("target_duration_sec") val targetDurationSec: Int? = null,
    @SerialName("rounds") val rounds: Int? = null,
    @SerialName("work_duration_sec") val workDurationSec: Int? = null,
    @SerialName("rest_duration_sec") val restDurationSec: Int? = null,
    @SerialName("guidance_note") val guidanceNote: String? = null,
    @SerialName("hide_weight") val hideWeight: Boolean? = null,
    @SerialName("show_time") val showTime: Boolean? = null,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("exposure") val exposure: String? = null,
    @SerialName("tempo") val tempo: String? = null,
    @SerialName("target_rpe") val targetRpe: String? = null,
    @SerialName("target_load") val targetLoad: String? = null,
    @SerialName("canonical_slug") val canonicalSlug: String? = null,
    @SerialName("items") val items: List<String>? = null,
)
