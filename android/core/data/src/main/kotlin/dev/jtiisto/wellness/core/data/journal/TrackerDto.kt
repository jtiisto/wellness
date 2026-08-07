package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tracker fields the sync protocol owns, mirroring `_TRACKER_RESERVED_KEYS` in
 * the server's `journal.py`.
 *
 * The server strips exactly these before storing the rest as `meta_json`, so a
 * client that let one ride along in [TrackerDto.extras] would be uploading a
 * field the server silently drops — or worse, one it interprets. Keys here that
 * have no typed field (`target`, the `_`-prefixed legacy and protocol names)
 * are therefore dropped on decode and never emitted.
 */
internal val TRACKER_RESERVED_KEYS: Set<String> = setOf(
    "id", "name", "category", "type", "lastModifiedAt", "deleted",
    "scheduleHistory", "polarity", "target", "targetHistory",
    "_version", "_baseVersion", "_baseLastModifiedAt",
    "_lastModifiedBy", "_lastModifiedAt", "_deleted",
)

/** A weekday schedule in effect from [effectiveFrom]. 0=Sun…6=Sat; `[]` = paused. */
@kotlinx.serialization.Serializable
data class ScheduleSegmentDto(
    override val effectiveFrom: DateString,
    val days: List<Int>,
) : EffectiveDated

/**
 * A quantifiable tracker's goal range. Both bounds optional (min-only, max-only
 * and both are all meaningful).
 */
@kotlinx.serialization.Serializable(with = TargetDtoSerializer::class)
data class TargetDto(
    val min: Double? = null,
    val max: Double? = null,
)

/**
 * A target in effect from [effectiveFrom]. A null [target] is the *cleared*
 * segment the PWA writes when the user removes a goal — it is meaningful and is
 * always present on the wire, hence the hand-written serializer.
 */
@kotlinx.serialization.Serializable(with = TargetSegmentDtoSerializer::class)
data class TargetSegmentDto(
    override val effectiveFrom: DateString,
    val target: TargetDto? = null,
) : EffectiveDated

/**
 * One journal tracker in its server-facing shape.
 *
 * [extras] is the `meta_json` passthrough: `unit`, `defaultValue`,
 * `accumulator`, the legacy `frequency`/`weeklyDay` pair, and anything the
 * server grows later. Keeping it verbatim is what lets this client edit a
 * tracker it does not fully understand without destroying fields it never read.
 *
 * [deleted] is the *server's* soft-delete flag and only ever arrives on a
 * rejected upload's `serverRow` — delta config never carries deleted trackers,
 * they come back as `deletedTrackers` ids. A **local** pending delete lives in
 * the entity's `deleted` column instead, and is injected into the upload as
 * `_deleted`; it must never enter this DTO or [extras].
 */
@kotlinx.serialization.Serializable(with = TrackerDtoSerializer::class)
data class TrackerDto(
    val id: String,
    val name: String? = null,
    val category: String? = null,
    val type: String? = null,
    val lastModifiedAt: SyncStamp? = null,
    val deleted: Boolean? = null,
    val scheduleHistory: List<ScheduleSegmentDto>? = null,
    val polarity: String? = null,
    val targetHistory: List<TargetSegmentDto>? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
)

/**
 * Maps the protocol's typed tracker fields to properties and buckets every
 * other key into [TrackerDto.extras], so a decode→encode round trip reproduces
 * the server's object.
 *
 * Two documented departures from byte-for-byte identity:
 * - reserved keys with no typed field are dropped (see [TRACKER_RESERVED_KEYS]);
 * - a typed field sent as explicit `null` decodes to null and is *omitted* on
 *   encode, per the wire rule that optional fields are omitted, never null.
 */
object TrackerDtoSerializer : KSerializer<TrackerDto> {

