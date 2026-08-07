package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One tracker's value for one day.
 *
 * [value] stays a raw [JsonElement] because the field is polymorphic by design:
 * a number for quantifiable and evaluation trackers, a string for notes, null
 * for a bare checkbox. Coercing it to a typed union here would lose the
 * server's exact literal on the way back out.
 */
@Serializable
data class EntryDto(
    val value: JsonElement? = null,
    val completed: Boolean? = null,
    val lastModifiedAt: SyncStamp? = null,
)

/**
 * `GET /api/journal/sync/delta`. Every list defaults to empty so a server that
 * omits a section (or a hand-written test payload) still decodes.
 *
 * [serverTime] is the next watermark. The server always sends it; it is
 * nullable here only so a malformed response degrades to the client clock
 * instead of throwing.
 */
@Serializable
data class DeltaResponseDto(
    val config: List<TrackerDto> = emptyList(),
    val days: Map<DateString, Map<String, EntryDto>> = emptyMap(),
    val deletedTrackers: List<String> = emptyList(),
    val serverTime: SyncStamp? = null,
)

/** An upload the server took, with the new opaque token to store as the base. */
@Serializable
data class AcceptedTrackerDto(val id: String, val lastModifiedAt: SyncStamp)

@Serializable
data class AcceptedEntryDto(val date: DateString, val trackerId: String, val lastModifiedAt: SyncStamp)

/**
 * An upload the server refused. [serverRow] carries its current state so the
 * client recovers in the same cycle instead of waiting for the next delta;
 * it is absent when the row does not exist server-side (`errorKind = "missing"`).
 */
@Serializable
data class RejectedTrackerDto(
    val id: String,
    val errorKind: String? = null,
    val serverRow: TrackerDto? = null,
)

@Serializable
data class RejectedEntryDto(
    val date: DateString,
    val trackerId: String,
    val errorKind: String? = null,
    val serverRow: EntryDto? = null,
)

/** `POST /api/journal/sync/update`. Accepted and rejected are both *settled*. */
@Serializable
data class UpdateResponseDto(
    val serverTime: SyncStamp? = null,
    val acceptedTrackers: List<AcceptedTrackerDto> = emptyList(),
    val acceptedEntries: List<AcceptedEntryDto> = emptyList(),
    val rejectedTrackers: List<RejectedTrackerDto> = emptyList(),
    val rejectedEntries: List<RejectedEntryDto> = emptyList(),
)
