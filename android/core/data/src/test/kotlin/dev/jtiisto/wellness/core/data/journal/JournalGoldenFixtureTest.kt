package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests against payloads the dev server actually produced
 * (`testdata/golden/journal/` — see the README there for provenance).
 *
 * Hand-written test JSON pins what we *think* the protocol is; these pin what
 * it *is*. Three laws:
 * 1. every fixture decodes;
 * 2. every tracker in them survives decode→encode unchanged, modulo the
 *    documented reserved-key drops;
 * 3. the captured upload regenerates byte-for-byte from `computeUploadPayload`.
 */
class JournalGoldenFixtureTest {

    private val json = WellnessJson

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/journal/$name")) {
            "missing golden fixture golden/journal/$name"
        }.use { it.readBytes().decodeToString() }

    private fun delta(name: String): DeltaResponseDto =
        json.decodeFromString(DeltaResponseDto.serializer(), fixture(name))

    private fun update(name: String): UpdateResponseDto =
        json.decodeFromString(UpdateResponseDto.serializer(), fixture(name))

    // ---- decode ----------------------------------------------------------

    @Test
    @DisplayName("the full delta decodes: every tracker type, entries, and a watermark")
    fun fullDeltaDecodes() {
        val response = delta("delta-full.json")

        assertEquals(
            listOf("fixture-simple", "fixture-quant", "fixture-eval", "fixture-note", "fixture-legacy"),
            response.config.map { it.id },
        )
        assertEquals(
            setOf("simple", "quantifiable", "evaluation", "note"),
            response.config.mapNotNull { it.type }.toSet(),
        )
        assertNotNull(response.serverTime)

        val quant = response.config.first { it.id == "fixture-quant" }
        assertEquals(listOf(TargetSegmentDto("2026-01-01", TargetDto(1000.0, 2000.0))), quant.targetHistory)
        assertEquals(JsonPrimitive("ml"), quant.extras["unit"])

        val eval = response.config.first { it.id == "fixture-eval" }
        assertEquals(listOf(ScheduleSegmentDto("0000-01-01", listOf(1, 3, 5))), eval.scheduleHistory)
        assertEquals("negative", eval.polarity)

        // The polymorphic entry value: null, number and string all in one pull.
        val fifth = response.days.getValue("2026-08-05")
        assertEquals(null, fifth.getValue("fixture-simple").value)
        assertEquals(true, fifth.getValue("fixture-simple").completed)
        assertEquals(750.0, fifth.getValue("fixture-quant").value?.jsonPrimitive?.content?.toDouble())
        val sixth = response.days.getValue("2026-08-06")
        assertEquals("fixture-note-text", sixth.getValue("fixture-note").value?.jsonPrimitive?.content)
        assertEquals(null, sixth.getValue("fixture-note").completed)
    }

    @Test
    @DisplayName("the incremental delta decodes to the same shape as the full one")
    fun incrementalDeltaDecodes() {
        val response = delta("delta-incremental.json")

        assertEquals(5, response.config.size)
        assertEquals(2, response.days.size)
        assertNotNull(response.serverTime)
    }

    @Test
    @DisplayName("the legacy tracker keeps frequency/weeklyDay in extras, exactly as the server returns them")
    fun legacyTrackerDecodes() {
        val tracker = json.decodeFromString(TrackerDto.serializer(), fixture("tracker-legacy.json"))

        assertEquals("fixture-legacy", tracker.id)
        assertEquals(JsonPrimitive("weekly"), tracker.extras["frequency"])
        assertEquals(JsonPrimitive(3), tracker.extras["weeklyDay"])
        // Un-normalized on the wire; normalization is the store's job.
        assertEquals(null, tracker.scheduleHistory)

        val normalized = normalizeTrackerSchedule(tracker)
        assertEquals(listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(3))), normalized.scheduleHistory)
        assertEquals(JsonObject(emptyMap()), normalized.extras)
    }

    @Test
    @DisplayName("the accepted upload response decodes to stamps for every uploaded row")
    fun acceptedResponseDecodes() {
        val response = update("update-response-accepted.json")

        assertEquals(5, response.acceptedTrackers.size)
        assertEquals(4, response.acceptedEntries.size)
        assertTrue(response.rejectedTrackers.isEmpty())
        assertTrue(response.rejectedEntries.isEmpty())
        assertNotNull(response.serverTime)
        assertTrue(response.acceptedTrackers.all { it.lastModifiedAt == response.serverTime })
    }

    @Test
    @DisplayName("the rejected upload response carries a full serverRow the client can adopt")
    fun rejectedResponseDecodes() {
        val response = update("update-response-rejected.json")

        assertTrue(response.acceptedTrackers.isEmpty())
        assertEquals(5, response.rejectedTrackers.size)
        assertEquals(4, response.rejectedEntries.size)
        assertTrue(response.rejectedTrackers.all { it.errorKind == "stale" })

        val tracker = response.rejectedTrackers.first { it.id == "fixture-quant" }
        val serverRow = requireNotNull(tracker.serverRow)
        assertEquals(false, serverRow.deleted)
        assertEquals("fixture-Quantifiable", serverRow.name)
        assertNotNull(serverRow.lastModifiedAt)

        val entry = requireNotNull(response.rejectedEntries.first().serverRow)
        assertNotNull(entry.lastModifiedAt)
    }

    // ---- the round-trip law ----------------------------------------------

    @Test
    @DisplayName("every golden tracker survives decode -> encode unchanged")
    fun trackersRoundTrip() {
        val sources = buildList {
            for (name in listOf("delta-full.json", "delta-incremental.json")) {
                addAll(json.parseToJsonElement(fixture(name)).jsonObject.getValue("config").let { config ->
                    config.jsonArrayObjects()
                })
            }
            add(json.parseToJsonElement(fixture("tracker-legacy.json")).jsonObject)
            addAll(
                json.parseToJsonElement(fixture("update-response-rejected.json"))
                    .jsonObject.getValue("rejectedTrackers").jsonArrayObjects()
                    .mapNotNull { it["serverRow"]?.takeIf { row -> row !is JsonNull }?.jsonObject },
            )
        }
        assertTrue(sources.size >= 15, "expected trackers from every fixture, got ${sources.size}")

        for (original in sources) {
            val decoded = json.decodeFromString(TrackerDto.serializer(), original.toString())
            val encoded = json.encodeToJsonElement(TrackerDto.serializer(), decoded).jsonObject
            assertEquals(expectedRoundTrip(original), encoded, "round trip changed ${original["id"]}")
        }
    }

    /**
     * What the round trip is allowed to lose: reserved keys with no typed field
     * (the server strips them too), and typed fields sent as explicit null
     * (optional wire fields are omitted, never null).
     */
    private fun expectedRoundTrip(original: JsonObject): JsonObject {
        val dropped = TRACKER_RESERVED_KEYS - TYPED_TRACKER_KEYS
        return JsonObject(
            original
                .filterKeys { it !in dropped }
                .filterNot { (key, value) -> key in TYPED_TRACKER_KEYS && value is JsonNull },
        )
    }

    // ---- the upload law --------------------------------------------------

    @Test
    @DisplayName("the captured upload regenerates byte-identically from computeUploadPayload")
    fun uploadRequestRegenerates() {
        val expected = fixture("update-request.json").trim()
        val request = json.parseToJsonElement(expected).jsonObject
        val clientId = request.getValue("clientId").jsonPrimitive.content
        // Single source for the token: these rows were stamped by the upload
        // that preceded the captured one.
        val baseToken = request.getValue("config").jsonArrayObjects().first()
            .getValue("_baseLastModifiedAt").jsonPrimitive.content

        // The tracker bodies are the server's own delta rows, re-pointed at the
        // base token — exactly what a store row holds when it goes dirty.
        val trackers = delta("delta-incremental.json").config.map { tracker ->
            UploadTracker(
                id = tracker.id,
                data = TrackerDtoSerializer.toJson(tracker.copy(lastModifiedAt = baseToken), json),
            )
        }
        val entries = listOf(
            Triple("2026-08-05", "fixture-simple", EntryDto(null, true, baseToken)),
            Triple("2026-08-05", "fixture-quant", EntryDto(JsonPrimitive(750), true, baseToken)),
            Triple("2026-08-06", "fixture-note", EntryDto(JsonPrimitive("fixture-note-text"), null, baseToken)),
            Triple("2026-08-06", "fixture-eval", EntryDto(JsonPrimitive(2), false, baseToken)),
        )

        val result = computeUploadPayload(
            meta = UploadMeta(
                clientId = clientId,
                dirtyTrackers = trackers.map { it.id },
                dirtyEntries = entries.map { (date, trackerId, _) -> entryKey(date, trackerId) },
            ),
            trackerConfig = trackers,
            dailyLogs = entries.groupBy { it.first }
                .mapValues { (_, rows) -> rows.associate { it.second to it.third } },
        )

        assertEquals(expected, json.encodeToString(JsonObject.serializer(), result.payload))
    }

    @Test
    @DisplayName("no fixture carries a protocol-reserved key the client would have to invent")
    fun uploadFixtureUsesOnlyProtocolKeys() {
        val request = json.parseToJsonElement(fixture("update-request.json")).jsonObject

        for (item in request.getValue("config").jsonArrayObjects()) {
            assertFalse("lastModifiedAt" in item, "upload echoed the reserved lastModifiedAt")
        }
        for ((_, day) in request.getValue("days").jsonObject) {
            for ((_, entry) in day.jsonObject) {
                assertTrue("value" in entry.jsonObject, "entry upload dropped its value key")
                assertTrue("completed" in entry.jsonObject, "entry upload dropped its completed key")
            }
        }
    }

    private fun JsonElement.jsonArrayObjects(): List<JsonObject> = jsonArray.map { it.jsonObject }

    private companion object {
        val TYPED_TRACKER_KEYS = setOf(
            "id", "name", "category", "type", "lastModifiedAt", "deleted",
            "scheduleHistory", "polarity", "targetHistory",
        )
    }
}
