package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.PlanBlockDto
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.RxKind
import dev.jtiisto.wellness.core.data.coach.TYPE_CHECKLIST
import dev.jtiisto.wellness.core.data.coach.TYPE_DURATION
import dev.jtiisto.wellness.core.data.coach.TYPE_WEIGHTED_TIME
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The whole-tab derivation: the calendar grid and its floor, editability, and
 * the planned day's tree of blocks, accordions and entry widgets.
 */
class CoachUiStateTest {

    private val today: LocalDate = LocalDate.parse("2026-08-08")
    private val august = YearMonth.of(2026, 8)

    @Suppress("LongParameterList")
    private fun state(
        selectedDate: DateString = today.toString(),
        viewMonth: YearMonth = august,
        plans: Map<DateString, PlanDto?> = emptyMap(),
        logs: Map<DateString, JsonObject> = emptyMap(),
        earliestDate: DateString? = null,
        hooks: WorkoutHooksState = WorkoutHooksState(),
        expanded: Set<String> = emptySet(),
        isLoading: Boolean = false,
        capture: HrCaptureState = HrCaptureState(),
    ): CoachUiState = buildCoachUiState(
        selectedDate = selectedDate,
        viewMonth = viewMonth,
        plans = plans,
        logs = logs,
        earliestDate = earliestDate,
        today = today,
        hooks = hooks,
        expandedExercises = expanded,
        isLoading = isLoading,
        capture = capture,
        locale = Locale.ENGLISH,
    )

    // ---- the live BPM chip --------------------------------------------------

    @Test
    @DisplayName("no capture, no chip — on any day, loaded or not")
    fun noChipWhenIdle() {
        assertNull(state().hr)
        assertNull(state(isLoading = true).hr)
        assertNull(state(selectedDate = "2026-08-01").hr)
    }

    @Test
    @DisplayName("a running capture puts the chip on the bar, whatever the day is showing")
    fun chipFollowsTheCapture() {
        // Deliberately on a past, read-only day: the recording is happening now,
        // and which date the calendar is parked on has nothing to do with it.
        val capture = HrCaptureState(isRunning = true, bpm = 132, deviceName = "HRM-Pro")

        val hr = requireNotNull(state(selectedDate = "2026-08-01", capture = capture).hr)

        assertEquals("132", hr.bpmText)
        assertEquals("HRM-Pro", hr.deviceName)
    }

    @Test
    @DisplayName("the sheet's link line says whether these beats belong to the workout on screen")
    fun linkFollowsTheAnchor() {
        fun linkFor(workoutDate: String?, sessionId: Long?) = state(
            hooks = WorkoutHooksState(sessionId = 7),
            capture = HrCaptureState(
                isRunning = true,
                workoutDate = workoutDate,
                workoutSessionId = sessionId,
            ),
        ).hrLink

        assertEquals(CaptureLink.THIS_WORKOUT, linkFor(today.toString(), 7))
        assertEquals(CaptureLink.OTHER_WORKOUT, linkFor("2026-08-07", 4))
        assertEquals(CaptureLink.UNANCHORED, linkFor(null, null))
    }

    @Test
    @DisplayName("with no capture there is no link either — the sheet never has one without the other")
    fun noLinkWithoutACapture() {
        val plain = state()

        assertNull(plain.hr)
        assertNull(plain.hrLink)
    }

    // ---- the calendar grid ------------------------------------------------

