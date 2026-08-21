package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.PlanBlockDto
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import dev.jtiisto.wellness.core.data.coach.TYPE_STRENGTH
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plan/log builders for the feature-module tests. See the `:core:data` twin. */

@Suppress("LongParameterList")
internal fun exercise(
    id: String = "ex_1",
    name: String = "Exercise",
    type: String = TYPE_STRENGTH,
    targetSets: Int? = null,
    targetReps: String? = null,
    targetDurationMin: Int? = null,
    items: List<String>? = null,
    supersetGroup: String? = null,
    exposure: String? = null,
    canonicalSlug: String? = null,
    hideWeight: Boolean? = null,
    showTime: Boolean? = null,
    guidanceNote: String? = null,
    segments: List<PlanSegmentDto>? = null,
): PlanExerciseDto = PlanExerciseDto(
    id = id,
    name = name,
    type = type,
    targetSets = targetSets,
    targetReps = targetReps,
    targetDurationMin = targetDurationMin,
    guidanceNote = guidanceNote,
    hideWeight = hideWeight,
    showTime = showTime,
    supersetGroup = supersetGroup,
    exposure = exposure,
    canonicalSlug = canonicalSlug,
    items = items,
    segments = segments,
)

/**
 * One timeline step. Bounds are absolute bpm and **invented** — never a number
 * off a real ride, and the far-future dates around them say the same thing.
 */
internal fun segment(
    durationSec: Int,
    hrMin: Int? = null,
    hrMax: Int? = null,
    label: String? = null,
): PlanSegmentDto = PlanSegmentDto(
    durationSec = durationSec,
    hrMin = hrMin,
    hrMax = hrMax,
    label = label,
)

internal fun block(
    blockIndex: Int = 0,
    blockType: String = "strength",
    title: String? = null,
    exercises: List<PlanExerciseDto> = emptyList(),
): PlanBlockDto = PlanBlockDto(
    blockIndex = blockIndex,
    blockType = blockType,
    title = title,
    exercises = exercises,
)

internal fun plan(
    sessionId: Long = 1,
    dayName: String? = "Push Day",
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

internal fun loggedSet(
    setNum: Int? = null,
    weight: Double? = null,
    reps: Int? = null,
    durationSec: Int? = null,
    completed: Boolean? = null,
): JsonObject = buildJsonObject {
    setNum?.let { put("set_num", it) }
    weight?.let { put("weight", journalNumberJson(it)) }
    reps?.let { put("reps", it) }
    durationSec?.let { put("duration_sec", it) }
    completed?.let { put("completed", it) }
}

internal fun sets(vararg rows: JsonObject): JsonArray = JsonArray(rows.toList())

/** A day's log holding one exercise entry. */
internal fun dayLog(exerciseId: String, entry: JsonObject): JsonObject = buildJsonObject {
    put(exerciseId, entry)
}
