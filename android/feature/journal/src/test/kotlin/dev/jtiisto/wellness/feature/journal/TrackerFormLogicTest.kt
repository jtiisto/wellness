package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.ALL_DAYS
import dev.jtiisto.wellness.core.data.journal.PolarityField
import dev.jtiisto.wellness.core.data.journal.SCHEDULE_GENESIS_DATE
import dev.jtiisto.wellness.core.data.journal.ScheduleSegmentDto
import dev.jtiisto.wellness.core.data.journal.TargetDto
import dev.jtiisto.wellness.core.data.journal.TargetSegmentDto
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.defaultValueOrNull
import dev.jtiisto.wellness.core.data.journal.isAccumulator
import dev.jtiisto.wellness.core.data.journal.targetForDate
import dev.jtiisto.wellness.core.data.journal.unitOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The config form: how it seeds, what it rejects, and what a submit assembles.
 *
 * The picker-seeding and type-change cases are the ones with teeth — both are
 * places where the obvious implementation quietly destroys a setting the user
 * cannot see from the form.
 */
class TrackerFormLogicTest {

    private val today = "2026-08-06"

    // ---- seeding -------------------------------------------------------------

    @Test
    @DisplayName("a new tracker starts Daily, unpaused, with no polarity or target")
    fun seedNewTracker() {
        val form = seedTrackerForm(null, existingCategories = listOf("Habits"), today = today)
        assertEquals(ALL_DAYS, form.days)
        assertFalse(form.paused)
        assertEquals("", form.polarity)
        assertEquals("", form.targetInput)
        assertEquals(TrackerType.SIMPLE, form.type)
        assertFalse(form.useNewCategory, "there are categories to pick from")
    }

    @Test
    @DisplayName("with no categories yet the form opens on the new-category text field")
    fun seedNewCategoryWhenNoneExist() {
        assertTrue(seedTrackerForm(null, emptyList(), today).useNewCategory)
    }

