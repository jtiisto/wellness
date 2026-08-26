package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The twelve Trends aggregate payloads.
 *
 * Two typing rules run through all of them, and both are load-bearing:
 *
 * - **Every physiological measurement is a `Double`**, `avg_hr`, `rhr` and
 *   `sleep_score` included. The server computes these from source databases
 *   that flip freely between integer and float representations of the same
 *   quantity, and an `Int` property fails the whole decode the first time a
 *   heart rate arrives as `52.0`. `Int` is reserved for things that count:
 *   reps, sessions, scheduled days, streaks.
 * - **Nullability mirrors the wire exactly.** Omitted keys are the exception
 *   and each one carries a default here: `weekly_usage` on a tracker detail,
 *   and — since the sleep ledger arrived — `as_of`, `tonight`, `gap` and
 *   `strain_partial` on `/health/sleep`. Everything else arrives as a value, an
 *   explicit null, or an empty list.
 *
 * The shared `WellnessJson` has no naming strategy, so every multi-word field
 * carries an explicit `@SerialName`. Golden-fixture decode tests enforce that.
 */

// ---- GET /overview ---------------------------------------------------------

@Serializable
data class OverviewDto(
    val zone2: Zone2Tile,
    val tonnage: TonnageTile,
    @SerialName("adherence_focus") val adherenceFocus: List<FocusRow>,
    val prs: PrSummary,
)

@Serializable
data class Zone2Tile(
    @SerialName("this_week_min") val thisWeekMin: Double,
    @SerialName("last_week_min") val lastWeekMin: Double?,
    @SerialName("four_week_avg_min") val fourWeekAvgMin: Double?,
    val sparkline: List<Zone2Week>,
)

@Serializable
data class Zone2Week(
    @SerialName("week_start") val weekStart: DateString,
    @SerialName("planned_min") val plannedMin: Double,
    @SerialName("extra_min") val extraMin: Double,
)

@Serializable
data class TonnageTile(
    @SerialName("this_week_kg") val thisWeekKg: Double,
    @SerialName("last_week_kg") val lastWeekKg: Double?,
    @SerialName("four_week_avg_kg") val fourWeekAvgKg: Double?,
    val sparkline: List<TonnageWeek>,
)

@Serializable
data class TonnageWeek(
    @SerialName("week_start") val weekStart: DateString,
    @SerialName("tonnage_kg") val tonnageKg: Double,
)

/** [metricKind] is `"adherence"` or `"avoidance"` — the label, not a computation. */
@Serializable
data class FocusRow(
    @SerialName("tracker_id") val trackerId: String,
    val name: String,
    @SerialName("metric_kind") val metricKind: String,
    val rate: Double,
    val dropping: Boolean,
    val ribbon: List<RibbonDay>,
)

/** [status] is one of `met` / `partial` / `missed` / `off`. */
@Serializable
data class RibbonDay(
    val date: DateString,
    val status: String,
)

@Serializable
data class PrSummary(
    @SerialName("count_30d") val count30d: Int,
    val latest: PrLatest?,
)

@Serializable
data class PrLatest(
    val slug: String,
    val name: String,
    val date: DateString,
    val e1rm: Double,
    val weight: Double,
    val reps: Int,
    val unit: String,
)

// ---- GET /weight -----------------------------------------------------------

@Serializable
data class WeightDto(
    val available: Boolean,
    val series: List<WeightPoint>,
)

@Serializable
data class WeightPoint(
    val date: DateString,
    val kg: Double,
)

// ---- GET /strength/exercises ----------------------------------------------

@Serializable
data class ExercisesDto(
    val exercises: List<ExerciseSummary>,
)

/**
 * [inRange] is null whenever no `start` was sent — the All range — and the UI
 * reads that as "no in-range block", never as an error. [allTime] is decoded
 * nullable as forward-compatible tolerance: today an exercise is only listed
 * when it has sets, so the server never omits it.
 */
@Serializable
data class ExerciseSummary(
    val slug: String,
    val name: String,
    val equipment: String?,
    @SerialName("last_used") val lastUsed: DateString,
    @SerialName("session_count") val sessionCount: Int,
    val unit: String,
    @SerialName("all_time") val allTime: Best?,
    @SerialName("in_range") val inRange: Best?,
    val plateau: Boolean,
)

@Serializable
data class Best(
    @SerialName("best_weight") val bestWeight: BestWeight,
    @SerialName("best_e1rm") val bestE1rm: BestE1rm,
)

@Serializable
data class BestWeight(
    val weight: Double,
    val reps: Int,
    val date: DateString,
    val assistance: Double?,
)

