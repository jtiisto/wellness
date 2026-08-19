package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.journal.DotState
import dev.jtiisto.wellness.core.data.journal.defaultValueOrNull
import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.EntryField
import dev.jtiisto.wellness.core.data.journal.EntryPatch
import dev.jtiisto.wellness.core.data.journal.ProgressTone
import dev.jtiisto.wellness.core.data.journal.SCHEDULE_GENESIS_DATE
import dev.jtiisto.wellness.core.data.journal.ScheduleSegmentDto
import dev.jtiisto.wellness.core.data.journal.TargetDto
import dev.jtiisto.wellness.core.data.journal.TargetSegmentDto
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import dev.jtiisto.wellness.core.ui.theme.WeekMark
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * [buildJournalUiState] and the widget action builders.
 *
 * These are the day view's real rules — visibility, the strip lock, which value
 * a field shows when nothing is stored, and what each widget writes. Everything
 * downstream of here just draws.
 */
class JournalUiStateTest {

    // 2026-08-06 is a Thursday (weekday 4).
    private val today = LocalDate.parse("2026-08-06")
    private val todayStr = "2026-08-06"
    private val yesterday = "2026-08-05"
    private val utc = ZoneId.of("UTC")

    private fun build(
        trackers: List<TrackerDto>,
        entriesByDate: Map<DateString, Map<String, EntryDto>> = emptyMap(),
        selectedDate: DateString = todayStr,
        expandedCategories: Set<String> = emptySet(),
        valueUpdatedTimes: Map<String, String> = emptyMap(),
        syncStatus: SyncStatus = SyncStatus.GREEN,
    ) = buildJournalUiState(
        trackers = trackers,
        entriesByDate = entriesByDate,
        selectedDate = selectedDate,
        today = today,
        expandedCategories = expandedCategories,
        valueUpdatedTimes = valueUpdatedTimes,
        syncStatus = syncStatus,
        zone = utc,
        locale = Locale.US,
    )

    // ---- the date strip ------------------------------------------------------

    @Test
    @DisplayName("the strip is the trailing seven days, ending today")
    fun stripShape() {
        val state = build(listOf(simple("t")))
        assertEquals(7, state.dateStrip.size)
        assertEquals("2026-07-31", state.dateStrip.first().date)
        assertEquals(todayStr, state.dateStrip.last().date)
        assertTrue(state.dateStrip.last().isToday)
        assertTrue(state.dateStrip.last().isSelected)
    }

    @Test
    @DisplayName("a selected date outside the strip is clamped to today")
    fun staleSelectionIsClamped() {
        val state = build(listOf(simple("t")), selectedDate = "2020-01-01")
        assertEquals(todayStr, state.selectedDate)
        assertTrue(state.dateStrip.last().isSelected)
    }

    // ---- visibility and empty states ----------------------------------------