    private val ScheduleList = ListSerializer(ScheduleSegmentDto.serializer())
    private val TargetList = ListSerializer(TargetSegmentDto.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.jtiisto.wellness.core.data.journal.TrackerDto")

    override fun deserialize(decoder: Decoder): TrackerDto {
        val input = decoder.asJsonDecoder()
        return fromJson(input.decodeJsonElement().jsonObject, input.json)
    }

    override fun serialize(encoder: Encoder, value: TrackerDto) {
        val output = encoder.asJsonEncoder()
        output.encodeJsonElement(toJson(value, output.json))
    }

    fun fromJson(obj: JsonObject, json: Json): TrackerDto = TrackerDto(
        id = obj.stringOrNull("id")
            ?: throw SerializationException("tracker object has no id: ${obj.keys}"),
        name = obj.stringOrNull("name"),
        category = obj.stringOrNull("category"),
        type = obj.stringOrNull("type"),
        lastModifiedAt = obj.stringOrNull("lastModifiedAt"),
        deleted = obj.booleanOrNull("deleted"),
        scheduleHistory = obj.notNull("scheduleHistory")?.let { json.decodeFromJsonElement(ScheduleList, it) },
        polarity = obj.stringOrNull("polarity"),
        targetHistory = obj.notNull("targetHistory")?.let { json.decodeFromJsonElement(TargetList, it) },
        extras = JsonObject(obj.filterKeys { it !in TRACKER_RESERVED_KEYS }),
    )

    /**
     * The canonical stored/uploaded form. Key order is fixed here (typed fields
     * first, extras after) because it is what `dataJson` — and therefore the
     * upload payload built from it — serializes to.
     */
    fun toJson(value: TrackerDto, json: Json): JsonObject = buildJsonObject {
        put("id", value.id)
        value.name?.let { put("name", it) }
        value.category?.let { put("category", it) }
        value.type?.let { put("type", it) }
        value.lastModifiedAt?.let { put("lastModifiedAt", it) }
        value.deleted?.let { put("deleted", it) }
        value.scheduleHistory?.let { put("scheduleHistory", json.encodeToJsonElement(ScheduleList, it)) }
        value.polarity?.let { put("polarity", it) }
        value.targetHistory?.let { put("targetHistory", json.encodeToJsonElement(TargetList, it)) }
        for ((key, element) in value.extras) {
            if (key in TRACKER_RESERVED_KEYS) continue
            put(key, element)
        }
    }
}

/**
 * Emits whole targets as integer literals (`1000`, not `1000.0`).
 *
 * The PWA writes targets straight from `parseFloat`, so the server has them
 * stored as JSON integers; re-encoding a `Double` the naive way would rewrite
 * every stored target on the next upload and break the round-trip law.
 */
object TargetDtoSerializer : KSerializer<TargetDto> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.jtiisto.wellness.core.data.journal.TargetDto")

    override fun deserialize(decoder: Decoder): TargetDto {
        val obj = decoder.asJsonDecoder().decodeJsonElement().jsonObject
        return TargetDto(min = obj.doubleOrNull("min"), max = obj.doubleOrNull("max"))
    }

    override fun serialize(encoder: Encoder, value: TargetDto) {
        encoder.asJsonEncoder().encodeJsonElement(
            buildJsonObject {
                value.min?.let { put("min", journalNumberJson(it)) }
                value.max?.let { put("max", journalNumberJson(it)) }
            },
        )
    }
}

/**
 * Always writes both keys, `target` included when it is null: a null target is
 * the PWA's "goal cleared from this date" segment, not an absent field.
 */
object TargetSegmentDtoSerializer : KSerializer<TargetSegmentDto> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.jtiisto.wellness.core.data.journal.TargetSegmentDto")

    override fun deserialize(decoder: Decoder): TargetSegmentDto {
        val input = decoder.asJsonDecoder()
        val obj = input.decodeJsonElement().jsonObject
        return TargetSegmentDto(
            effectiveFrom = obj.stringOrNull("effectiveFrom")
                ?: throw SerializationException("target segment has no effectiveFrom: ${obj.keys}"),
            target = obj.notNull("target")?.let { input.json.decodeFromJsonElement(TargetDto.serializer(), it) },
        )
    }

    override fun serialize(encoder: Encoder, value: TargetSegmentDto) {
        val output = encoder.asJsonEncoder()
        output.encodeJsonElement(
            buildJsonObject {
                put("effectiveFrom", value.effectiveFrom)
                put(
                    "target",
                    value.target?.let { output.json.encodeToJsonElement(TargetDto.serializer(), it) } ?: JsonNull,
                )
            },
        )
    }
}

private fun Decoder.asJsonDecoder(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("journal DTOs decode from JSON only")

private fun Encoder.asJsonEncoder(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("journal DTOs encode to JSON only")

private fun JsonObject.notNull(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

private fun JsonObject.stringOrNull(key: String): String? = (notNull(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? = (notNull(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.doubleOrNull(key: String): Double? = (notNull(key) as? JsonPrimitive)?.doubleOrNull