@Serializable
data class BestE1rm(
    val value: Double,
    val weight: Double,
    val reps: Int,
    val date: DateString,
    val assistance: Double?,
)

// ---- GET /strength/exercise/{slug} ----------------------------------------

@Serializable
data class ExerciseDetailDto(
    val exercise: ExerciseInfo,
    val unit: String,
    val sessions: List<SessionPoint>,
)

@Serializable
data class ExerciseInfo(
    val slug: String,
    val name: String,
    val equipment: String?,
    val category: String?,
)

@Serializable
data class SessionPoint(
    val date: DateString,
    @SerialName("top_set") val topSet: TopSet,
    val e1rm: Double,
    @SerialName("top_set_rpe") val topSetRpe: Double?,
    @SerialName("set_count") val setCount: Int,
    @SerialName("off_plan") val offPlan: Boolean,
)

@Serializable
data class TopSet(
    val weight: Double,
    val reps: Int,
    val assistance: Double?,
)

// ---- GET /strength/volume --------------------------------------------------

@Serializable
data class VolumeDto(
    val weeks: List<VolumeWeek>,
)

@Serializable
data class VolumeWeek(
    @SerialName("week_start") val weekStart: DateString,
    val partial: Boolean,
    @SerialName("tonnage_kg") val tonnageKg: Double,
    @SerialName("hard_sets") val hardSets: Int,
    @SerialName("by_exercise") val byExercise: List<SlugTonnage>,
)

@Serializable
data class SlugTonnage(
    val slug: String,
    val name: String,
    @SerialName("tonnage_kg") val tonnageKg: Double,
    @SerialName("hard_sets") val hardSets: Int,
)

// ---- GET /cardio -----------------------------------------------------------

@Serializable
data class CardioDto(
    val weeks: List<CardioWeek>,
    @SerialName("steady_sessions") val steadySessions: List<SteadySession>,
)

@Serializable
data class CardioWeek(
    @SerialName("week_start") val weekStart: DateString,
    val partial: Boolean,
    @SerialName("zone2_planned_min") val zone2PlannedMin: Double,
    @SerialName("zone2_extra_min") val zone2ExtraMin: Double,
    @SerialName("interval_sessions") val intervalSessions: Int,
)

@Serializable
data class SteadySession(
    val date: DateString,
    @SerialName("avg_hr") val avgHr: Double,
    @SerialName("duration_min") val durationMin: Double,
    @SerialName("off_plan") val offPlan: Boolean,
)

// ---- GET /journal/trackers -------------------------------------------------

@Serializable
data class TrackersDto(
    val trackers: List<TrackerSummary>,
)

@Serializable
data class TrackerSummary(
    val id: String,
    val name: String?,
    val type: String?,
    val unit: String?,
    val polarity: String?,
    val actionable: Boolean,
    @SerialName("has_target") val hasTarget: Boolean,
    @SerialName("first_entry") val firstEntry: DateString,
    @SerialName("last_entry") val lastEntry: DateString,
)

// ---- GET /journal/tracker/{id} ---------------------------------------------

@Serializable
data class TrackerDetailDto(
    val tracker: TrackerSummary,
    val values: List<TrackerValue>,
    @SerialName("target_segments") val targetSegments: List<TargetSegment>,
    @SerialName("weekly_adherence") val weeklyAdherence: List<AdherenceWeek>,
    val streaks: Streaks,
    /** The API's one omitted key: present only for neutral (non-actionable) trackers. */
    @SerialName("weekly_usage") val weeklyUsage: List<UsageWeek>? = null,
)

/**
 * [value] is a raw [JsonElement] on purpose.
 *
 * A tracker converted from type `note` keeps free-text entry values — the
 * column is REAL-affinity and text survives — so a `Double?` here would fail
 * the *whole* payload's decode before any coercion could run. `coerceNumeric`
 * is the only thing that ever reads it.
 *
 * [completed] is `1 | 0 | null` on the wire, never a boolean.
 */
@Serializable
data class TrackerValue(
    val date: DateString,
    val value: JsonElement?,
    val completed: Int?,
)

/** Effective-dated target window; both ends are **inclusive**. */
@Serializable
data class TargetSegment(
    val start: DateString,
    val end: DateString,
    val min: Double?,
    val max: Double?,
)

@Serializable
data class AdherenceWeek(
    @SerialName("week_start") val weekStart: DateString,
    val partial: Boolean,
    val paused: Boolean,
    @SerialName("scheduled_days") val scheduledDays: Int,
    val met: Int,
    @SerialName("partial_days") val partialDays: Int,
    val missed: Int,
    val rate: Double?,
    @SerialName("metric_kind") val metricKind: String,
)

@Serializable
data class Streaks(
    val current: Int,
    val best: Int,
)

