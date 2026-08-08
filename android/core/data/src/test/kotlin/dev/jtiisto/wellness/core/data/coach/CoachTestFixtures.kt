package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Builders for the coach UI tests.
 *
 * The JS suites these are transcribed from build their fixtures as object
 * literals; these keep the same shapes so a case can be read against its
 * original line by line.
 */

internal fun exercise(
    id: String = "ex_1",
    name: String = "Exercise",
    type: String = TYPE_STRENGTH,
    targetSets: Int? = null,
    targetReps: String? = null,
    targetDurationMin: Int? = null,
    targetDurationSec: Int? = null,
    rounds: Int? = null,
    workDurationSec: Int? = null,
    restDurationSec: Int? = null,
    items: List<String>? = null,
    supersetGroup: String? = null,
    exposure: String? = null,
    canonicalSlug: String? = null,
    targetRpe: String? = null,
    targetLoad: String? = null,
    tempo: String? = null,
    hideWeight: Boolean? = null,
    showTime: Boolean? = null,
    guidanceNote: String? = null,
): PlanExerciseDto = PlanExerciseDto(
    id = id,
    name = name,
    type = type,
    targetSets = targetSets,
    targetReps = targetReps,
    targetDurationMin = targetDurationMin,
    targetDurationSec = targetDurationSec,
    rounds = rounds,
    workDurationSec = workDurationSec,
    restDurationSec = restDurationSec,
    guidanceNote = guidanceNote,
    hideWeight = hideWeight,
    showTime = showTime,
    supersetGroup = supersetGroup,
    exposure = exposure,
    tempo = tempo,
    targetRpe = targetRpe,
    targetLoad = targetLoad,
    canonicalSlug = canonicalSlug,
    items = items,
)

internal fun block(
    blockIndex: Int = 0,
    blockType: String = "strength",
    title: String? = null,
    restGuidance: String = "",
    rounds: Int? = null,
    workDurationSec: Int? = null,
    restDurationSec: Int? = null,
    exercises: List<PlanExerciseDto> = emptyList(),
): PlanBlockDto = PlanBlockDto(
    blockIndex = blockIndex,
    blockType = blockType,
    title = title,
    restGuidance = restGuidance,
    rounds = rounds,
    workDurationSec = workDurationSec,
    restDurationSec = restDurationSec,
    exercises = exercises,
)

internal fun plan(
    sessionId: Long = 1,
    dayName: String? = "Workout",
    location: String? = null,
    phase: String? = null,
    blocks: List<PlanBlockDto> = emptyList(),
): PlanDto = PlanDto(
    sessionId = sessionId,
    dayName = dayName,
    location = location,
    phase = phase,
    blocks = blocks,
)

/** A one-exercise plan keyed by slug — the shape `last-performance.test.js` uses. */
internal fun planWith(exerciseId: String, slug: String, exposure: String? = null): PlanDto =
    plan(blocks = listOf(block(exercises = listOf(exercise(id = exerciseId, canonicalSlug = slug, exposure = exposure)))))

/** A day's log holding one exercise's sets. */
internal fun logWith(exerciseId: String, sets: List<JsonObject>): JsonObject = buildJsonObject {
    put(exerciseId, buildJsonObject { put("sets", JsonArray(sets)) })
}

/** One logged set. Every field is optional — that is the point of most cases. */
internal fun loggedSet(
    setNum: Int? = null,
    weight: Double? = null,
    reps: Int? = null,
    rpe: Double? = null,
    durationSec: Int? = null,
    completed: Boolean? = null,
): JsonObject = buildJsonObject {
    setNum?.let { put("set_num", it) }
    weight?.let { put("weight", journalNumberJson(it)) }
    reps?.let { put("reps", it) }
    rpe?.let { put("rpe", journalNumberJson(it)) }
    durationSec?.let { put("duration_sec", it) }
    completed?.let { put("completed", it) }
}

/** An exercise's log entry with an arbitrary set list. */
internal fun entryWithSets(vararg sets: JsonObject): JsonObject = buildJsonObject {
    put("sets", JsonArray(sets.toList()))
}

internal fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

internal fun jsonText(value: String): JsonPrimitive = JsonPrimitive(value)
