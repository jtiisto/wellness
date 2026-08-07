package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The tracker serializer's job is to lose nothing. These pin the two ways it
 * could: silently dropping a field the server sent, or emitting one the server
 * reserves.
 */
class TrackerDtoSerializerTest {

    private val json = WellnessJson

    private fun decode(text: String): TrackerDto = json.decodeFromString(TrackerDto.serializer(), text)

    private fun encode(tracker: TrackerDto): JsonObject =
        json.encodeToJsonElement(TrackerDto.serializer(), tracker).jsonObject

    @Test
    @DisplayName("typed fields map to properties and everything else lands in extras")
    fun typedAndExtras() {
        val tracker = decode(
            """
            {"id":"t1","name":"Water","category":"Habits","type":"quantifiable",
             "lastModifiedAt":"s1","polarity":"positive",
             "scheduleHistory":[{"effectiveFrom":"2026-01-01","days":[1,3]}],
             "targetHistory":[{"effectiveFrom":"2026-01-01","target":{"min":1000}}],
             "unit":"ml","defaultValue":250,"accumulator":true}
            """.trimIndent(),
        )

        assertEquals("t1", tracker.id)
        assertEquals("Water", tracker.name)
        assertEquals("Habits", tracker.category)
        assertEquals("quantifiable", tracker.type)
        assertEquals("s1", tracker.lastModifiedAt)
        assertEquals("positive", tracker.polarity)
        assertEquals(listOf(ScheduleSegmentDto("2026-01-01", listOf(1, 3))), tracker.scheduleHistory)
        assertEquals(listOf(TargetSegmentDto("2026-01-01", TargetDto(min = 1000.0))), tracker.targetHistory)
        assertEquals(
            buildJsonObject { put("unit", "ml"); put("defaultValue", 250); put("accumulator", true) },
            tracker.extras,
        )
    }

    @Test
    @DisplayName("reserved keys with no typed field are dropped on decode and never re-emitted")
    fun reservedKeysDropped() {
        val tracker = decode(
            """
            {"id":"t1","name":"Water","target":{"min":1},"_version":4,"_baseVersion":3,
             "_baseLastModifiedAt":"s0","_lastModifiedBy":"phone","_lastModifiedAt":"s0",
             "_deleted":true,"keeper":"yes"}
            """.trimIndent(),
        )

        assertEquals(buildJsonObject { put("keeper", "yes") }, tracker.extras)
        // Most of all `_deleted`: a local pending delete lives in the entity
        // column, and a stray copy here would upload a delete nobody asked for.
        val encoded = encode(tracker)
        for (key in TRACKER_RESERVED_KEYS - setOf("id", "name")) {
            assertFalse(key in encoded, "reserved key $key leaked back onto the wire")
        }
    }

    @Test
    @DisplayName("legacy frequency/weeklyDay ride through extras untouched by the serializer")
    fun legacyFieldsRideInExtras() {
        val tracker = decode("""{"id":"t1","name":"Fast","frequency":"weekly","weeklyDay":3}""")

        assertEquals(
            buildJsonObject { put("frequency", "weekly"); put("weeklyDay", 3) },
            tracker.extras,
        )
        assertEquals(tracker.extras["weeklyDay"], encode(tracker)["weeklyDay"])
    }

    @Test
    @DisplayName("an unknown nested object survives a round trip verbatim")
    fun unknownNestedObjectSurvives() {
        val text = """{"id":"t1","futureThing":{"a":[1,2,{"b":null}],"c":"x"}}"""

        assertEquals(json.parseToJsonElement(text).jsonObject, encode(decode(text)))
    }

    @Test
    @DisplayName("a typed field sent as explicit null decodes to null and is omitted on encode")
    fun explicitNullTypedField() {
        val tracker = decode("""{"id":"t1","name":null,"scheduleHistory":null,"polarity":null}""")

        assertNull(tracker.name)
        assertNull(tracker.scheduleHistory)
        assertNull(tracker.polarity)
        assertEquals(buildJsonObject { put("id", "t1") }, encode(tracker))
    }

    @Test
    @DisplayName("a null-valued extra is preserved: the server sent it, so it stays")
    fun explicitNullExtraPreserved() {
        val tracker = decode("""{"id":"t1","unit":null}""")

        assertEquals(JsonNull, tracker.extras["unit"])
        assertEquals(JsonNull, encode(tracker)["unit"])
    }

    @Test
    @DisplayName("the server's deleted flag round-trips; it only ever arrives on a rejected serverRow")
    fun deletedFlagRoundTrips() {
        assertEquals(true, decode("""{"id":"t1","deleted":true}""").deleted)
        assertEquals(false, decode("""{"id":"t1","deleted":false}""").deleted)
        assertEquals(JsonPrimitive(false), encode(TrackerDto(id = "t1", deleted = false))["deleted"])
    }

    @Test
    @DisplayName("a whole target encodes as an integer, matching what the server stored")
    fun wholeTargetsStayIntegers() {
        val text = """{"id":"t1","targetHistory":[{"effectiveFrom":"2026-01-01","target":{"min":1000,"max":2000}}]}"""

        assertEquals(json.parseToJsonElement(text).jsonObject, encode(decode(text)))
    }

    @Test
    @DisplayName("a fractional target keeps its fraction")
    fun fractionalTargetsSurvive() {
        val text = """{"id":"t1","targetHistory":[{"effectiveFrom":"2026-01-01","target":{"max":7.5}}]}"""

        assertEquals(json.parseToJsonElement(text).jsonObject, encode(decode(text)))
    }

    @Test
    @DisplayName("a cleared target segment keeps its explicit null: absent and null are different")
    fun clearedTargetSegmentKeepsNull() {
        val text = """{"id":"t1","targetHistory":[{"effectiveFrom":"2026-02-01","target":null}]}"""

        val tracker = decode(text)

        assertEquals(listOf(TargetSegmentDto("2026-02-01", null)), tracker.targetHistory)
        assertEquals(json.parseToJsonElement(text).jsonObject, encode(tracker))
    }

    @Test
    @DisplayName("an empty schedule (paused) is not the same as no schedule")
    fun pausedScheduleSurvives() {
        val paused = decode("""{"id":"t1","scheduleHistory":[{"effectiveFrom":"2026-01-01","days":[]}]}""")

        assertEquals(listOf(ScheduleSegmentDto("2026-01-01", emptyList())), paused.scheduleHistory)
        assertTrue("scheduleHistory" in encode(paused))
        assertFalse("scheduleHistory" in encode(decode("""{"id":"t1"}""")))
    }

    @Test
    @DisplayName("a tracker object with no id is a decode failure, not a silent empty id")
    fun missingIdThrows() {
        org.junit.jupiter.api.assertThrows<Exception> { decode("""{"name":"nameless"}""") }
    }
}