@Serializable
data class UsageWeek(
    @SerialName("week_start") val weekStart: DateString,
    val partial: Boolean,
    val count: Int,
)

// ---- GET /health/recovery --------------------------------------------------

@Serializable
data class RecoveryDto(
    val available: Boolean,
    val days: List<RecoveryDay>,
)

@Serializable
data class RecoveryDay(
    val date: DateString,
    val rhr: Double?,
    val hrv: Double?,
    @SerialName("hrv_band") val hrvBand: HrvBand?,
    @SerialName("sleep_hours") val sleepHours: Double?,
    @SerialName("sleep_score") val sleepScore: Double?,
)

/** Garmin's own baseline, never recomputed here. */
@Serializable
data class HrvBand(
    val low: Double,
    val high: Double,
    @SerialName("low_floor") val lowFloor: Double?,
)

// ---- GET /health/sleep -----------------------------------------------------

/**
 * The sleep-need ledger: what tonight asks for, and what every night since has.
 *
 * [asOf] is the latest date in the *whole* history carrying a scored night —
 * never clipped by the requested range — so a stale watch sync stays visible at
 * any zoom. It is omitted when nothing has ever been scored and whenever the
 * source is unavailable; [tonight] is omitted only in the unavailable case.
 *
 * Unavailable is a supported state, not an error: the server answers 200 with
 * `{"available": false, "days": []}` when the Garmin database or the fitted
 * parameters are missing on that machine.
 */
@Serializable
data class SleepDebtDto(
    val available: Boolean,
    @SerialName("as_of") val asOf: DateString? = null,
    val tonight: SleepTonight? = null,
    val days: List<SleepDebtDay>,
)

/**
 * The upcoming night.
 *
 * [strainPartial] is always true on the wire and defaults to false here for the
 * same reason every optional key does — the default is what the *absence* of
 * the key means, not what the server happens to send. Today's activity is still
 * accumulating, so tonight's need is a number that will still move.
 */
@Serializable
data class SleepTonight(
    val date: DateString,
    @SerialName("need_min") val needMin: Double,
    @SerialName("debt_min") val debtMin: Double,
    @SerialName("strain_est") val strainEst: Double,
    @SerialName("strain_partial") val strainPartial: Boolean = false,
)

/**
 * One night, keyed by the date it was **woken from** — the same keying the
 * recovery payload uses, so this overlays the sleep card row for row.
 *
 * [needMin] was fixed the previous evening and is compared against [sleptMin],
 * the raw minutes the watch recorded. [debtMin] is the debt carried *into* that
 * night and is never negative. [gap] marks a night following a missing sleep
 * record: the ledger has nothing to carry from, so the debt resets to zero and
 * no line should be drawn across the break. The key is omitted when false.
 */
@Serializable
data class SleepDebtDay(
    val date: DateString,
    @SerialName("need_min") val needMin: Double,
    @SerialName("slept_min") val sleptMin: Double,
    @SerialName("debt_min") val debtMin: Double,
    @SerialName("strain_est") val strainEst: Double,
    val gap: Boolean = false,
)

// ---- GET /health/composition -----------------------------------------------

@Serializable
data class CompositionDto(
    val available: Boolean,
    val scans: List<Scan>,
)

@Serializable
data class Scan(
    val date: DateString,
    @SerialName("lean_kg") val leanKg: Double?,
    @SerialName("fat_kg") val fatKg: Double?,
    @SerialName("total_kg") val totalKg: Double?,
    @SerialName("body_fat_pct") val bodyFatPct: Double?,
    @SerialName("vat_kg") val vatKg: Double?,
    @SerialName("ag_ratio") val agRatio: Double?,
    @SerialName("bmd_total") val bmdTotal: Double?,
    @SerialName("t_score_total") val tScoreTotal: Double?,
)

// ---- GET /health/labs ------------------------------------------------------

@Serializable
data class LabsDto(
    val available: Boolean,
    val panels: List<LabPanel>,
)

@Serializable
data class LabPanel(
    val name: String,
    val tests: List<LabTest>,
)

@Serializable
data class LabTest(
    val name: String,
    val unit: String?,
    val observations: List<LabObs>,
)

/**
 * [flag] is the lab's own H/L marker and the *only* thing that tones an
 * observation — a recomputed comparison against [refLow]/[refHigh] would
 * disagree with the report the user is holding.
 */
@Serializable
data class LabObs(
    val date: DateString,
    val value: Double?,
    val text: String?,
    val prefix: String?,
    val flag: String?,
    @SerialName("ref_low") val refLow: Double?,
    @SerialName("ref_high") val refHigh: Double?,
    @SerialName("ref_text") val refText: String?,
)
