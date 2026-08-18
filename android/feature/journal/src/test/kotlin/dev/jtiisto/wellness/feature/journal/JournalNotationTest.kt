package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.journal.DayDot
import dev.jtiisto.wellness.core.data.journal.DotState
import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.SCHEDULE_GENESIS_DATE
import dev.jtiisto.wellness.core.data.journal.TargetDto
import dev.jtiisto.wellness.core.data.journal.TargetSegmentDto
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.dayStatus
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Logbook's journal notation: the week-mark grammar, the rollup cluster, the
 * header eyebrow and the strip's day initial.
 *
 * These are the decisions the composables are no longer allowed to make, so this
 * is where they are pinned. Everything here is pure; the wiring that feeds it
 * (`hasEntry`, the row's class) is pinned in `JournalUiStateTest`.
 */
class JournalNotationTest {

    private val uk = Locale.UK

    // ---- the row's class ---------------------------------------------------

    @Test
    @DisplayName("a goal makes a habit, a negative polarity an avoidance, neither an observation")
    fun classes() {
        assertEquals(TrackerClass.OBSERVATION, classOf(tracker("t")))
        assertEquals(TrackerClass.HABIT, classOf(tracker("t", polarity = "positive")))
        assertEquals(TrackerClass.HABIT, classOf(tracker("t", target = TargetDto(min = 8.0))))
        assertEquals(TrackerClass.AVOIDANCE, classOf(tracker("t", polarity = "negative")))
    }

    @Test
    @DisplayName("a target never turns an avoidance into a habit — the two predicates cannot disagree")
    fun avoidanceOutranksItsTarget() {
        val tracker = tracker("t", polarity = "negative", target = TargetDto(max = 2.0))
        assertEquals(TrackerClass.AVOIDANCE, classOf(tracker))
        // A neutral polarity with a target is the other side of the same rule.
        assertEquals(TrackerClass.HABIT, classOf(tracker("t", polarity = "neutral", target = TargetDto(min = 1.0))))
    }

    // ---- the week-mark grammar ---------------------------------------------

    @Test
    @DisplayName("a habit row draws the dot vocabulary, one mark per verdict")
    fun habitMarks() {
        assertEquals(WeekMark.FILLED_DOT, weekMarkFor(DotState.MET, TrackerClass.HABIT))
        assertEquals(WeekMark.HALF_DOT, weekMarkFor(DotState.PARTIAL, TrackerClass.HABIT))
        assertEquals(WeekMark.SLASHED_DOT, weekMarkFor(DotState.MISSED, TrackerClass.HABIT))
        assertEquals(WeekMark.DASH, weekMarkFor(DotState.OFF, TrackerClass.HABIT))
    }

    @Test
    @DisplayName("an avoidance row is hoops only — held, or slashed for anything short of it")
    fun avoidanceMarksAreHoops() {
        assertEquals(WeekMark.HOOP, weekMarkFor(DotState.MET, TrackerClass.AVOIDANCE))
        assertEquals(WeekMark.SLASHED_HOOP, weekMarkFor(DotState.MISSED, TrackerClass.AVOIDANCE))
        // The two-state collapse: a negative tracker carrying a range target can
        // still return PARTIAL, and there is no half-hoop to spend on it.
        assertEquals(WeekMark.SLASHED_HOOP, weekMarkFor(DotState.PARTIAL, TrackerClass.AVOIDANCE))
        // A held avoidance is never drawn as a filled "success" dot.
        for (state in DotState.entries) {
            assertTrue(
                weekMarkFor(state, TrackerClass.AVOIDANCE) != WeekMark.FILLED_DOT,
                "$state must not reach the habit's success mark",
            )
        }
        assertEquals(WeekMark.DASH, weekMarkFor(DotState.OFF, TrackerClass.AVOIDANCE))
    }

    @Test
    @DisplayName("noted is a filled diamond on every row class")
    fun notedIsAlwaysADiamond() {
        for (trackerClass in TrackerClass.entries) {
            assertEquals(WeekMark.FILLED_DIAMOND, weekMarkFor(DotState.NOTED, trackerClass), "$trackerClass")
        }
    }

    @Test
    @DisplayName("quiet is the ambiguous one: a diamond on an observation, an open dot elsewhere")
    fun quietSplitsByClass() {
        assertEquals(WeekMark.OUTLINE_DIAMOND, weekMarkFor(DotState.QUIET, TrackerClass.OBSERVATION))
        // Mid-row on a habit or avoidance this means the day was non-actionable
        // *that day* — before the tracker's target came into effect — and it
        // must not turn a run of dots into a run of diamonds.
        assertEquals(WeekMark.OPEN_DOT, weekMarkFor(DotState.QUIET, TrackerClass.HABIT))
        assertEquals(WeekMark.OPEN_DOT, weekMarkFor(DotState.QUIET, TrackerClass.AVOIDANCE))
    }

    @Test
    @DisplayName("an observation row's judged days fall through to the dots — they really were judged")
    fun observationJudgedDaysUseDots() {
        // Reachable: a target in effect earlier in the week and since cleared
        // leaves judged days behind an observation row.
        assertEquals(WeekMark.FILLED_DOT, weekMarkFor(DotState.MET, TrackerClass.OBSERVATION))
        assertEquals(WeekMark.HALF_DOT, weekMarkFor(DotState.PARTIAL, TrackerClass.OBSERVATION))
        assertEquals(WeekMark.SLASHED_DOT, weekMarkFor(DotState.MISSED, TrackerClass.OBSERVATION))
    }

    @Test
    @DisplayName("off-schedule is a dash on every row class — a non-day, not a failure")
    fun offIsAlwaysADash() {
        for (trackerClass in TrackerClass.entries) {
            assertEquals(WeekMark.DASH, weekMarkFor(DotState.OFF, trackerClass), "$trackerClass")
        }
    }

    // ---- the row's seven-day run -------------------------------------------

    @Test
    @DisplayName("the run draws in order and rings its last mark, which is the selected day")
    fun runIsOrderedAndRinged() {
        val marks = rowMarks(
            dots = listOf(dot(0, DotState.MET), dot(1, DotState.MISSED), dot(2, DotState.MET)),
            trackerClass = TrackerClass.HABIT,
            hasEntry = true,
            today = day(2),
        )

        assertEquals(listOf(day(0), day(1), day(2)), marks.map { it.date })
        assertEquals(
            listOf(WeekMark.FILLED_DOT, WeekMark.SLASHED_DOT, WeekMark.FILLED_DOT),
            marks.map { it.mark },
        )
        assertEquals(listOf(false, false, true), marks.map { it.ringed })
    }

    @Test
    @DisplayName("today with nothing logged has no verdict yet: the habit's miss draws open")
    fun todayWithoutAnEntryStaysOpen() {
        val marks = rowMarks(
            dots = listOf(dot(0, DotState.MET), dot(2, DotState.MISSED)),
            trackerClass = TrackerClass.HABIT,
            hasEntry = false,
            today = day(2),
        )

        assertEquals(WeekMark.FILLED_DOT, marks[0].mark, "a settled day is untouched")
        assertEquals(WeekMark.OPEN_DOT, marks[1].mark, "the day is not over")
        assertTrue(marks[1].ringed)
    }

    @Test
    @DisplayName("the same suspension holds an avoidance's hoop back — a held day is still unearned")
    fun todayWithoutAnEntryHoldsTheHoopBack() {
        // An unlogged avoidance reads MET all day, which is the state model
        // answering for a day nobody has finished living.
        val marks = rowMarks(
            dots = listOf(dot(2, DotState.MET)),
            trackerClass = TrackerClass.AVOIDANCE,
            hasEntry = false,
            today = day(2),
        )

        assertEquals(WeekMark.OPEN_DOT, marks.single().mark)
    }

    @Test
    @DisplayName("an entry earns the verdict back, on both classes")
    fun anEntryRestoresTheJudgedMark() {
        for ((trackerClass, expected) in listOf(
            TrackerClass.HABIT to WeekMark.SLASHED_DOT,
            TrackerClass.AVOIDANCE to WeekMark.SLASHED_HOOP,
        )) {
            val marks = rowMarks(
                dots = listOf(dot(2, DotState.MISSED)),
                trackerClass = trackerClass,
                hasEntry = true,
                today = day(2),
            )
            assertEquals(expected, marks.single().mark, "$trackerClass")
        }
    }

    @Test
    @DisplayName("a browsed past day is fully judged — the ring moves with the row, the rule does not fire")
    fun browsingAPastDayJudgesItsLastMark() {
        // The run ends on the SELECTED date, so nothing in it is today.
        val marks = rowMarks(
            dots = listOf(dot(0, DotState.MET), dot(1, DotState.MISSED)),
            trackerClass = TrackerClass.HABIT,
            hasEntry = false,
            today = day(2),
        )

        assertEquals(WeekMark.SLASHED_DOT, marks[1].mark, "a day that is over has a verdict")
        assertTrue(marks[1].ringed)
    }

    @Test
    @DisplayName("an observation row is exempt — its outline diamond already says 'not noted'")
    fun observationRowsAreExemptFromTheTodayRule() {
        val marks = rowMarks(
            dots = listOf(dot(2, DotState.QUIET)),
            trackerClass = TrackerClass.OBSERVATION,
            hasEntry = false,
            today = day(2),
        )

        assertEquals(WeekMark.OUTLINE_DIAMOND, marks.single().mark)
    }

    @Test
    @DisplayName("only a judged mark can be suspended: an off day stays a dash, a noted day a diamond")
    fun theTodayRuleOnlySuspendsVerdicts() {
        for ((state, expected) in listOf(
            DotState.OFF to WeekMark.DASH,
            DotState.NOTED to WeekMark.FILLED_DIAMOND,
            DotState.QUIET to WeekMark.OPEN_DOT,
        )) {
            val marks = rowMarks(
                dots = listOf(dot(2, state)),
                trackerClass = TrackerClass.HABIT,
                hasEntry = false,
                today = day(2),
            )
            assertEquals(expected, marks.single().mark, "$state")
        }
    }

    @Test
    @DisplayName("no dots, no marks")
    fun emptyRun() {
        assertEquals(
            emptyList<RowMark>(),
            rowMarks(emptyList(), TrackerClass.HABIT, hasEntry = false, today = day(2)),
        )
    }

    @Test
    @DisplayName("every mark has a word — shape is the only other channel the grammar has")
    fun everyMarkSpeaks() {
        assertEquals(
            listOf("done", "partial", "open", "missed", "off schedule", "held", "broken", "noted", "not noted"),
            WeekMark.entries.map { it.a11yLabel() },
        )
    }

    @Test
    @DisplayName("the run reads as one sentence, each day named by its own weekday")
    fun theRunSpeaksItsDrawnMarks() {
        // 2030-01-13..15 is Sun, Mon, Tue.
        val dots = listOf(dot(0, DotState.MET), dot(1, DotState.OFF), dot(2, DotState.MISSED))

        val judged = rowMarks(dots, TrackerClass.HABIT, hasEntry = true, today = day(2))
        assertEquals("Last 3 days: Sun done, Mon off schedule, Tue missed", describeRowMarks(judged, uk))

        // The voice speaks the drawn marks, so the today-open suspension
        // survives aloud: the suspended day says "open", never "missed".
        val suspended = rowMarks(dots, TrackerClass.HABIT, hasEntry = false, today = day(2))
        assertEquals("Last 3 days: Sun done, Mon off schedule, Tue open", describeRowMarks(suspended, uk))
    }

    @Test
    @DisplayName("no marks, nothing to say")
    fun anEmptyRunSaysNothing() {
        assertNull(describeRowMarks(emptyList(), uk))
    }

    // ---- the rollup cluster -------------------------------------------------

    @Test
    @DisplayName("habit marks are state-sorted, one per habit — settled work reads first")
    fun habitsAreStateSorted() {
        val cluster = requireNotNull(
            rollupCluster(CategoryRollup(habitsMet = 2, habitsPartial = 1, habitsNotYet = 2)),
        )

        assertEquals(
            listOf(
                WeekMark.FILLED_DOT,
                WeekMark.FILLED_DOT,
                WeekMark.HALF_DOT,
                WeekMark.OPEN_DOT,
                WeekMark.OPEN_DOT,
            ),
            cluster.habits,
        )
        assertNull(cluster.avoidance)
        assertNull(cluster.observation)
    }

    @Test
    @DisplayName("a not-yet habit is open in the cluster, never slashed — the day is still running")
    fun notYetIsNeverSlashedInTheCluster() {
        val cluster = requireNotNull(rollupCluster(CategoryRollup(habitsNotYet = 3)))

        assertEquals(List(3) { WeekMark.OPEN_DOT }, cluster.habits)
    }

    @Test
    @DisplayName("one hoop for the whole class, slashed by any break at all")
    fun avoidancesCollapseToTheirWorstState() {
        assertEquals(WeekMark.HOOP, rollupCluster(CategoryRollup(avoidances = 3))?.avoidance)
        assertEquals(
            WeekMark.SLASHED_HOOP,
            rollupCluster(CategoryRollup(avoidances = 3, avoidancesBroken = 1))?.avoidance,
            "one break slashes the class",
        )
        assertEquals(
            WeekMark.SLASHED_HOOP,
            rollupCluster(CategoryRollup(avoidances = 3, avoidancesBroken = 3))?.avoidance,
        )
    }

    @Test
    @DisplayName("the diamond fills on the first note and carries the count beside it")
    fun observationsFillOnTheFirstNote() {
        assertEquals(
            ObservationCluster(WeekMark.OUTLINE_DIAMOND, noted = 0),
            rollupCluster(CategoryRollup(observationsExpected = 2))?.observation,
        )
        assertEquals(
            ObservationCluster(WeekMark.FILLED_DIAMOND, noted = 1),
            rollupCluster(CategoryRollup(observationsExpected = 2, observationsNoted = 1))?.observation,
        )
        assertEquals(
            ObservationCluster(WeekMark.FILLED_DIAMOND, noted = 2),
            rollupCluster(CategoryRollup(observationsExpected = 2, observationsNoted = 2))?.observation,
        )
    }

    @Test
    @DisplayName("classes degrade by subtraction — an absent class leaves no placeholder")
    fun clusterDegradesBySubtraction() {
        val mixed = requireNotNull(
            rollupCluster(
                CategoryRollup(
                    habitsMet = 1,
                    habitsNotYet = 1,
                    avoidances = 1,
                    observationsExpected = 1,
                    observationsNoted = 1,
                ),
            ),
        )
        assertEquals(listOf(WeekMark.FILLED_DOT, WeekMark.OPEN_DOT), mixed.habits)
        assertEquals(WeekMark.HOOP, mixed.avoidance)
        assertEquals(ObservationCluster(WeekMark.FILLED_DIAMOND, noted = 1), mixed.observation)

        val habitsOnly = requireNotNull(rollupCluster(CategoryRollup(habitsMet = 1)))
        assertNull(habitsOnly.avoidance)
        assertNull(habitsOnly.observation)

        val avoidancesOnly = requireNotNull(rollupCluster(CategoryRollup(avoidances = 1)))
        assertEquals(emptyList<WeekMark>(), avoidancesOnly.habits)
        assertNull(avoidancesOnly.observation)

        val observationsOnly = requireNotNull(rollupCluster(CategoryRollup(observationsExpected = 1)))
        assertEquals(emptyList<WeekMark>(), observationsOnly.habits)
        assertNull(observationsOnly.avoidance)
    }

    @Test
    @DisplayName("nothing expected leaves the head bare")
    fun nothingExpectedDrawsNothing() {
        // `categoryRollup` returns null when a category asks nothing that day.
        assertNull(rollupCluster(null))
        // And a hand-built empty one must not draw a stray gap in the head.
        assertNull(rollupCluster(CategoryRollup()))
    }

    @Test
    @DisplayName("the cluster counts the rollup as-is — the rows' today-open rule stops at the row")
    fun theTodayRuleDoesNotPropagateToTheCluster() {
        // An unlogged avoidance is held as far as the day's rollup is concerned,
        // even while its own row is still drawing the open dot (see
        // todayWithoutAnEntryHoldsTheHoopBack). The cluster speaks for a
        // category, not for the one tracker whose day is still open.
        assertEquals(WeekMark.HOOP, rollupCluster(CategoryRollup(avoidances = 1))?.avoidance)
    }

    // ---- the header eyebrow -------------------------------------------------

    @Test
    @DisplayName("today leads with the word, and the tally counts entries against what is visible")
    fun todayEyebrow() {
        val eyebrow = journalEyebrow(TODAY, TODAY, LoggedTally(4, 11), locale = uk)

        assertEquals(JournalEyebrow.Today(LoggedTally(4, 11)), eyebrow)
        assertEquals("Today · 4 of 11 logged", eyebrow.label)
    }

    @Test
    @DisplayName("a browsed day swaps the word for the date and says nothing else")
    fun browsingEyebrow() {
        val eyebrow = journalEyebrow("2030-01-10", TODAY, LoggedTally(3, 9), locale = uk)

        assertEquals(JournalEyebrow.Browsing("Thu, Jan 10", LoggedTally(3, 9)), eyebrow)
        assertEquals("Thu, Jan 10 · 3 of 9 logged", eyebrow.label)
    }

    @Test
    @DisplayName("a day that asks nothing drops the tally rather than announcing zero of zero")
    fun emptyDayDropsTheTally() {
        assertEquals("Today", journalEyebrow(TODAY, TODAY, LoggedTally(0, 0), locale = uk).label)
        assertEquals(
            "Thu, Jan 10",
            journalEyebrow("2030-01-10", TODAY, LoggedTally(0, 0), locale = uk).label,
        )
    }

    @Test
    @DisplayName("nothing logged yet still reads as a count, because zero of nine is a real answer")
    fun zeroOfSomethingIsStillATally() {
        assertEquals(
            "Today · 0 of 9 logged",
            journalEyebrow(TODAY, TODAY, LoggedTally(0, 9), locale = uk).label,
        )
    }

    @Test
    @DisplayName("the tally counts entry presence, not completion, across every group")
    fun tallyCountsPresence() {
        val state = buildState(
            trackers = listOf(
                tracker("logged", category = "Morning"),
                tracker("uncommitted", category = "Morning"),
                tracker("emptied", category = "Evening"),
                tracker("bare", category = "Evening"),
            ),
            entries = mapOf(
                // Completed…
                "logged" to EntryDto(value = JsonPrimitive(1), completed = true),
                // …and a value with the box cleared, which is still something
                // written here today: presence, not completion.
                "uncommitted" to EntryDto(value = JsonPrimitive(4), completed = false),
                // A row an uncheck emptied says nothing, and counts as nothing.
                "emptied" to EntryDto(value = null, completed = false),
            ),
        )

        assertEquals(LoggedTally(logged = 2, total = 4), loggedTally(state.groups))
    }

    @Test
    @DisplayName("a day with nothing visible tallies zero of zero rather than throwing")
    fun tallyOfAnEmptyDay() {
        assertEquals(LoggedTally(0, 0), loggedTally(emptyList()))
    }

    // ---- the strip's day initial --------------------------------------------

    @Test
    @DisplayName("the strip's letter is the locale's own narrow weekday, natural-cased")
    fun dayInitials() {
        // 2030-01-07 is a Monday; seven days of it are the strip's own week.
        val initials = (7..13).map { cell("2030-01-${it.toString().padStart(2, '0')}").dayInitial(uk) }

        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), initials)
    }

    @Test
    @DisplayName("the letter comes from the locale, not from the English day name in the cell")
    fun dayInitialIsLocalized() {
        val cell = cell("2030-01-07")

        assertEquals("M", cell.dayInitial(Locale.ENGLISH))
        // German's narrow Monday is M too, so pick a locale whose alphabet moves:
        // the point is that the letter is not sliced off the cell's hard-coded
        // English `dayName`.
        assertEquals("月", cell.dayInitial(Locale.JAPANESE))
    }

    // ---- fixtures ------------------------------------------------------------

    private fun classOf(tracker: TrackerDto): TrackerClass =
        trackerClass(tracker, dayStatus(tracker, TODAY, null))

    private fun tracker(
        id: String,
        category: String = "Morning",
        polarity: String? = null,
        target: TargetDto? = null,
    ) = TrackerDto(
        id = id,
        name = id,
        category = category,
        type = if (target == null) "simple" else "quantifiable",
        polarity = polarity,
        targetHistory = target?.let { listOf(TargetSegmentDto(SCHEDULE_GENESIS_DATE, it)) },
    )

    private fun buildState(trackers: List<TrackerDto>, entries: Map<String, EntryDto>) =
        buildJournalUiState(
            trackers = trackers,
            entriesByDate = mapOf(TODAY to entries),
            selectedDate = TODAY,
            today = LocalDate.parse(TODAY),
            expandedCategories = emptySet(),
        )

    // ---- the shape legend ----------------------------------------------------

    @Test
    @DisplayName("the legend names seven marks, each with the word its own mark speaks")
    fun legendIsSelfDescribing() {
        assertEquals(JOURNAL_SHAPE_LEGEND.size, JOURNAL_SHAPE_LEGEND.toSet().size, "no mark twice")
        val words = JOURNAL_SHAPE_LEGEND.map { it.a11yLabel() }
        assertEquals(words.size, words.toSet().size, "two entries reading the same word teach nothing")
        assertEquals(
            listOf("done", "partial", "open", "missed", "held", "noted", "off schedule"),
            words,
        )
    }

    @Test
    @DisplayName("the two omitted marks are the negated forms of ones the legend already names")
    fun legendOmitsOnlyDerivedMarks() {
        val omitted = WeekMark.entries.toSet() - JOURNAL_SHAPE_LEGEND.toSet()
        // The slashed hoop is the held hoop struck through and the outline
        // diamond is the noted diamond hollowed — and by the time either appears
        // the dot half of the line has taught both modifiers. Anything else
        // falling out of the legend would be a shape with no word anywhere.
        assertEquals(setOf(WeekMark.SLASHED_HOOP, WeekMark.OUTLINE_DIAMOND), omitted)
        assertTrue(WeekMark.HOOP in JOURNAL_SHAPE_LEGEND, "the hoop teaches the slashed hoop")
        assertTrue(WeekMark.SLASHED_DOT in JOURNAL_SHAPE_LEGEND, "the slash is taught on a dot")
        assertTrue(WeekMark.FILLED_DIAMOND in JOURNAL_SHAPE_LEGEND, "the diamond teaches its hollow")
        assertTrue(WeekMark.OPEN_DOT in JOURNAL_SHAPE_LEGEND, "the hollow is taught on a dot")
    }

    @Test
    @DisplayName("the initial comes off a bare date too, and agrees with the cell's own")
    fun bareDateInitial() {
        // The strip is assembled before its cells exist, so the builder needs
        // the date form; two implementations could drift, one cannot.
        val date = "2030-01-07"
        assertEquals(cell(date).dayInitial(Locale.UK), dayInitial(date, Locale.UK))
    }

    private fun cell(date: DateString) = DateCellState(
        date = date,
        dayName = "Mon",
        initial = "M",
        dayNum = 7,
        isToday = false,
        isSelected = false,
    )

    /** Day [offset] of a three-day run ending on [TODAY]. */
    private fun day(offset: Int): DateString = "2030-01-1${offset + 3}"

    private fun dot(offset: Int, state: DotState) = DayDot(day(offset), state)

    private companion object {
        /** 2030-01-15, the last day of the [day] run. */
        const val TODAY = "2030-01-15"
    }
}
