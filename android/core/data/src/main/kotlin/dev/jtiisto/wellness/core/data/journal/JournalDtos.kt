package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One tracker's value for one day.
 *
 * [value] stays a raw [JsonElement] because the field is polymorphic by design:
 * a number for quantifiable and evaluation trackers, a string for notes, null
 * for a bare checkbox. Coercing it to a typed union here would lose the
 * server's exact literal on the way back out.
 *
 * Its **nullability carries meaning**, which is why the decoding is hand-written
 * (see [EntryDtoSerializer]): a Kotlin null means the server sent no `value`
 * key at all, while [JsonNull] means it sent one holding null. Storage mirrors
 * the distinction (`valueJson` SQL NULL versus the literal `"null"`) and the
 * checkbox depends on it — it seeds a tracker's default only into a genuinely
 * absent value, never over one the server put there.
 */
@Serializable(with = EntryDtoSerializer::class)
data class EntryDto(
    val value: JsonElement? = null,
    val completed: Boolean? = null,
    val lastModifiedAt: SyncStamp? = null,
)

/**
 * Presence-aware [EntryDto] decoding.
 *
 * The generated serializer cannot express this: with `explicitNulls = false` it
 * folds a missing key and an explicit null into the same Kotlin null, and the
 * pull would then store every server-sent null as *absent*. Reading the object
 * directly keeps the two apart. Encoding is the inverse — an absent value is
 * omitted, an explicit one is written as null — so a decode/encode round trip
 * reproduces the server's object.
 */
object EntryDtoSerializer : KSerializer<EntryDto> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.jtiisto.wellness.core.data.journal.EntryDto")

    override fun deserialize(decoder: Decoder): EntryDto {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("journal DTOs decode from JSON only")
        val obj = input.decodeJsonElement().jsonObject
        return EntryDto(
            // Deliberately not filtered for JsonNull: that IS the distinction.
            value = obj["value"],
            completed = obj.primitive("completed")?.booleanOrNull,
            lastModifiedAt = obj.primitive("lastModifiedAt")?.contentOrNull,
        )
    }

    override fun serialize(encoder: Encoder, value: EntryDto) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("journal DTOs encode to JSON only")
        output.encodeJsonElement(
            buildJsonObject {
                value.value?.let { put("value", it) }
                value.completed?.let { put("completed", JsonPrimitive(it)) }
                value.lastModifiedAt?.let { put("lastModifiedAt", JsonPrimitive(it)) }
            },
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
}

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
 *
 * [deleted] is that case's explicit instruction: "there is no such row here,
 * converge by dropping yours". Without it a `missing` rejection only cleared the
 * dirty flag, and the client kept a phantom row forever — it never re-uploaded
 * and no delta can deliver a row that does not exist.
 *
 * **Two different levels, deliberately not merged.** This flag says the server
 * has no row at all. `serverRow.deleted` says the server has a REAL row that is
 * soft-deleted, which arrives on `stale` rejections and flows through the
 * ordinary adoption path. A synthetic tombstone `serverRow` was rejected
 * server-side precisely because it would be indistinguishable from that.
 */
@Serializable
data class RejectedTrackerDto(
    val id: String,
    val errorKind: String? = null,
    val serverRow: TrackerDto? = null,
    val deleted: Boolean = false,
)

@Serializable
data class RejectedEntryDto(
    val date: DateString,
    val trackerId: String,
    val errorKind: String? = null,
    val serverRow: EntryDto? = null,
    val deleted: Boolean = false,
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