    @Test
    @DisplayName("no trackers at all and none scheduled today are different empty states")
    fun twoEmptyStates() {
        assertEquals(JournalEmptyState.NO_TRACKERS, build(emptyList()).emptyState)

        val weekendOnly = simple("w").copy(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(0, 6))),
        )
        assertEquals(JournalEmptyState.NONE_SCHEDULED, build(listOf(weekendOnly)).emptyState)
    }

    @Test
    @DisplayName("the date strip renders even when there is nothing to show")
    fun stripSurvivesEmptyStates() {
        assertEquals(7, build(emptyList()).dateStrip.size)
    }

    @Test
    @DisplayName("an off-schedule tracker stays visible while it has an entry that day")
    fun offScheduleWithEntryStaysVisible() {
        val weekendOnly = simple("w").copy(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(0, 6))),
        )
        val state = build(
            trackers = listOf(weekendOnly),
            entriesByDate = mapOf(todayStr to mapOf("w" to EntryDto(completed = false))),
            expandedCategories = setOf("Habits"),
        )
        assertNull(state.emptyState)
        assertEquals(listOf("w"), state.groups.single().trackers.map { it.id })
    }

    @Test
    @DisplayName("an entry with only an explicit null value still keeps the row visible")
    fun explicitNullEntryKeepsTheRow() {
        val weekendOnly = simple("w").copy(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(0, 6))),
        )
        val state = build(
            trackers = listOf(weekendOnly),
            entriesByDate = mapOf(todayStr to mapOf("w" to EntryDto(value = JsonNull))),
            expandedCategories = setOf("Habits"),
        )
        assertNull(state.emptyState, "visibility keys off row existence, not on the value")
    }

    // ---- groups and summaries -------------------------------------------------

    @Test
    @DisplayName("categories are collapsed by default and the rollup rides both band states")
    fun collapsedByDefault() {
        val trackers = listOf(
            simple("a", category = "Habits", polarity = "positive"),
            simple("b", category = "Habits", polarity = "positive"),
        )
        val entries = mapOf(todayStr to mapOf("a" to EntryDto(completed = true)))
        val expectedRollup = CategoryRollup(habitsMet = 1, habitsNotYet = 1)

        val collapsed = build(trackers, entries)
        assertFalse(collapsed.groups.single().expanded)
        assertEquals(expectedRollup, collapsed.groups.single().rollup)

        val expanded = build(trackers, entries, expandedCategories = setOf("Habits"))
        assertTrue(expanded.groups.single().expanded)
        assertEquals(expectedRollup, expanded.groups.single().rollup, "the ring costs no width")
    }

    @Test
    @DisplayName("a category the day expects nothing of leaves its band bare")
    fun rollupAbsentWhenNothingExpected() {
        // Visible only because it was logged off-schedule — nothing was asked.
        val weekendOnly = TrackerDto(
            id = "w",
            name = "w",
            category = "Habits",
            type = "simple",
            polarity = "positive",
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(0, 6))),
        )
        val state = build(
            trackers = listOf(weekendOnly),
            entriesByDate = mapOf(todayStr to mapOf("w" to EntryDto(completed = true))),
        )
        assertEquals(1, state.groups.single().trackers.size, "the row is still shown")
        assertNull(state.groups.single().rollup)
    }

    @Test
    @DisplayName("rows are derived whether the group is open or not")
    fun rowsAlwaysDerived() {
        val state = build(listOf(simple("a", category = "Habits")))
        assertEquals(1, state.groups.single().trackers.size)
    }

    @Test
    @DisplayName("categories sort by name, and so do trackers within them")
    fun sorting() {
        val trackers = listOf(
            simple("z", name = "Zinc", category = "Supplements"),
            simple("a", name = "Ashwagandha", category = "Supplements"),
            simple("e", name = "Éclair", category = "Supplements"),
            simple("h", name = "Walk", category = "Habits"),
        )
        val state = build(trackers)
        assertEquals(listOf("Habits", "Supplements"), state.groups.map { it.name })
        // Plain compareTo at both levels: an accented name sorts by code unit,
        // which is where this deliberately departs from the PWA's localeCompare.
        assertEquals(
            listOf("Ashwagandha", "Zinc", "Éclair"),
            state.groups.last().trackers.map { it.name },
        )
    }

    @Test
    @DisplayName("a tracker with no category lands under Uncategorized")
    fun uncategorized() {
        val state = build(listOf(simple("t").copy(category = null)))
        assertEquals(listOf("Uncategorized"), state.groups.map { it.name })
    }

    // ---- row derivation --------------------------------------------------------

    @Test
    @DisplayName("the displayed value falls back stored → default → 50 for an evaluation")
    fun displayedValueFallbackChain() {
        val quantifiable = quantifiable("q", defaultValue = 30)
        val evaluation = simple("e", type = "evaluation")

        val stored = row(build(listOf(quantifiable), mapOf(todayStr to mapOf("q" to EntryDto(value = JsonPrimitive(7))))), "q")
        assertEquals("7", stored.valueText)
        assertEquals(7.0, stored.displayedNumber)

        // The server's REAL column returns integers as Python floats: a
        // delta-delivered 7.0 must still display as 7. A genuine decimal keeps
        // its fraction untouched.
        val serverFloat = row(build(listOf(quantifiable), mapOf(todayStr to mapOf("q" to EntryDto(value = JsonPrimitive(7.0))))), "q")
        assertEquals("7", serverFloat.valueText, "integral doubles render without a trailing .0")
        val fractional = row(build(listOf(quantifiable), mapOf(todayStr to mapOf("q" to EntryDto(value = JsonPrimitive(7.5))))), "q")
        assertEquals("7.5", fractional.valueText)

        val fromDefault = row(build(listOf(quantifiable)), "q")
        assertEquals("30", fromDefault.valueText, "integers render without a trailing .0")
        assertEquals(30.0, fromDefault.displayedNumber)

        val evaluationDefault = row(build(listOf(evaluation)), "e")
        assertEquals(50f, evaluationDefault.sliderValue)

        val noDefault = row(build(listOf(quantifiable("n"))), "n")
        assertEquals("", noDefault.valueText)
        assertNull(noDefault.displayedNumber)
    }

    @Test
    @DisplayName("an explicitly-null stored value falls through to the default, as absent does")
    fun explicitNullFallsBackToDefault() {
        val state = build(
            listOf(quantifiable("q", defaultValue = 30)),
            mapOf(todayStr to mapOf("q" to EntryDto(value = JsonNull))),
        )
        assertEquals("30", row(state, "q").valueText)
    }

    @Test
    @DisplayName("committed is the checkbox being genuinely true; checked can differ")
    fun committedVersusChecked() {
        val entries = mapOf(
            todayStr to mapOf(
                "a" to EntryDto(completed = true),
                "b" to EntryDto(completed = false),
                "c" to EntryDto(value = JsonPrimitive(1)),
            ),
        )
        val state = build(listOf(simple("a"), simple("b"), simple("c")), entries)
        assertTrue(row(state, "a").committed)
        assertTrue(row(state, "a").checked)
        assertFalse(row(state, "b").committed)
        assertFalse(row(state, "c").committed, "a value with no checkbox is not committed")
    }

    @Test
    @DisplayName("the target line is quantifiable-only, and the fill bar at-least-only")
    fun targetLine() {
        val atLeast = quantifiable("q", target = TargetDto(min = 150.0))
        val atMost = quantifiable("m", target = TargetDto(max = 2.0))
        val entries = mapOf(
            todayStr to mapOf(
                "q" to EntryDto(value = JsonPrimitive(75)),
                "m" to EntryDto(value = JsonPrimitive(1)),
            ),
        )
        val state = build(listOf(atLeast, atMost), entries)

        val progress = requireNotNull(row(state, "q").targetProgress)
        assertEquals(ProgressTone.PARTIAL, progress.tone)
        assertEquals(50.0, progress.fillPct)
        assertNull(row(state, "m").targetProgress?.fillPct, "at-most targets show headroom, not a bar")

        val simpleRow = row(build(listOf(simple("s"))), "s")
        assertNull(simpleRow.targetProgress)
    }

    @Test
    @DisplayName("the last-updated caption is quantifiable-only and formatted against the selected day")
    fun lastUpdatedCaption() {
        val stamps = mapOf(
            "$todayStr|q" to "2026-08-06T15:42:07Z",
            "$todayStr|s" to "2026-08-06T15:42:07Z",
        )
        val state = build(listOf(quantifiable("q"), simple("s")), valueUpdatedTimes = stamps)
        assertEquals("3:42 PM", row(state, "q").lastUpdatedCaption)
        assertNull(row(state, "s").lastUpdatedCaption, "only a quantifiable value carries a caption")

        val older = build(
            listOf(quantifiable("q")),
            valueUpdatedTimes = mapOf("$yesterday|q" to "2026-07-03T15:42:07Z"),
            selectedDate = yesterday,
        )
        assertEquals("Jul 3, 3:42 PM", row(older, "q").lastUpdatedCaption)
    }

    @Test
    @DisplayName("the dot row ends on the SELECTED date and mutes days before the local window")
    fun dotRowWiring() {
        val tracker = simple("t", polarity = "positive")
        val state = build(listOf(tracker), selectedDate = yesterday, expandedCategories = setOf("Habits"))
        val dots = row(state, "t").dots

        assertEquals(7, dots.size)
        assertEquals(yesterday, dots.last().date, "the row shows the selected day's week")
        // The window starts at today − 7 = 2026-07-30, so the two oldest dots
        // reach into pruned data and are muted rather than judged.
        assertEquals("2026-07-30", dots.first().date)
        assertEquals(DotState.MISSED, dots.first().state)

        val furtherBack = build(listOf(tracker), selectedDate = "2026-07-31")
        assertEquals(DotState.OFF, furtherBack.groups.single().trackers.single().dots.first().state)
    }

    @Test
    @DisplayName("the run is drawn as well as judged, and the drawing carries the today-open rule")
    fun rowsCarryTheirDrawnRun() {
        // Nothing logged today on a habit: the model still has to answer for the
        // day and says MISSED, but the day is not over and no verdict has been
        // earned — so the mark suspends to the open dot while the state behind
        // it does not move. The two fields exist to hold exactly this
        // disagreement.
        val open = row(build(listOf(simple("h", polarity = "positive"))), "h")
        assertEquals(DotState.MISSED, open.dots.last().state)
        assertEquals(WeekMark.OPEN_DOT, open.marks.last().mark)
        assertTrue(open.marks.last().ringed, "the run's last mark is the selected day")
        assertEquals(7, open.marks.size)

        // An entry restores the verdict, drawn and spoken alike.
        val entries = mapOf(todayStr to mapOf("h" to EntryDto(value = null, completed = true)))
        val logged = row(build(listOf(simple("h", polarity = "positive")), entriesByDate = entries), "h")
        assertEquals(WeekMark.FILLED_DOT, logged.marks.last().mark)

        // The emptiness rule composes with the suspension: a row an uncheck
        // emptied is "nothing logged" again, so today's mark returns to the
        // open dot — the retraction restores "no verdict yet". A written value
        // is an assertion, so it keeps the judged mark on the page.
        val emptied = mapOf(todayStr to mapOf("h" to EntryDto(value = null, completed = false)))
        val retracted = row(build(listOf(simple("h", polarity = "positive")), entriesByDate = emptied), "h")
        assertEquals(WeekMark.OPEN_DOT, retracted.marks.last().mark)

        val valued = mapOf(todayStr to mapOf("h" to EntryDto(value = JsonPrimitive(1), completed = false)))
        val asserted = row(build(listOf(simple("h", polarity = "positive")), entriesByDate = valued), "h")
        assertEquals(WeekMark.SLASHED_DOT, asserted.marks.last().mark)
    }

    @Test
    @DisplayName("the run's sentence speaks the drawn marks, so a suspended verdict survives aloud")
    fun rowsCarryTheirSpokenRun() {
        val open = row(build(listOf(simple("h", polarity = "positive"))), "h")
        val spoken = open.marksDescription.orEmpty()
        assertTrue(spoken.startsWith("Last 7 days: "), spoken)
        // 2026-08-06 is a Thursday, and it is the day the run ends on. The six
        // days before it are over and really were missed, so they say so — the
        // suspension is today's alone, and it is the last thing the sentence
        // says. Both halves matter: a rule that silenced the whole week would be
        // hiding the log rather than being honest about one unfinished day.
        assertTrue(spoken.endsWith("Thu open"), spoken)
        assertTrue(spoken.contains("missed"), spoken)
    }

    @Test
    @DisplayName("a tracker logged on a day it was not scheduled says so")
    fun offScheduleRowsAreFlagged() {
        // Thursday is weekday 4; a weekend-only tracker is not expected today,
        // and only shows at all because an entry exists against it.
        val weekendOnly = simple("w").copy(
            scheduleHistory = listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(0, 6))),
        )
        val entries = mapOf(todayStr to mapOf("w" to EntryDto(value = null, completed = true)))
        val state = build(listOf(weekendOnly, simple("d")), entriesByDate = entries)

        assertTrue(row(state, "w").offSchedule)
        assertFalse(row(state, "d").offSchedule, "a daily tracker is never off schedule")
    }

    @Test
    @DisplayName("the real today is hoisted, and is not the same fact as the selected day")
    fun todayIsHoisted() {
        val browsing = build(listOf(simple("t")), selectedDate = yesterday)
        assertEquals(todayStr, browsing.today)
        assertEquals(yesterday, browsing.selectedDate)
    }

    @Test
    @DisplayName("the header's eyebrow is assembled with the rest, tally and all")
    fun eyebrowIsDerived() {
        val entries = mapOf(todayStr to mapOf("a" to EntryDto(value = null, completed = true)))
        val today = build(listOf(simple("a"), simple("b")), entriesByDate = entries).eyebrow
        assertEquals(JournalEyebrow.Today(LoggedTally(logged = 1, total = 2)), today)
        assertEquals("Today · 1 of 2 logged", today.label)

        val browsing = build(listOf(simple("a")), selectedDate = yesterday).eyebrow
        assertTrue(browsing is JournalEyebrow.Browsing, browsing.toString())
    }

    @Test
    @DisplayName("the tally counts asserted entries: an unchecked, valueless row is nothing logged")
    fun tallyIgnoresEmptyRows() {
        val emptied = mapOf(todayStr to mapOf("a" to EntryDto(value = null, completed = false)))
        val state = build(listOf(simple("a"), simple("b")), entriesByDate = emptied)
        assertEquals(LoggedTally(logged = 0, total = 2), state.eyebrow.tally)
        assertFalse(row(state, "a").hasEntry, "an uncheck retracts the row's claim on the day")

        // A value keeps the row counted even with the box cleared — the value
        // is the assertion, and blanking the field is its retraction.
        val valued = mapOf(
            todayStr to mapOf("a" to EntryDto(value = JsonPrimitive(3), completed = false)),
        )
        assertTrue(row(build(listOf(simple("a")), entriesByDate = valued), "a").hasEntry)
    }

    @Test
    @DisplayName("each strip cell carries the locale's own initial, not a slice of the English name")
    fun stripCellsCarryTheirInitial() {
        val state = build(listOf(simple("t")))
        // 2026-08-06 is a Thursday: "Thu" abbreviated, "T" as a narrow initial.
        assertEquals("Thu", state.dateStrip.last().dayName)
        assertEquals("T", state.dateStrip.last().initial)
        assertEquals(7, state.dateStrip.count { it.initial.isNotEmpty() })
    }

    @Test
    @DisplayName("hasEntry is the row asserting something, not the checkbox and not mere existence")
    fun entryPresenceIsAnAssertion() {
        val entries = mapOf(
            todayStr to mapOf(
                "done" to EntryDto(value = JsonPrimitive(1), completed = true),
                // A value with the box cleared is still a claim about the day.
                "valued" to EntryDto(value = JsonPrimitive(7), completed = false),
                // A row that exists but says nothing: what an uncheck leaves
                // behind, and it must read exactly like no row at all.
                "emptied" to EntryDto(value = null, completed = false),
            ),
        )
        val state = build(
            listOf(simple("done"), simple("valued"), simple("emptied"), simple("bare")),
            entriesByDate = entries,
        )

        assertTrue(row(state, "done").hasEntry)
        assertTrue(row(state, "valued").hasEntry)
        assertFalse(row(state, "emptied").hasEntry)
        assertFalse(row(state, "bare").hasEntry)
    }

    @Test
    @DisplayName("each row is classed for the selected day, the same way the rollup classes it")
    fun rowsCarryTheirClass() {
        val state = build(
            listOf(
                simple("noticed"),
                simple("habit", polarity = "positive"),
                simple("avoided", polarity = "negative"),
                quantifiable("targeted", target = TargetDto(min = 8.0)),
            ),
        )

        assertEquals(TrackerClass.OBSERVATION, row(state, "noticed").trackerClass)
        assertEquals(TrackerClass.HABIT, row(state, "habit").trackerClass)
        assertEquals(TrackerClass.AVOIDANCE, row(state, "avoided").trackerClass)
        assertEquals(TrackerClass.HABIT, row(state, "targeted").trackerClass)
    }

    @Test
    @DisplayName("the class follows the day: a goal added today leaves yesterday an observation")
    fun classIsResolvedPerDay() {
        // The shape the config form writes when a target is added to a tracker
        // that had none: a cleared genesis segment, then the goal. It has to be
        // spelled out — `selectSegmentForDate` falls back to the *earliest*
        // segment for a pre-history date, so a lone segment dated today would
        // reach backwards and target the whole week.
        val tracker = TrackerDto(
            id = "q",
            name = "q",
            category = "Habits",
            type = "quantifiable",
            targetHistory = listOf(
                TargetSegmentDto(SCHEDULE_GENESIS_DATE, target = null),
                TargetSegmentDto(todayStr, TargetDto(min = 8.0)),
            ),
        )

        assertEquals(TrackerClass.HABIT, row(build(listOf(tracker)), "q").trackerClass)
        assertEquals(
            TrackerClass.OBSERVATION,
            row(build(listOf(tracker), selectedDate = yesterday), "q").trackerClass,
            "before the goal existed there was nothing to be on track against",
        )
    }

    @Test
    @DisplayName("widget fields come off extras: unit and the accumulator button")
    fun widgetFields() {
        val tracker = quantifiable("q", unit = "g", accumulator = true)
        val state = build(listOf(tracker))
        assertEquals("g", row(state, "q").unit)
        assertTrue(row(state, "q").isAccumulator)
        assertEquals(TrackerType.QUANTIFIABLE, row(state, "q").type)
    }

    // ---- widget actions ----------------------------------------------------------

    @Test
    @DisplayName("checking a quantifiable tracker with no value writes the default in the same patch")
    fun checkboxSeedsTheDefault() {
        val tracker = quantifiable("q", defaultValue = 30)
        val patch = checkboxPatch(tracker, entry = null, checked = true)
        assertEquals(EntryField.Set<JsonElement?>(JsonPrimitive(30)), patch.value)
        assertEquals(EntryField.Set(true), patch.completed)
    }

    @Test
    @DisplayName("checking an evaluation tracker with no default seeds 50")
    fun checkboxSeedsEvaluationMidpoint() {
        val patch = checkboxPatch(simple("e", type = "evaluation"), entry = null, checked = true)
        assertEquals(EntryField.Set<JsonElement?>(JsonPrimitive(50)), patch.value)
    }

    @Test
    @DisplayName("unchecking retracts the seeded default — the checkbox takes back what it wrote")
    fun uncheckRetractsTheSeed() {
        // The device finding behind the rule: check seeds 30, uncheck kept it,
        // and a value counts as logged all by itself — so the row (and the
        // cluster above it) read "noted" forever.
        val tracker = quantifiable("q", defaultValue = 30)
        val seeded = checkboxPatch(tracker, EntryDto(value = JsonPrimitive(30), completed = false), checked = false)
        assertEquals(EntryField.Set<JsonElement?>(null), seeded.value)
        assertEquals(EntryField.Set(false), seeded.completed)

        // The server round-trips integers as floats; the comparison is numeric.
        val floated = checkboxPatch(tracker, EntryDto(value = JsonPrimitive(30.0), completed = false), checked = false)
        assertEquals(EntryField.Set<JsonElement?>(null), floated.value)
    }

    @Test
    @DisplayName("unchecking keeps a value that differs from the seed — a typed number is an assertion")
    fun uncheckKeepsATypedValue() {
        val tracker = quantifiable("q", defaultValue = 30)
        val patch = checkboxPatch(tracker, EntryDto(value = JsonPrimitive(22), completed = true), checked = false)
        assertEquals(EntryField.Unchanged, patch.value)
        assertEquals(EntryField.Set(false), patch.completed)
    }

    @Test
    @DisplayName("unchecking a plain checkbox writes completed only — there was never a seed")
    fun uncheckSimpleTracker() {
        val patch = checkboxPatch(simple("s"), EntryDto(completed = true), checked = false)
        assertEquals(EntryField.Unchanged, patch.value)
        assertEquals(EntryField.Set(false), patch.completed)
    }

    @Test
    @DisplayName("unchecking an evaluation still at its seed clears it back to the midpoint ghost")
    fun uncheckRetractsTheEvaluationSeed() {
        val patch = checkboxPatch(
            simple("e", type = "evaluation"),
            EntryDto(value = JsonPrimitive(50), completed = false),
            checked = false,
        )
        assertEquals(EntryField.Set<JsonElement?>(null), patch.value)
    }

    @Test
    @DisplayName("checking does not overwrite a value that is already there")
    fun checkboxKeepsAnExistingValue() {
        val tracker = quantifiable("q", defaultValue = 30)
        val patch = checkboxPatch(tracker, EntryDto(value = JsonPrimitive(7)), checked = true)
        assertEquals(EntryField.Unchanged, patch.value)
        assertEquals(EntryField.Set(true), patch.completed)
    }

    @Test
    @DisplayName("an explicitly-null value is a value: checking does not seed over it")
    fun checkboxRespectsAnExplicitNull() {
        val tracker = quantifiable("q", defaultValue = 30)
        val patch = checkboxPatch(tracker, EntryDto(value = JsonNull), checked = true)
        assertEquals(EntryField.Unchanged, patch.value, "only an ABSENT value gets the default")
    }

    @Test
    @DisplayName("a tracker whose stored defaultValue is JSON null has no default to seed")
    fun checkboxWithANullDefaultValueInExtras() {
        // The config form writes `defaultValue: null` for a blank field, so this
        // is the shape a real tracker takes — not a hypothetical. It must read
        // as "no default" all the way through to the patch, not just in the
        // accessor: seeding JsonNull as a value would create an entry holding
        // nothing and flip the day's judgment.
        val tracker = TrackerDto(
            id = "q",
            name = "q",
            category = "Habits",
            type = "quantifiable",
            extras = JsonObject(mapOf("defaultValue" to JsonNull)),
        )
        assertNull(tracker.defaultValueOrNull())

        val patch = checkboxPatch(tracker, entry = null, checked = true)
        assertEquals(EntryField.Unchanged, patch.value)
        assertEquals(EntryField.Set(true), patch.completed)
    }

    @Test
    @DisplayName("an evaluation tracker with a null default still falls back to 50")
    fun evaluationNullDefaultStillSeedsMidpoint() {
        val tracker = TrackerDto(
            id = "e",
            name = "e",
            category = "Habits",
            type = "evaluation",
            extras = JsonObject(mapOf("defaultValue" to JsonNull)),
        )
        assertEquals(EntryField.Set<JsonElement?>(JsonPrimitive(50)), checkboxPatch(tracker, null, true).value)
    }

    @Test
    @DisplayName("unchecking, and checking a type with no default, write the checkbox alone")
    fun checkboxWithoutADefault() {
        val tracker = quantifiable("q", defaultValue = 30)
        assertEquals(EntryField.Unchanged, checkboxPatch(tracker, null, checked = false).value)
        assertEquals(EntryField.Unchanged, checkboxPatch(simple("s"), null, checked = true).value)
        assertEquals(EntryField.Unchanged, checkboxPatch(quantifiable("n"), null, checked = true).value)
    }

    @Test
    @DisplayName("a numeric commit writes only the value, and only on a real change")
    fun numericCommit() {
        assertEquals(EntryPatch.numeric(12.0), numericCommitPatch(7.0, "12"))
        assertEquals(EntryField.Unchanged, numericCommitPatch(7.0, "12")!!.completed)

        assertNull(numericCommitPatch(1.0, "1"), "the same number in a different spelling")
        assertNull(numericCommitPatch(30.0, "30"), "echoing back a displayed default")
        assertNull(numericCommitPatch(7.0, "abc"), "unparseable input restores the display")
        assertNull(numericCommitPatch(7.0, "1e"), "half-typed input restores the display")
        assertEquals(EntryPatch.numeric(1.5), numericCommitPatch(7.0, " 1.5 "))
    }

    @Test
    @DisplayName("emptying a numeric field clears the value back to absent")
    fun numericCommitClear() {
        assertEquals(EntryField.Set<JsonElement?>(null), numericCommitPatch(7.0, "")!!.value)
        assertNull(numericCommitPatch(null, ""), "nothing to clear")
    }

    @Test
    @DisplayName("the accumulator adds to what is displayed, allows negatives, ignores zero and junk")
    fun accumulator() {
        assertEquals(EntryPatch.numeric(32.0), accumulatorPatch(7.0, "25"))
        assertEquals(EntryPatch.numeric(25.0), accumulatorPatch(null, "25"), "a missing value counts as zero")
        assertEquals(EntryPatch.numeric(-3.0), accumulatorPatch(7.0, "-10"), "negatives take a mis-entry back")
        assertNull(accumulatorPatch(7.0, "0"), "zero just closes the sheet")
        assertNull(accumulatorPatch(7.0, ""))
        assertNull(accumulatorPatch(7.0, "lots"))
    }

    @Test
    @DisplayName("the slider writes the value alone; a note writes text and checkbox together")
    fun sliderAndNote() {
        val slider = sliderPatch(75f)
        assertEquals(EntryField.Set<JsonElement?>(JsonPrimitive(75)), slider.value)
        assertEquals(EntryField.Unchanged, slider.completed)

        assertEquals(EntryField.Set(true), EntryPatch.note("felt good").completed)
        assertEquals(EntryField.Set<JsonElement?>(JsonPrimitive("felt good")), EntryPatch.note("felt good").value)
        assertEquals(EntryField.Set(false), EntryPatch.note("   ").completed, "clearing uncommits")
    }

    @Test
    @DisplayName("blanking a note clears the value back to absent, as emptying a numeric field does")
    fun noteClear() {
        // The twin of numericCommitClear. Writing the blank text back stored
        // "", which counts as logged all by itself — the cleared note kept
        // asserting a logged day with nothing on screen to show for it.
        assertEquals(EntryField.Set<JsonElement?>(null), EntryPatch.note("").value)
        assertEquals(EntryField.Set(false), EntryPatch.note("").completed)

        // Whitespace-only is blank: isBlank, the same test that already decided
        // the checkbox half of this patch.
        assertEquals(EntryField.Set<JsonElement?>(null), EntryPatch.note("   ").value)
        assertEquals(EntryField.Set<JsonElement?>(null), EntryPatch.note("\n\t ").value)

        // Text is stored verbatim — surrounding whitespace is not trimmed away,
        // only used to decide blankness.
        assertEquals(
            EntryField.Set<JsonElement?>(JsonPrimitive(" felt good ")),
            EntryPatch.note(" felt good ").value,
        )
        assertEquals(EntryField.Set(true), EntryPatch.note(" felt good ").completed)
    }

    @Test
    @DisplayName("only quantifiable values are stamped")
    fun stampingRules() {
        assertTrue(stampsLastUpdated(TrackerType.QUANTIFIABLE))
        assertFalse(stampsLastUpdated(TrackerType.EVALUATION))
        assertFalse(stampsLastUpdated(TrackerType.NOTE))
        assertFalse(stampsLastUpdated(TrackerType.SIMPLE))
    }

    // ---- fixtures --------------------------------------------------------------

    private fun row(state: JournalUiState, id: String): TrackerRowState =
        state.groups.flatMap { it.trackers }.first { it.id == id }

    private fun simple(
        id: String,
        name: String = id,
        category: String = "Habits",
        type: String = "simple",
        polarity: String? = null,
    ) = TrackerDto(id = id, name = name, category = category, type = type, polarity = polarity)

    private fun quantifiable(
        id: String,
        unit: String? = null,
        defaultValue: Number? = null,
        accumulator: Boolean = false,
        target: TargetDto? = null,
    ) = TrackerDto(
        id = id,
        name = id,
        category = "Habits",
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
