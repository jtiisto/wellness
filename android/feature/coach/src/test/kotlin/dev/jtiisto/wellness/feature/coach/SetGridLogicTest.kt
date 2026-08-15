package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.LastPerformance
import dev.jtiisto.wellness.core.data.coach.buildColumns
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The set grid's write path, row building, and ghost placeholders. */
class SetGridLogicTest {

    private val weightRepsRpe = buildColumns(showWeight = true, showTime = false)

    // ---- pad-and-rewrite ---------------------------------------------------

    @Test
    @DisplayName("editing set 3 of an empty grid materializes sets 1 and 2 as {set_num} fillers")
    fun padsToIndex() {
        val next = withSetCell(JsonArray(emptyList()), index = 2, field = "weight", value = JsonPrimitive(60))

        assertEquals(3, next.size)
        assertEquals("1", next[0].jsonObject["set_num"]?.jsonPrimitive?.content)
        assertEquals("2", next[1].jsonObject["set_num"]?.jsonPrimitive?.content)
        // The fillers carry nothing else, which is what keeps them out of a
        // later `setHasData` reading.
        assertEquals(setOf("set_num"), next[0].jsonObject.keys)
        assertEquals("3", next[2].jsonObject["set_num"]?.jsonPrimitive?.content)
        assertEquals("60", next[2].jsonObject["weight"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("an edit merges into the existing row and rewrites the whole array")
    fun mergesIntoExistingRow() {
        val existing = sets(
            loggedSet(setNum = 1, weight = 60.0, reps = 8),
            loggedSet(setNum = 2, weight = 60.0, reps = 8),
        )

        val next = withSetCell(existing, index = 0, field = "reps", value = JsonPrimitive(10))

        assertEquals(2, next.size)
        assertEquals("60", next[0].jsonObject["weight"]?.jsonPrimitive?.content)
        assertEquals("10", next[0].jsonObject["reps"]?.jsonPrimitive?.content)
        // The untouched row survives verbatim — the array goes up whole.
        assertEquals(existing[1], next[1])
    }

    @Test
    @DisplayName("the done tick goes through the same path as a value edit")
    fun completedTick() {
        val next = withSetCell(JsonArray(emptyList()), index = 0, field = "completed", value = JsonPrimitive(true))

        assertEquals("true", next.single().jsonObject["completed"]?.jsonPrimitive?.content)
    }

    // ---- what a commit writes ------------------------------------------------

    @Test
    @DisplayName("a parsed number is written; an integral one keeps no decimal point")
    fun writesNumbers() {
        assertEquals(JsonPrimitive(24), numericCellValue(null, "24"))
        assertEquals(JsonPrimitive(22.5), numericCellValue(null, "22.5"))
        assertEquals(JsonPrimitive(24), numericCellValue(null, "  24  "))
    }

    @Test
    @DisplayName("unusable input writes nothing, so the field snaps back")
    fun unusableInput() {
        assertNull(numericCellValue(null, "abc"))
        assertNull(numericCellValue(JsonPrimitive(24), "12kg"))
        assertNull(numericCellValue(null, "NaN"))
        assertNull(numericCellValue(null, "Infinity"))
    }

    @Test
    @DisplayName("re-committing the value already stored writes nothing")
    fun unchangedWritesNothing() {
        assertNull(numericCellValue(JsonPrimitive(24), "24"))
        assertNull(numericCellValue(JsonPrimitive(24), "24.0"))
    }

    @Test
    @DisplayName("emptying a filled cell stores an explicit null; emptying an empty one writes nothing")
    fun clearing() {
        assertEquals(JsonNull, numericCellValue(JsonPrimitive(24), ""))
        // The deliberate divergence from the PWA, whose blur handler fires
        // unconditionally and would materialize set rows just for being tabbed
        // through.
        assertNull(numericCellValue(null, ""))
        assertNull(numericCellValue(JsonNull, "   "))
    }

    // ---- rows and ghosts ---------------------------------------------------------

    @Test
    @DisplayName("the grid always has exactly target_sets rows, logged or not")
    fun alwaysTargetRows() {
        val rows = buildSetRows(
            columns = weightRepsRpe,
            sets = sets(loggedSet(setNum = 1, weight = 60.0, reps = 8)),
            targetSets = 3,
            lastPerformance = null,
        )

        assertEquals(3, rows.size)
        assertEquals(listOf("60", "8", ""), rows[0].cells.map { it.text })
        assertEquals(listOf("", "", ""), rows[1].cells.map { it.text })
        assertTrue(rows.all { row -> row.cells.map { it.key } == listOf("weight", "reps", "rpe") })
    }

    @Test
    @DisplayName("more logged sets than the target still shows only the target's rows")
    fun extraLoggedSetsAreNotShown() {
        val rows = buildSetRows(
            columns = weightRepsRpe,
            sets = sets(loggedSet(setNum = 1), loggedSet(setNum = 2), loggedSet(setNum = 3)),
            targetSets = 2,
            lastPerformance = null,
        )

        assertEquals(2, rows.size)
    }

    @Test
    @DisplayName("the done tick reads only a real JSON boolean")
    fun completedFlag() {
        val rows = buildSetRows(
            columns = weightRepsRpe,
            sets = sets(
                loggedSet(setNum = 1, completed = true),
                loggedSet(setNum = 2, completed = false),
                buildJsonObject { put("completed", "true") },
            ),
            targetSets = 3,
            lastPerformance = null,
        )

        assertEquals(listOf(true, false, false), rows.map { it.completed })
    }

    @Test
    @DisplayName("ghosts are matched by set_num, not by position")
    fun ghostsMatchBySetNum() {
        // The past session's first set was blank and got filtered out, so its
        // remaining sets keep the numbers they were performed under.
        val last = LastPerformance(
            date = "2026-06-01",
            sets = listOf(
                loggedSet(setNum = 2, weight = 60.0, reps = 8),
                loggedSet(setNum = 3, weight = 62.5, reps = 6),
            ),
        )

        val rows = buildSetRows(weightRepsRpe, JsonArray(emptyList()), targetSets = 3, lastPerformance = last)

        assertEquals(listOf(null, null, null), rows[0].cells.map { it.ghost })
        assertEquals(listOf("60", "8", null), rows[1].cells.map { it.ghost })
        assertEquals(listOf("62.5", "6", null), rows[2].cells.map { it.ghost })
    }

    @Test
    @DisplayName("a ghost is a placeholder only — it never becomes the cell's value")
    fun ghostsAreNotValues() {
        val last = LastPerformance("2026-06-01", listOf(loggedSet(setNum = 1, weight = 60.0, reps = 8)))

        val rows = buildSetRows(weightRepsRpe, JsonArray(emptyList()), targetSets = 1, lastPerformance = last)

        assertEquals(listOf("", "", ""), rows.single().cells.map { it.text })
        assertEquals(listOf("60", "8", null), rows.single().cells.map { it.ghost })
    }

    @Test
    @DisplayName("ghostSetFor returns null when nothing matches")
    fun noGhost() {
        assertNull(ghostSetFor(null, 1))
        assertNull(ghostSetFor(LastPerformance("2026-06-01", listOf(loggedSet(setNum = 2))), 1))
    }

    @Test
    @DisplayName("the time-only shape drops reps and RPE entirely")
    fun timeOnlyShape() {
        val rows = buildSetRows(
            columns = buildColumns(showWeight = false, showTime = true),
            sets = sets(loggedSet(setNum = 1, durationSec = 40)),
            targetSets = 1,
            lastPerformance = null,
        )

        assertEquals(listOf("duration_sec"), rows.single().cells.map { it.key })
        assertEquals(listOf("40"), rows.single().cells.map { it.text })
    }
}