    @Test
    @DisplayName("editing seeds the picker from the schedule in effect today")
    fun seedFromCurrentSchedule() {
        val tracker = tracker(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(1, 3, 5))),
        )
        val form = seedTrackerForm(tracker, listOf("Habits"), today)
        assertEquals(listOf(1, 3, 5), form.days)
        assertFalse(form.paused)
    }

    @Test
    @DisplayName("a paused tracker seeds the picker from its last active days, so unpausing restores them")
    fun seedPausedFromLastActive() {
        val tracker = tracker(
            scheduleHistory = listOf(
                ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                ScheduleSegmentDto("2026-07-01", listOf(1, 2, 3, 4, 5)),
                ScheduleSegmentDto("2026-08-01", emptyList()),
            ),
        )
        val form = seedTrackerForm(tracker, listOf("Habits"), today)
        assertTrue(form.paused)
        assertEquals(listOf(1, 2, 3, 4, 5), form.days, "an empty picker would coerce back to Daily on unpause")
    }

    @Test
    @DisplayName("editing seeds the quantifiable fields and the target in effect today")
    fun seedQuantifiableFields() {
        val tracker = quantifiable(unit = "g", defaultValue = 30, accumulator = true, target = TargetDto(150.0, 170.0))
        val form = seedTrackerForm(tracker, listOf("Habits"), today)
        assertEquals(TrackerType.QUANTIFIABLE, form.type)
        assertEquals("g", form.unit)
        assertEquals("30", form.defaultValue)
        assertTrue(form.accumulator)
        assertEquals("150-170", form.targetInput, "the range form round-trips to a no-op save")
    }

    @Test
    @DisplayName("an unrecognised polarity seeds as unspecified")
    fun seedPolarity() {
        assertEquals("negative", seedTrackerForm(tracker(polarity = "negative"), listOf("x"), today).polarity)
        assertEquals("", seedTrackerForm(tracker(polarity = "sideways"), listOf("x"), today).polarity)
        assertEquals("", seedTrackerForm(tracker(), listOf("x"), today).polarity)
    }

    // ---- validation -----------------------------------------------------------

    @Test
    @DisplayName("name and category are both required")
    fun requiredFields() {
        val blank = TrackerFormState()
        assertNotNull(validateTrackerForm(blank).name)
        assertNotNull(validateTrackerForm(blank).category)
        assertFalse(validateTrackerForm(blank).isValid)

        val filled = TrackerFormState(name = "Walk", category = "Habits")
        assertTrue(validateTrackerForm(filled).isValid)

        assertNotNull(
            validateTrackerForm(filled.copy(name = "   ")).name,
            "whitespace is not a name",
        )
    }

    @Test
    @DisplayName("the category comes from whichever input is showing")
    fun categorySwap() {
        val existing = TrackerFormState(name = "Walk", category = "Habits", newCategory = "  Ignored  ")
        assertEquals("Habits", existing.resolvedCategory)

        val fresh = existing.copy(useNewCategory = true, newCategory = "  Supplements  ")
        assertEquals("Supplements", fresh.resolvedCategory, "the new category is trimmed")
        assertTrue(validateTrackerForm(fresh).isValid)

        val emptyNew = existing.copy(useNewCategory = true, newCategory = "   ")
        assertNotNull(validateTrackerForm(emptyNew).category)
    }

    @Test
    @DisplayName("target validation is live, quantifiable-only, and quiet on an empty field")
    fun targetValidation() {
        val base = TrackerFormState(name = "Protein", category = "Nutrition", type = TrackerType.QUANTIFIABLE)

        assertNull(base.targetError, "an empty target is not an error")
        assertTrue(validateTrackerForm(base).isValid)

        val bad = base.copy(targetInput = "170-150")
        assertNotNull(bad.targetError)
        assertFalse(validateTrackerForm(bad).isValid)

        val good = base.copy(targetInput = "150-170")
        assertNull(good.targetError)
        assertEquals(TargetDto(150.0, 170.0), good.targetParse.target)

        // The same text on a non-quantifiable tracker cannot block a save: the
        // form does not even show the field.
        assertTrue(validateTrackerForm(bad.copy(type = TrackerType.SIMPLE)).isValid)
    }

    @Test
    @DisplayName("the default value must be a finite number, or blank")
    fun defaultValueValidation() {
        val base = TrackerFormState(name = "Protein", category = "Nutrition", type = TrackerType.QUANTIFIABLE)

        assertNull(base.defaultValueError, "blank is a legitimate 'no default'")
        assertNull(base.defaultValueNumber)
        assertTrue(validateTrackerForm(base).isValid)

        assertEquals(30.0, base.copy(defaultValue = " 30 ").defaultValueNumber)
        assertEquals(1.5, base.copy(defaultValue = "1.5").defaultValueNumber)

        for (bad in listOf("abc", "3o", "1e999", "Infinity", "NaN")) {
            val form = base.copy(defaultValue = bad)
            assertNotNull(form.defaultValueError, "\"$bad\" should not pass as a default value")
            assertNull(form.defaultValueNumber)
            assertFalse(validateTrackerForm(form).isValid)
            assertNull(
                buildTrackerSubmission(null, form, today) { "id" },
                "\"$bad\" must block the save rather than be dropped silently",
            )
        }

        // A non-quantifiable tracker has no default-value field to be wrong about.
        assertTrue(validateTrackerForm(base.copy(defaultValue = "abc", type = TrackerType.SIMPLE)).isValid)
    }

    @Test
    @DisplayName("polarity flips how a bare target number reads")
    fun targetPolarityDefaulting() {
        val base = TrackerFormState(name = "Drinks", category = "Habits", type = TrackerType.QUANTIFIABLE)
        assertEquals(TargetDto(min = 10.0), base.copy(targetInput = "10").targetParse.target)
        assertEquals(TargetDto(max = 10.0), base.copy(targetInput = "10", polarity = "negative").targetParse.target)
    }

    @Test
    @DisplayName("toggling a weekday adds and removes it, keeping the list sorted")
    fun dayToggling() {
        val form = TrackerFormState(days = listOf(1, 3))
        assertEquals(listOf(1, 2, 3), form.toggleDay(2).days)
        assertEquals(listOf(3), form.toggleDay(1).days)
    }

    // ---- submission -------------------------------------------------------------

    @Test
    @DisplayName("an invalid form assembles nothing")
    fun invalidFormSubmitsNothing() {
        assertNull(buildTrackerSubmission(null, TrackerFormState(), today) { "new-id" })
    }

    @Test
    @DisplayName("a new tracker carries its id, base fields and quantifiable extras")
    fun newTrackerSubmission() {
        val form = TrackerFormState(
            name = "  Protein  ",
            category = "Nutrition",
            type = TrackerType.QUANTIFIABLE,
            unit = "g",
            defaultValue = "30",
            accumulator = true,
            polarity = "positive",
            targetInput = "150",
            days = ALL_DAYS,
        )
        val submission = requireNotNull(buildTrackerSubmission(null, form, today) { "new-id" })
        assertEquals("new-id", submission.id)
        assertEquals("Protein", submission.name, "the name is trimmed")

        val tracker = submission.toNewTracker()
        assertEquals("quantifiable", tracker.type)
        assertEquals("g", tracker.unitOrNull())
        assertEquals(30.0, tracker.defaultValueOrNull())
        assertTrue(tracker.isAccumulator())
        assertEquals("positive", tracker.polarity)
        assertEquals(TargetDto(min = 150.0), targetForDate(tracker, today))
        assertNull(tracker.scheduleHistory, "a new Daily tracker writes no schedule")
    }

    @Test
    @DisplayName("a blank default value is written as an explicit null, as the PWA writes it")
    fun blankDefaultValue() {
        val form = TrackerFormState(
            name = "Protein",
            category = "Nutrition",
            type = TrackerType.QUANTIFIABLE,
            defaultValue = "",
        )
        val tracker = requireNotNull(buildTrackerSubmission(null, form, today) { "id" }).toNewTracker()
        assertEquals(JsonNull, tracker.extras["defaultValue"])
        assertNull(tracker.defaultValueOrNull())
    }

    @Test
    @DisplayName("editing merges over the stored tracker and leaves unknown extras alone")
    fun editPreservesUnknownExtras() {
        val existing = quantifiable(unit = "g", defaultValue = 30).copy(
            extras = JsonObject(
                mapOf(
                    "unit" to JsonPrimitive("g"),
                    "defaultValue" to JsonPrimitive(30),
                    "somethingTheServerGrewLater" to JsonPrimitive("keep me"),
                ),
            ),
            lastModifiedAt = "s7",
        )
        val form = seedTrackerForm(existing, listOf("Habits"), today).copy(name = "Renamed", unit = "mg")
        val submission = requireNotNull(buildTrackerSubmission(existing, form, today) { "unused" })
        val updated = submission.applyTo(existing)

        assertEquals(existing.id, updated.id)
        assertEquals("Renamed", updated.name)
        assertEquals("mg", updated.unitOrNull())
        assertEquals(JsonPrimitive("keep me"), updated.extras["somethingTheServerGrewLater"])
        assertEquals("s7", updated.lastModifiedAt, "the sync token is not the form's to touch")
    }

    @Test
    @DisplayName("leaving the quantifiable type with a live target clears the target explicitly")
    fun typeChangeClearsALiveTarget() {
        val existing = quantifiable(target = TargetDto(min = 150.0))
        val form = seedTrackerForm(existing, listOf("Habits"), today).copy(type = TrackerType.SIMPLE)

        val submission = requireNotNull(buildTrackerSubmission(existing, form, today) { "unused" })
        val history = requireNotNull(submission.saveFields.targetHistory)
        assertEquals(TargetSegmentDto(today, null), history.last(), "a cleared-from-today segment")

        val updated = submission.applyTo(existing)
        assertNull(targetForDate(updated, today))
        assertEquals(TargetDto(min = 150.0), targetForDate(updated, "2026-01-01"), "past adherence is preserved")
    }

    @Test
    @DisplayName("leaving the quantifiable type with no target in effect writes no target history")
    fun typeChangeWithoutATargetWritesNothing() {
        val existing = quantifiable()
        val form = seedTrackerForm(existing, listOf("Habits"), today).copy(type = TrackerType.NOTE)
        val submission = requireNotNull(buildTrackerSubmission(existing, form, today) { "unused" })
        assertNull(submission.saveFields.targetHistory)
    }

    @Test
    @DisplayName("a non-quantifiable edit leaves the quantifiable extras exactly as stored")
    fun nonQuantifiableEditKeepsExtras() {
        val existing = quantifiable(unit = "g", defaultValue = 30)
        val form = seedTrackerForm(existing, listOf("Habits"), today).copy(type = TrackerType.SIMPLE)
        val updated = requireNotNull(buildTrackerSubmission(existing, form, today) { "x" }).applyTo(existing)
        assertEquals("simple", updated.type)
        assertEquals("g", updated.unitOrNull(), "the form stopped showing them; that is not a reason to drop them")
    }

    @Test
    @DisplayName("an untouched edit reproduces the tracker exactly, so the store can skip the write")
    fun untouchedEditIsANoOp() {
        // Every quantifiable key already present, which is the steady state
        // after one save from either client.
        val existing = quantifiable(unit = "g", defaultValue = 30, accumulator = true, target = TargetDto(150.0, 170.0))
            .copy(polarity = "positive", lastModifiedAt = "s7")

        val form = seedTrackerForm(existing, listOf("Habits"), today)
        val submission = requireNotNull(buildTrackerSubmission(existing, form, today) { "unused" })

        assertNull(submission.saveFields.scheduleHistory)
        assertNull(submission.saveFields.targetHistory)
        assertEquals(PolarityField.Set("positive"), submission.saveFields.polarity)
        // The real guarantee: opening the form and saving without edits must
        // produce a tracker equal to the stored one, so the store's identity
        // check short-circuits it and nothing goes dirty. Anything less locks
        // every past day in the date strip behind an empty upload.
        assertEquals(existing, submission.applyTo(existing))
    }

    @Test
    @DisplayName("a quantifiable tracker that never carried an accumulator key gains one — a real change")
    fun untouchedEditFillsInMissingQuantifiableKeys() {
        // Saving does write all three quantifiable keys, so a tracker created
        // before the accumulator existed genuinely changes on its first save.
        // That is not the no-op case and must not be mistaken for one.
        val existing = quantifiable(unit = "g", defaultValue = 30)
        val form = seedTrackerForm(existing, listOf("Habits"), today)
        val updated = requireNotNull(buildTrackerSubmission(existing, form, today) { "x" }).applyTo(existing)

        assertNotEquals(existing, updated)
        assertEquals(JsonPrimitive(false), updated.extras["accumulator"])
        // ...and once written, a second untouched save really is a no-op.
        val secondForm = seedTrackerForm(updated, listOf("Habits"), today)
        assertEquals(updated, requireNotNull(buildTrackerSubmission(updated, secondForm, today) { "x" }).applyTo(updated))
    }

    @Test
    @DisplayName("pausing writes an empty schedule from today; the picked days survive for the unpause")
    fun pauseWritesAnEmptySchedule() {
        val existing = tracker(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(1, 2, 3, 4, 5))),
        )
        val form = seedTrackerForm(existing, listOf("Habits"), today).copy(paused = true)
        val submission = requireNotNull(buildTrackerSubmission(existing, form, today) { "unused" })
        assertEquals(
            ScheduleSegmentDto(today, emptyList()),
            submission.saveFields.scheduleHistory?.last(),
        )
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun tracker(
        polarity: String? = null,
        scheduleHistory: List<ScheduleSegmentDto>? = null,
    ) = TrackerDto(
        id = "t1",
        name = "Walk",
        category = "Habits",
        type = "simple",
        polarity = polarity,
        scheduleHistory = scheduleHistory,
    )

    private fun quantifiable(
        unit: String? = null,
        defaultValue: Number? = null,
        accumulator: Boolean = false,
        target: TargetDto? = null,
    ) = TrackerDto(
        id = "t1",
        name = "Protein",
        category = "Nutrition",
        type = "quantifiable",
        targetHistory = target?.let { listOf(TargetSegmentDto(SCHEDULE_GENESIS_DATE, it)) },
        extras = JsonObject(
            buildMap {
                unit?.let { put("unit", JsonPrimitive(it)) }
                defaultValue?.let { put("defaultValue", JsonPrimitive(it)) }
                if (accumulator) put("accumulator", JsonPrimitive(true))
            },
        ),
    )
}
