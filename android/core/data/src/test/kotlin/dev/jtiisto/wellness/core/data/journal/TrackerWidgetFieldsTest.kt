package dev.jtiisto.wellness.core.data.journal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `meta_json` accessors. Everything here arrives from a server the client
 * does not fully control, so each case is a shape a row must survive: the key
 * missing, the key explicitly null, the value the wrong type, and the number
 * that arrived as a string.
 */
class TrackerWidgetFieldsTest {

    private fun withExtras(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        tracker(extras = pairs.toMap())

    // ---- unitOrNull --------------------------------------------------------

    @Test
    @DisplayName("unitOrNull: a string unit, and nothing else")
    fun unit() {
        assertEquals("g", withExtras("unit" to JsonPrimitive("g")).unitOrNull())
        assertNull(tracker().unitOrNull(), "missing")
        assertNull(withExtras("unit" to JsonNull).unitOrNull(), "explicit null")
        assertNull(withExtras("unit" to JsonPrimitive("")).unitOrNull(), "blank reads as unset, as in the PWA")
        assertNull(withExtras("unit" to JsonPrimitive(3)).unitOrNull(), "a number is not a unit label")
        assertNull(withExtras("unit" to buildJsonArray { }).unitOrNull(), "malformed")
    }

    // ---- defaultValueOrNull ------------------------------------------------

    @Test
    @DisplayName("defaultValueOrNull: numeric primitives and numeric strings")
    fun defaultValue() {
        assertEquals(30.0, withExtras("defaultValue" to JsonPrimitive(30)).defaultValueOrNull())
        assertEquals(1.5, withExtras("defaultValue" to JsonPrimitive(1.5)).defaultValueOrNull())
        assertEquals(30.0, withExtras("defaultValue" to JsonPrimitive("30")).defaultValueOrNull())
    }

    @Test
    @DisplayName("defaultValueOrNull: missing, null and malformed all read as absent")
    fun defaultValueAbsent() {
        assertNull(tracker().defaultValueOrNull(), "missing")
        assertNull(withExtras("defaultValue" to JsonNull).defaultValueOrNull(), "explicit null")
        assertNull(withExtras("defaultValue" to JsonPrimitive("abc")).defaultValueOrNull(), "non-numeric string")
        assertNull(withExtras("defaultValue" to JsonPrimitive("")).defaultValueOrNull(), "empty string")
        assertNull(withExtras("defaultValue" to JsonPrimitive(true)).defaultValueOrNull(), "boolean")
        assertNull(withExtras("defaultValue" to JsonObject(emptyMap())).defaultValueOrNull(), "object")
    }

    // ---- isAccumulator -----------------------------------------------------

    @Test
    @DisplayName("isAccumulator: a JSON boolean true, and nothing else")
    fun accumulator() {
        assertTrue(withExtras("accumulator" to JsonPrimitive(true)).isAccumulator())
        assertFalse(withExtras("accumulator" to JsonPrimitive(false)).isAccumulator())
        assertFalse(tracker().isAccumulator(), "missing")
        assertFalse(withExtras("accumulator" to JsonNull).isAccumulator(), "explicit null")
        assertFalse(
            withExtras("accumulator" to JsonPrimitive("true")).isAccumulator(),
            "the string \"true\" is not the boolean true",
        )
        assertFalse(withExtras("accumulator" to JsonPrimitive(1)).isAccumulator(), "1 is not true")
    }

    // ---- trackerType -------------------------------------------------------

    @Test
    @DisplayName("trackerType: the four wire values, with anything unknown falling back to simple")
    fun types() {
        assertEquals(TrackerType.SIMPLE, tracker(type = "simple").trackerType())
        assertEquals(TrackerType.QUANTIFIABLE, tracker(type = "quantifiable").trackerType())
        assertEquals(TrackerType.EVALUATION, tracker(type = "evaluation").trackerType())
        assertEquals(TrackerType.NOTE, tracker(type = "note").trackerType())
        assertEquals(TrackerType.SIMPLE, tracker(type = null).trackerType())
        assertEquals(TrackerType.SIMPLE, tracker(type = "something-new").trackerType())
    }
}
