package dev.jtiisto.wellness.ui.coach

import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.PlanDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Where the debug screen's scratch write goes, and what it writes. */
data class ScratchWrite(val exerciseKey: String, val label: String, val data: JsonObject)

/** The exercise type the scratch set is meaningful for. */
private const val STRENGTH = "strength"

/**
 * Pick the scratch write for [plan]: one set against the day's first planned
 * strength exercise, or an ad-hoc Zone 2 session when there is nothing to log
 * against.
 *
 * The fallback covers a genuine rest day (no plan at all) and a planned day
 * with no strength work — in both cases [EXTRA_SESSION_KEY] is the server's
 * off-plan channel and needs no planned exercise to attach to.
 *
 * Phase 5 replaces this with real set entry; until then it is the end-to-end
 * proof that a write from the phone reaches the server.
 */
fun scratchWriteFor(plan: PlanDto?): ScratchWrite {
    val exercise = plan?.blocks
        ?.asSequence()
        ?.flatMap { it.exercises }
        ?.firstOrNull { it.type == STRENGTH }
        ?: return ScratchWrite(
            exerciseKey = EXTRA_SESSION_KEY,
            label = "Zone 2 — extra session",
            data = buildJsonObject { put("duration_min", 30) },
        )

    return ScratchWrite(
        exerciseKey = exercise.id,
        label = exercise.name,
        data = buildJsonObject {
            putJsonArray("sets") {
                add(
                    buildJsonObject {
                        put("set_num", 1)
                        put("weight", 100)
                        put("reps", 5)
                        put("completed", true)
                    },
                )
            }
        },
    )
}

/** The plan's exercises flattened for display, one line of targets each. */
fun planTargetSummary(exerciseTargets: List<String?>): String =
    exerciseTargets.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/**
 * The one-line target description the debug screen shows under an exercise
 * name. Free-form server text (`"8-10"`, `"6-7"`, `"70%"`) is shown verbatim.
 */
fun exerciseTargets(
    targetSets: Int?,
    targetReps: String?,
    targetDurationMin: Int?,
    targetDurationSec: Int?,
    targetLoad: String?,
    targetRpe: String?,
): String = planTargetSummary(
    listOf(
        targetSets?.let { sets -> targetReps?.let { "$sets x $it" } ?: "$sets sets" },
        targetReps?.takeIf { targetSets == null },
        targetDurationMin?.let { "$it min" },
        targetDurationSec?.let { "$it s" },
        targetLoad?.let { "@ $it" },
        targetRpe?.let { "RPE $it" },
    ),
)