    @Test
    @DisplayName("the grid is always 42 cells, Sunday-first, padded from both neighbours")
    fun gridShape() {
        val calendar = state().calendar

        assertEquals(42, calendar.cells.size)
        // August 2026 starts on a Saturday, so six July days pad the first row.
        assertEquals("2026-07-26", calendar.cells.first().date)
        assertEquals("2026-09-05", calendar.cells.last().date)
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), calendar.weekdayLabels)
        assertEquals("August 2026", calendar.monthCaption)
        assertEquals(1, calendar.cells.count { it.isToday })
        assertFalse(calendar.cells.first().inViewMonth)
        assertTrue(calendar.cells.first { it.date == "2026-08-01" }.inViewMonth)
    }

    @Test
    @DisplayName("a month that starts on a Sunday still fills six rows")
    fun sundayStart() {
        // November 2026 starts on a Sunday: no leading padding at all.
        val calendar = state(viewMonth = YearMonth.of(2026, 11)).calendar

        assertEquals(42, calendar.cells.size)
        assertEquals("2026-11-01", calendar.cells.first().date)
        assertEquals("2026-12-12", calendar.cells.last().date)
    }

    @Test
    @DisplayName("cells carry the same status dots the day view would show")
    fun cellStatuses() {
        val plans = mapOf(
            today.toString() to plan(),
            "2026-08-05" to plan(),
            "2026-08-20" to plan(),
        )
        val logs = mapOf(
            "2026-08-05" to dayLog("ex_1", buildJsonObject { put("duration_min", 30) }),
        )

        val cells = state(plans = plans, logs = logs).calendar.cells.associateBy { it.date }

        assertEquals(WorkoutStatus.SCHEDULED, cells.getValue(today.toString()).status)
        assertEquals(WorkoutStatus.COMPLETED, cells.getValue("2026-08-05").status)
        assertEquals(WorkoutStatus.SCHEDULED, cells.getValue("2026-08-20").status)
        assertNull(cells.getValue("2026-08-06").status)
    }

    // ---- the earliestDate floor ---------------------------------------------

    @Test
    @DisplayName("cells below the window start are disabled and selection is refused")
    fun floorDisablesCells() {
        val calendar = state(earliestDate = "2026-08-05").calendar
        val cells = calendar.cells.associateBy { it.date }

        assertFalse(cells.getValue("2030-01-04").enabled)
        assertTrue(cells.getValue("2026-08-05").enabled)
        assertFalse(canSelectDate("2030-01-04", "2026-08-05"))
        assertTrue(canSelectDate("2026-08-05", "2026-08-05"))
        assertTrue(canSelectDate("2030-01-04", null))
    }

    @Test
    @DisplayName("paging back is refused only once the whole target month is below the floor")
    fun previousMonthBounds() {
        // The floor sits mid-July, so July is still worth reaching...
        assertEquals(YearMonth.of(2026, 7), previousMonthOrNull(august, "2026-07-15"))
        // ...and June, whose last day is below it, is not.
        assertNull(previousMonthOrNull(YearMonth.of(2026, 7), "2026-07-15"))
        // A floor on a month's last day still leaves that one day reachable.
        assertEquals(YearMonth.of(2026, 7), previousMonthOrNull(august, "2026-07-31"))
        assertNull(previousMonthOrNull(august, "2026-08-01"))
        assertEquals(YearMonth.of(2026, 7), previousMonthOrNull(august, null))
    }

    @Test
    @DisplayName("canGoPrev mirrors the paging rule; there is no forward bound")
    fun canGoPrevMirrorsTheRule() {
        assertTrue(state(earliestDate = "2026-07-15").calendar.canGoPrev)
        assertFalse(state(earliestDate = "2026-08-01").calendar.canGoPrev)
        assertTrue(state(earliestDate = null).calendar.canGoPrev)
        // Nothing caps the future: a plan three months out is worth browsing to.
        assertEquals(YearMonth.of(2027, 1), august.plusMonths(5))
    }

    @Test
    @DisplayName("the view month re-homes onto whatever date is selected")
    fun monthOfDate() {
        assertEquals(YearMonth.of(2026, 6), monthOf("2026-06-01"))
        assertEquals(YearMonth.of(2026, 12), monthOf("2026-12-31"))
    }

    // ---- editability ------------------------------------------------------------

    @Test
    @DisplayName("only today is editable; past and future are not")
    fun editability() {
        assertTrue(state().isEditable)
        assertFalse(state(selectedDate = "2026-08-07").isEditable)
        assertFalse(state(selectedDate = "2026-08-09").isEditable)
    }

    @Test
    @DisplayName("the trigger caption and dot describe the selected day")
    fun triggerCaption() {
        val plans = mapOf(today.toString() to plan())

        assertEquals("Today", state(plans = plans).dateCaption)
        assertEquals(WorkoutStatus.SCHEDULED, state(plans = plans).selectedStatus)
        assertEquals("Yesterday", state(selectedDate = "2026-08-07").dateCaption)
        assertEquals("Mon, Jun 1", state(selectedDate = "2026-06-01", viewMonth = YearMonth.of(2026, 6)).dateCaption)
    }

    // ---- the days that are neither planned nor rest ----------------------------------

    @Test
    @DisplayName("nothing loaded yet is its own state, not an empty rest day")
    fun loadingIsNotARestDay() {
        val loading = state(isLoading = true)

        assertEquals(WorkoutDayState.Loading, loading.day)
        // The calendar still draws, so the header does not jump when data lands.
        assertEquals(42, loading.calendar.cells.size)
        // And the same inputs, once loaded, ARE a rest day.
        assertTrue(state(isLoading = false).day is WorkoutDayState.Rest)
    }

    @Test
    @DisplayName("a stored plan that will not decode is reported, never shown as a rest day")
    fun undecodablePlanIsNotARestDay() {
        // The store keeps the date with a null value: the day HAS a plan, it just
        // cannot be read. Dropping the key would offer an ad-hoc Zone 2 session
        // on a day that already has a workout on it.
        val day = state(plans = mapOf(today.toString() to null)).day

        assertTrue(day is WorkoutDayState.PlanUnavailable)
        assertTrue((day as WorkoutDayState.PlanUnavailable).message.isNotBlank())
    }

    @Test
    @DisplayName("an undecodable plan still earns its calendar dot — the day is planned")
    fun undecodablePlanStillCountsAsPlanned() {
        val cells = state(plans = mapOf(today.toString() to null)).calendar.cells.associateBy { it.date }

        assertEquals(WorkoutStatus.SCHEDULED, cells.getValue(today.toString()).status)
    }

    // ---- the planned day ------------------------------------------------------------

    @Test
    @DisplayName("a past day is read-only with a lock banner and no controls")
    fun pastDayBanner() {
        val day = plannedDay(selectedDate = "2026-08-01")

        assertEquals("Past workout — read-only.", day.banner?.text)
        assertEquals(ReadOnlyBanner.Kind.PAST, day.banner?.kind)
        assertNull(day.controls)
        assertFalse(day.editable)
        assertFalse(day.feedback.editable)
    }

    @Test
    @DisplayName("a future day says when it can be logged, in the locale's date form")
    fun futureDayBanner() {
        val day = plannedDay(selectedDate = "2026-08-20")

        assertEquals("Scheduled workout — come back on Thu, Aug 20 to log it.", day.banner?.text)
        assertEquals(ReadOnlyBanner.Kind.FUTURE, day.banner?.kind)
    }

    @Test
    @DisplayName("today has no banner and its header falls back to a generic name")
    fun todayHasNoBanner() {
        val day = plannedDay(dayName = null)

        assertNull(day.banner)
        assertEquals("Workout", day.dayName)
    }

    @Test
    @DisplayName("blocks title from the block type when untitled, and carry their own timing badge")
    fun blockHeaders() {
        val plan = plan(
            blocks = listOf(
                block(blockIndex = 0, blockType = "warmup", exercises = listOf(exercise(id = "a"))),
                PlanBlockDto(
                    blockIndex = 1,
                    blockType = "conditioning",
                    title = "Finisher",
                    restGuidance = "60s between rounds",
                    rounds = 4,
                    workDurationSec = 40,
                    restDurationSec = 20,
                    exercises = listOf(exercise(id = "b")),
                ),
            ),
        )

        val day = state(plans = mapOf(today.toString() to plan)).day as WorkoutDayState.Planned

        assertEquals("warmup", day.blocks[0].title)
        assertEquals("", day.blocks[0].timing)
        assertEquals("Finisher", day.blocks[1].title)
        assertEquals("4 × 0:40/0:20", day.blocks[1].timing)
        assertEquals("60s between rounds", day.blocks[1].restGuidance)
    }

    @Test
    @DisplayName("consecutive superset labels fold into one group with a display label")
    fun supersetGrouping() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(id = "a", supersetGroup = "A"),
                        exercise(id = "b", supersetGroup = "A"),
                        exercise(id = "c"),
                    ),
                ),
            ),
        )

        val items = (state(plans = mapOf(today.toString() to plan)).day as WorkoutDayState.Planned)
            .blocks.single().items

        val group = items[0] as BlockItemState.Group
        assertEquals("A", group.label)
        assertEquals("Superset A", group.displayLabel)
        assertEquals(listOf("a", "b"), group.exercises.map { it.id })
        assertEquals("c", (items[1] as BlockItemState.Single).exercise.id)
    }

    @Test
    @DisplayName("an accordion header carries the parsed name, pills, exposure, target and progress")
    fun accordionHeader() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(
                            id = "ex_1",
                            name = "Goblet Squat (KB)",
                            targetSets = 3,
                            targetReps = "8",
                            exposure = "HEAVY",
                            guidanceNote = "Brace hard",
                        ),
                    ),
                ),
            ),
        )
        val logs = mapOf(
            today.toString() to dayLog(
                "ex_1",
                buildJsonObject {
                    put("sets", sets(loggedSet(setNum = 1, completed = true)))
                    put("user_note", "felt heavy")
                },
            ),
        )

        val row = firstRow(state(plans = mapOf(today.toString() to plan), logs = logs))

        assertEquals("Goblet Squat", row.name)
        assertEquals(listOf("KB"), row.pills)
        assertEquals("HEAVY", row.exposure)
        assertEquals("3 x 8", row.target)
        assertEquals("1/3", row.progress?.display)
        assertEquals("Brace hard", row.guidanceNote)
        assertEquals("felt heavy", row.note)
    }

    @Test
    @DisplayName("the entry widget is built only once the accordion is open")
    fun entryIsLazy() {
        val plan = plan(blocks = listOf(block(exercises = listOf(exercise(id = "ex_1", targetSets = 3)))))
        val plans = mapOf(today.toString() to plan)

        assertNull(firstRow(state(plans = plans)).entry)
        assertNotNull(firstRow(state(plans = plans, expanded = setOf("ex_1"))).entry)
    }

    @Test
    @DisplayName("each exercise type gets its own widget, with the PWA's default set counts")
    fun widgetPerType() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(id = "strength"),
                        exercise(id = "bodyweight", hideWeight = true, targetSets = 2),
                        exercise(id = "hold", type = TYPE_WEIGHTED_TIME),
                        exercise(id = "cardio", type = TYPE_DURATION, targetDurationMin = 30),
                        exercise(id = "check", type = TYPE_CHECKLIST, items = listOf("Foam roll", "Band pulls")),
                    ),
                ),
            ),
        )
        val expanded = setOf("strength", "bodyweight", "hold", "cardio", "check")
        val rows = allRows(state(plans = mapOf(today.toString() to plan), expanded = expanded))

        // `target_sets || 3` for strength, `|| 1` for a weighted hold.
        val strength = rows.getValue("strength").entry as EntryWidgetState.Sets
        assertEquals(3, strength.rows.size)
        assertEquals(listOf("weight", "reps", "rpe"), strength.columns.map { it.key })

        val bodyweight = rows.getValue("bodyweight").entry as EntryWidgetState.Sets
        assertEquals(2, bodyweight.rows.size)
        assertEquals(listOf("reps", "rpe"), bodyweight.columns.map { it.key })

        val hold = rows.getValue("hold").entry as EntryWidgetState.Sets
        assertEquals(1, hold.rows.size)
        assertEquals(listOf("weight", "duration_sec"), hold.columns.map { it.key })

        val cardio = rows.getValue("cardio").entry as EntryWidgetState.Cardio
        assertEquals("30", cardio.durationPlaceholder)

        val checklist = rows.getValue("check").entry as EntryWidgetState.Checklist
        assertEquals(listOf("Foam roll", "Band pulls"), checklist.items.map { it.item })
        assertTrue(checklist.items.none { it.checked })
    }

    @Test
    @DisplayName("a zero set count defaults like the PWA's `|| 3`, rather than rendering an empty grid")
    fun zeroTargetSetsDefaults() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(id = "strength", targetSets = 0),
                        exercise(id = "hold", type = TYPE_WEIGHTED_TIME, targetSets = 0),
                    ),
                ),
            ),
        )

        val rows = allRows(state(plans = mapOf(today.toString() to plan), expanded = setOf("strength", "hold")))

        assertEquals(3, (rows.getValue("strength").entry as EntryWidgetState.Sets).rows.size)
        assertEquals(1, (rows.getValue("hold").entry as EntryWidgetState.Sets).rows.size)
    }

    @Test
    @DisplayName("a zero cardio target hints nothing, matching `placeholder={targetMin || ''}`")
    fun zeroCardioTargetHasNoPlaceholder() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(id = "zero", type = TYPE_DURATION, targetDurationMin = 0),
                        exercise(id = "real", type = TYPE_DURATION, targetDurationMin = 30),
                    ),
                ),
            ),
        )

        val rows = allRows(state(plans = mapOf(today.toString() to plan), expanded = setOf("zero", "real")))

        assertEquals("", (rows.getValue("zero").entry as EntryWidgetState.Cardio).durationPlaceholder)
        assertEquals("30", (rows.getValue("real").entry as EntryWidgetState.Cardio).durationPlaceholder)
    }

    @Test
    @DisplayName("ghosts and the Last hint come from the previous matching session")
    fun ghostsFromHistory() {
        val plan = plan(
            blocks = listOf(
                block(exercises = listOf(exercise(id = "ex_now", canonicalSlug = "squat", targetSets = 2))),
            ),
        )
        val history = plan(
            blocks = listOf(block(exercises = listOf(exercise(id = "ex_then", canonicalSlug = "squat")))),
        )

        val ui = state(
            plans = mapOf(today.toString() to plan, "2026-08-01" to history),
            logs = mapOf(
                "2026-08-01" to dayLog(
                    "ex_then",
                    buildJsonObject { put("sets", sets(loggedSet(setNum = 1, weight = 60.0, reps = 8))) },
                ),
            ),
            expanded = setOf("ex_now"),
        )

        val entry = firstRow(ui).entry as EntryWidgetState.Sets
        assertEquals("Last · Aug 1", entry.lastPerformanceHint)
        assertEquals("60", entry.rows[0].cells[0].ghost)
        assertNull(entry.rows[1].cells[0].ghost)
    }

    @Test
    @DisplayName("cardio and checklist entries never carry ghosts")
    fun noGhostsForCardio() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        exercise(id = "cardio", type = TYPE_DURATION, canonicalSlug = "zone_2"),
                    ),
                ),
            ),
        )

        val entry = firstRow(state(plans = mapOf(today.toString() to plan), expanded = setOf("cardio"))).entry

        assertTrue(entry is EntryWidgetState.Cardio)
    }

    @Test
    @DisplayName("the prescription row keeps its rpe/load/tempo order")
    fun prescription() {
        val plan = plan(
            blocks = listOf(
                block(
                    exercises = listOf(
                        PlanExerciseDto(
                            id = "ex_1",
                            name = "Front Squat",
                            type = "strength",
                            targetRpe = "6-7",
                            targetLoad = "70%",
                            tempo = "3-1-2-0",
                        ),
                    ),
                ),
            ),
        )

        val row = firstRow(state(plans = mapOf(today.toString() to plan)))

        assertEquals(listOf(RxKind.RPE, RxKind.LOAD, RxKind.TEMPO), row.prescription.map { it.kind })
    }

    @Test
    @DisplayName("session feedback reads the stored day and follows the gate")
    fun sessionFeedback() {
        val logs = mapOf(
            today.toString() to buildJsonObject {
                put(
                    "session_feedback",
                    buildJsonObject {
                        put("pain_discomfort", "left knee")
                        put("general_notes", "good session")
                    },
                )
            },
        )

        val day = state(plans = mapOf(today.toString() to plan()), logs = logs).day as WorkoutDayState.Planned

        assertEquals("left knee", day.feedback.painDiscomfort)
        assertEquals("good session", day.feedback.generalNotes)
        assertTrue(day.feedback.editable)
    }

    // ---- the gate's effect on the tree -------------------------------------------------

    @Test
    @DisplayName("an unsatisfied gate locks blocks and feedback while the controls stay visible")
    fun gateLocksEntry() {
        val hooks = WorkoutHooksState(
            sessionId = 1,
            statusLoaded = true,
            actions = HookAvailability(start = true, end = true),
        )

        val day = state(plans = mapOf(today.toString() to plan()), hooks = hooks).day as WorkoutDayState.Planned

        assertFalse(day.gateSatisfied)
        assertFalse(day.editable)
        assertFalse(day.feedback.editable)
        assertNotNull(day.controls?.start)
        assertNotNull(day.controls?.end)
    }

    @Test
    @DisplayName("logged data satisfies the gate even before Start is pressed")
    fun dataOpensTheGate() {
        val hooks = WorkoutHooksState(
            sessionId = 1,
            statusLoaded = true,
            actions = HookAvailability(start = true, end = true),
        )
        val logs = mapOf(
            today.toString() to dayLog("ex_1", buildJsonObject { put("sets", sets(loggedSet(setNum = 1, reps = 5))) }),
        )

        val day = state(plans = mapOf(today.toString() to plan()), logs = logs, hooks = hooks).day
            as WorkoutDayState.Planned

        assertTrue(day.gateSatisfied)
        assertTrue(day.editable)
    }

    @Test
    @DisplayName("only the hooks the server has get a button, and a locked Start says so")
    fun controlsFollowAvailability() {
        val startOnly = WorkoutHooksState(
            sessionId = 1,
            statusLoaded = true,
            actions = HookAvailability(start = true, end = false),
            startState = HookButtonState.LOCKED,
        )

        val day = state(plans = mapOf(today.toString() to plan()), hooks = startOnly).day as WorkoutDayState.Planned

        assertEquals("Start Workout (locked)", day.controls?.start?.label)
        assertFalse(day.controls?.start?.enabled == true)
        assertNull(day.controls?.end)
    }

    @Test
    @DisplayName("a pending button reads Working… and cannot be pressed")
    fun pendingButton() {
        val hooks = WorkoutHooksState(
            sessionId = 1,
            statusLoaded = true,
            actions = HookAvailability(start = true),
            startState = HookButtonState.PENDING,
        )

        val start = (state(plans = mapOf(today.toString() to plan()), hooks = hooks).day as WorkoutDayState.Planned)
            .controls?.start

        assertEquals("Working…", start?.label)
        assertFalse(start?.enabled == true)
        assertFalse(start?.canFire == true)
    }

    private fun plannedDay(
        selectedDate: DateString = today.toString(),
        dayName: String? = "Push Day",
    ): WorkoutDayState.Planned = state(
        selectedDate = selectedDate,
        plans = mapOf(selectedDate to plan(dayName = dayName)),
    ).day as WorkoutDayState.Planned

    private fun firstRow(ui: CoachUiState): ExerciseRowState =
        allRows(ui).values.first()

    private fun allRows(ui: CoachUiState): Map<String, ExerciseRowState> =
        (ui.day as WorkoutDayState.Planned).blocks
            .flatMap { it.items }
            .flatMap { item ->
                when (item) {
                    is BlockItemState.Single -> listOf(item.exercise)
                    is BlockItemState.Group -> item.exercises
                }
            }
            .associateBy { it.id }
}
