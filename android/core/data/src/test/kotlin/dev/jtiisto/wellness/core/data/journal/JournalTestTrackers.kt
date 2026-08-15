package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builders for the transcribed `test/js/journal-*.test.js` suites.
 *
 * The JS fixtures are object literals with only the fields a case cares about;
 * these give the same brevity over the typed DTOs, with the legacy
 * `frequency`/`weeklyDay` pair going where it really lives — `extras`.
 */

internal fun tracker(
    id: String = "t",
    name: String? = null,
    category: String? = null,
    type: String? = null,
    polarity: String? = null,
    scheduleHistory: List<ScheduleSegmentDto>? = null,
    targetHistory: List<TargetSegmentDto>? = null,
    extras: Map<String, JsonElement> = emptyMap(),
): TrackerDto = TrackerDto(
    id = id,
    name = name,
    category = category,
    type = type,
    polarity = polarity,
    scheduleHistory = scheduleHistory,
    targetHistory = targetHistory,
    extras = JsonObject(extras),
)

/** A tracker carrying the legacy `frequency` / `weeklyDay` fields. */
internal fun legacyTracker(
    id: String = "t",
    frequency: String,
    weeklyDay: Int? = null,
    name: String? = null,
    polarity: String? = null,
    scheduleHistory: List<ScheduleSegmentDto>? = null,
): TrackerDto = tracker(
    id = id,
    name = name,
    polarity = polarity,
    scheduleHistory = scheduleHistory,
    extras = buildMap {
        put("frequency", JsonPrimitive(frequency))
        weeklyDay?.let { put("weeklyDay", JsonPrimitive(it)) }
    },
)

internal fun schedule(effectiveFrom: DateString, vararg days: Int): ScheduleSegmentDto =
    ScheduleSegmentDto(effectiveFrom, days.toList())

internal fun schedule(effectiveFrom: DateString, days: List<Int>): ScheduleSegmentDto =
    ScheduleSegmentDto(effectiveFrom, days)

internal fun targetSegment(effectiveFrom: DateString, target: TargetDto?): TargetSegmentDto =
    TargetSegmentDto(effectiveFrom, target)

internal fun target(min: Number? = null, max: Number? = null): TargetDto =
    TargetDto(min = min?.toDouble(), max = max?.toDouble())

internal fun entry(value: JsonElement? = null, completed: Boolean? = null): EntryDto =
    EntryDto(value = value, completed = completed)

/** A JSON number, as the wire carries entry values. */
internal fun num(value: Number): JsonPrimitive = JsonPrimitive(value)

internal fun text(value: String): JsonPrimitive = JsonPrimitive(value)
