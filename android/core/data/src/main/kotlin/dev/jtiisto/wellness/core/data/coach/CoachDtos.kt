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
/** Which workout hook a call targets. The path segment is the wire name. */
enum class HookAction(val path: String) { START("start"), END("end") }

/**
 * `GET /api/coach/workout/{id}/status`.
 *
 * [actionsAvailable] is the ONLY availability source the client uses: it says
 * whether each hook script is configured and present on the server, which makes
 * the separate `GET /workout/config` call redundant. A response that omits it
 * means "nothing is configured" rather than a decode failure.
 */
@Serializable
data class WorkoutStatusDto(
    @SerialName("start") val start: HookResultDto? = null,
    @SerialName("end") val end: HookResultDto? = null,
    @SerialName("actions_available") val actionsAvailable: HookAvailabilityDto = HookAvailabilityDto(),
)

@Serializable
data class HookAvailabilityDto(
    @SerialName("start") val start: Boolean = false,
    @SerialName("end") val end: Boolean = false,
)

/**
 * One hook phase's recorded run.
 *
 * A null [exitCode] is not "unknown" — it is the server's marker for a hook
 * still running, written the moment the fire endpoint accepts the request. The
 * hook is killed at 120 s, so the null resolves one way or the other.
 */
@Serializable
data class HookResultDto(
    @SerialName("fired_at") val firedAt: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("data") val data: JsonObject? = null,
)

/**
 * One step of a cardio exercise's target-HR timeline.
 *
 * The server validates the shape before it is ever stored — `durationSec` >= 1,
 * at least one of the two bounds, `hrMin <= hrMax` when both — so a decoded
 * segment is already coherent and nothing re-checks it here. The bounds are
 * **absolute bpm**: no zone is ever resolved on this side, because nothing in
 * the system knows the athlete's zones (the plan author computed them and may
 * have put the zone's name in [label]).
 *
 * Which bounds are present is the whole meaning: min only is a floor, max only a
 * ceiling, both a range — which is why they are nullable rather than defaulted.
 */
@Serializable
data class PlanSegmentDto(
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("hr_min") val hrMin: Int? = null,
    @SerialName("hr_max") val hrMax: Int? = null,
    @SerialName("label") val label: String? = null,
    /**
     * What the segment is for: `warmup`, `work` or `cooldown`, **absent meaning
     * work**.
     *
     * A string rather than an enum on purpose. The server holds the closed set
     * and rejects anything outside it, so a value that gets this far and is not
     * one of the three can only be a hand-edited row — and a `@Serializable`
     * enum would fail the whole day's decode over it, where the display rule is
     * to degrade (see `SegmentRole`, which reads this leniently). Nothing here
     * re-validates; the field is carried.
     *
     * Nothing about display reads it: the static segments line is identical with
     * and without. It is the guide that consumes it — which spans a finished
     * ride averages, and which segment `+ 5 MIN` may lengthen.
     */
    @SerialName("role") val role: String? = null,
)

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
    /**
     * The target-HR timeline, on `duration` / `interval` exercises only.
     *
     * Omitted (never null, never `[]`) when the plan has none, which is the
     * common case — a cardio exercise without one simply has no timeline, and
     * nothing is derived from the block's `rounds` / `workDurationSec`.
     */
    @SerialName("segments") val segments: List<PlanSegmentDto>? = null,
)
