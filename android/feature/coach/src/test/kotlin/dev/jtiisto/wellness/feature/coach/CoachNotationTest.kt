package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import dev.jtiisto.wellness.core.data.coach.TallyMarks
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Logbook's coach notation: plate assignment and its legend, the header eyebrow,
 * calendar ink marks, and the set table's provenance footer.
 *
 * These are the decisions the composables are no longer allowed to make, so this
 * is where they are pinned. Everything here is pure; the wiring that hands the
 * results to the day tree is pinned in `CoachUiStateTest`.
 */
class CoachNotationTest {

    /**
     * A locale whose short time is a 24-hour clock.
     *
     * Deliberately not `Locale.US`: CLDR 42 (JDK 20+) puts a narrow no-break
     * space before AM/PM, so pinning an en-US caption would pin a CLDR revision
     * rather than our formatting. The 12-hour path is covered below without
     * asserting on that character.
     */
    private val uk = Locale.UK
    private val utc = ZoneId.of("UTC")

    private fun eyebrow(
        date: String = TODAY,
        hasProgress: Boolean = false,
        startState: HookButtonState = HookButtonState.DEFAULT,
        startFiredAt: String? = null,
        locale: Locale = uk,
        zone: ZoneId = utc,
    ) = workoutEyebrow(
        date = date,
        today = TODAY,
        hasProgress = hasProgress,
        startState = startState,
        startFiredAt = startFiredAt,
        locale = locale,
        zone = zone,
    )

    // ---- plate assignment -----------------------------------------------------

    @Test
    @DisplayName("distinct exposures take plates in order of first appearance, repeats share one")
    fun assignmentIsPositional() {
        val tiers = assignTierPlates(listOf("Heavy", "Volume", "Heavy", "Technique"))

        assertEquals(PlateSlot.Plate(0), tiers.slotFor("Heavy"))
        assertEquals(PlateSlot.Plate(1), tiers.slotFor("Volume"))
        assertEquals(PlateSlot.Plate(2), tiers.slotFor("Technique"))
        assertEquals(3, tiers.slots.size, "the repeat took no colour of its own")
    }

    @Test
    @DisplayName("an exercise with no exposure gets no dot, and a blank one takes no colour")
    fun noExposureNoDot() {
        val tiers = assignTierPlates(listOf(null, "  ", "Heavy", null))

        assertNull(tiers.slotFor(null))
        assertNull(tiers.slotFor("Volume"), "a tier this day never mentions has no dot either")
        // The blank did not consume the first plate on its way past.
        assertEquals(PlateSlot.Plate(0), tiers.slotFor("Heavy"))
        assertEquals(listOf("Heavy"), tiers.legend.map { it.exposure })
    }

    @Test
    @DisplayName("the fifth distinct exposure falls back to ink — colours never repeat")
    fun fifthExposureIsInk() {
        val tiers = assignTierPlates(listOf("a", "b", "c", "d", "e", "f", "a"))

        assertEquals(PlateSlot.Plate(3), tiers.slotFor("d"))
        assertEquals(PlateSlot.Ink, tiers.slotFor("e"))
        assertEquals(PlateSlot.Ink, tiers.slotFor("f"))
        // The one that already had a plate keeps it, however many follow.
        assertEquals(PlateSlot.Plate(0), tiers.slotFor("a"))
    }

    @Test
    @DisplayName("the legend follows assignment order and quotes each string verbatim")
    fun legendIsOrderedAndVerbatim() {
        val tiers = assignTierPlates(listOf("top set", "back-off", "top set", "AMRAP", "z", "spare"))

        assertEquals(
            listOf(
                TierLegendEntry(PlateSlot.Plate(0), "top set"),
                TierLegendEntry(PlateSlot.Plate(1), "back-off"),
                TierLegendEntry(PlateSlot.Plate(2), "AMRAP"),
                TierLegendEntry(PlateSlot.Plate(3), "z"),
                TierLegendEntry(PlateSlot.Ink, "spare"),
            ),
            tiers.legend,
            "casing and spacing are the plan's, not ours — the header uppercases when it draws",
        )
    }

    @Test
    @DisplayName("a day with no tiers at all produces an empty legend, which is how the row disappears")
    fun noTiersNoLegend() {
        assertEquals(emptyList<TierLegendEntry>(), assignTierPlates(listOf(null, null)).legend)
        assertEquals(emptyList<TierLegendEntry>(), assignTierPlates(emptyList()).legend)
        assertEquals(TierPlates.EMPTY, assignTierPlates(emptyList()))
    }

    @Test
    @DisplayName("the ink fallback starts exactly where the palette runs out")
    fun plateCountMatchesThePalette() {
        assertEquals(PLATE_COUNT, LogbookLight.plates.size)
        assertEquals(PLATE_COUNT, LogbookDark.plates.size)
    }

    // ---- the header eyebrow ------------------------------------------------------

    @Test
    @DisplayName("a past day is read-only, whatever the hooks or the log say")
    fun pastEyebrow() {
        assertEquals(WorkoutEyebrow.Past, eyebrow(date = "2026-08-01"))
        assertEquals(
            WorkoutEyebrow.Past,
            eyebrow(date = "2026-08-01", hasProgress = true, startState = HookButtonState.FIRED),
        )
        assertEquals("Past workout · Read-only", WorkoutEyebrow.Past.label)
    }

    @Test
    @DisplayName("a future day says which day it opens on, in the locale's date form")
    fun futureEyebrow() {
        val scheduled = eyebrow(date = "2030-01-10") as WorkoutEyebrow.Scheduled

        assertEquals("Thu, Jan 10", scheduled.logOn)
        assertEquals("Scheduled · Log on Thu, Jan 10", scheduled.label)
    }

    @Test
    @DisplayName("today, untouched and unstarted, is ready to log")
    fun todayReadyEyebrow() {
        assertEquals(WorkoutEyebrow.TodayReady, eyebrow())
        assertEquals("Today · Ready to log", WorkoutEyebrow.TodayReady.label)
    }

    @Test
    @DisplayName("logged data starts the workout even with no hook — the same test the gate applies")
    fun dataAloneStartsIt() {
        val state = eyebrow(hasProgress = true)

        assertEquals(WorkoutEyebrow.InProgress(startedAt = null), state)
        assertEquals("In progress", state.label)
    }

    @Test
    @DisplayName("only a start that actually fired is a workout under way")
    fun onlyFiredStartsAreInProgress() {
        for (state in listOf(HookButtonState.FIRED, HookButtonState.LOCKED)) {
            assertTrue(
                eyebrow(startState = state) is WorkoutEyebrow.InProgress,
                "$state means the hook ran",
            )
        }
        // Pressed is not started: a FAILED hook ran nothing, and a PENDING one
        // has not run yet. The gate is open either way, and an open, empty
        // session is exactly what "Ready to log" describes — the first logged
        // set flips it regardless (see dataAloneStartsIt).
        for (state in listOf(HookButtonState.PENDING, HookButtonState.FAILED)) {
            assertEquals(
                WorkoutEyebrow.TodayReady,
                eyebrow(startState = state),
                "$state is a press, not a session under way",
            )
        }
    }

    @Test
    @DisplayName("a fired start carries its wall-clock time; without one the eyebrow just says so")
    fun startTimeCaption() {
        val timed = eyebrow(startState = HookButtonState.FIRED, startFiredAt = "2026-08-08T14:32:05Z")

        assertEquals(WorkoutEyebrow.InProgress("14:32"), timed)
        assertEquals("In progress · Started 14:32", timed.label)
        assertEquals(
            WorkoutEyebrow.InProgress(startedAt = null),
            eyebrow(startState = HookButtonState.FIRED),
            "an optimistic FIRED has not read a time back yet",
        )
    }

    @Test
    @DisplayName("the caption is the device's wall clock, not the server's UTC")
    fun startTimeIsLocal() {
        val eastern = eyebrow(
            startState = HookButtonState.FIRED,
            startFiredAt = "2026-08-08T14:32:05Z",
            zone = ZoneId.of("America/New_York"),
        )

        assertEquals(WorkoutEyebrow.InProgress("10:32"), eastern)
    }

    @Test
    @DisplayName("a 12-hour locale gets a 12-hour caption")
    fun startTimeFollowsTheLocale() {
        val american = eyebrow(
            startState = HookButtonState.FIRED,
            startFiredAt = "2026-08-08T14:32:05Z",
            locale = Locale.US,
        ) as WorkoutEyebrow.InProgress

        // Asserted around the separator rather than through it: recent CLDR puts
        // a narrow no-break space before the meridiem and that is the platform's
        // business, not this derivation's.
        val started = requireNotNull(american.startedAt)
        assertTrue(started.startsWith("2:32"), "was $started")
        assertTrue(started.endsWith("PM"), "was $started")
    }

    @Test
    @DisplayName("a timestamp this client cannot read drops the time rather than the eyebrow")
    fun unreadableStampDegrades() {
        assertEquals(
            WorkoutEyebrow.InProgress(startedAt = null),
            eyebrow(startState = HookButtonState.FIRED, startFiredAt = "2026-08-08 14:32:05"),
        )
    }

    // ---- eyebrow glyphs -------------------------------------------------------------

    @Test
    @DisplayName("each lifecycle wears its own mark, and an appointment is an appointment")
    fun eyebrowGlyphs() {
        assertEquals(EyebrowGlyph.LOCK, WorkoutEyebrow.Past.glyph())
        assertEquals(EyebrowGlyph.CALENDAR, WorkoutEyebrow.Scheduled("Thu, Jan 10").glyph())
        // A day ahead and a day not begun are the same kind of thing: the words
        // after the glyph are what separate them.
        assertEquals(EyebrowGlyph.CALENDAR, WorkoutEyebrow.TodayReady.glyph())
        assertEquals(EyebrowGlyph.DOT, WorkoutEyebrow.InProgress(startedAt = null).glyph())
        assertEquals(EyebrowGlyph.DOT, WorkoutEyebrow.InProgress(startedAt = "14:32").glyph())
    }

    // ---- tally marks -----------------------------------------------------------------

    @Test
    @DisplayName("the tally draws one mark per unit of work, inked up to the count done")
    fun tallyFills() {
        assertEquals(listOf(true, true, false), tallyMarkFills(TallyMarks(filled = 2, total = 3)))
        assertEquals(listOf(false, false), tallyMarkFills(TallyMarks(filled = 0, total = 2)))
        assertEquals(listOf(true), tallyMarkFills(TallyMarks(filled = 1, total = 1)))
    }

    @Test
    @DisplayName("a log that outran its plan inks what is drawn and no more")
    fun tallyClampsToWhatItDraws() {
        // An exercise edited down to two sets after three were ticked: the marks
        // must not claim a fourth that is not on screen.
        assertEquals(listOf(true, true), tallyMarkFills(TallyMarks(filled = 3, total = 2)))
        // Nothing to draw is a legitimate answer; a negative count is not a
        // list of minus-one marks.
        assertEquals(emptyList<Boolean>(), tallyMarkFills(TallyMarks(filled = 0, total = 0)))
        assertEquals(emptyList<Boolean>(), tallyMarkFills(TallyMarks(filled = 1, total = -1)))
    }

    @Test
    @DisplayName("each drawn day mark has a spoken name, and an unmarked day stays silent")
    fun dayMarkVoices() {
        assertEquals("Completed", DayMark.FILLED.a11yLabel())
        assertEquals("Scheduled", DayMark.OUTLINED.a11yLabel())
        assertEquals("Missed", DayMark.SLASHED.a11yLabel())
        assertNull(DayMark.NONE.a11yLabel())
    }

    @Test
    @DisplayName("the collapsed row announces its marks and tier, which are geometry to a screen reader")
    fun rowDescriptions() {
        assertEquals(
            "Expand Bench Press, 3 x 8, 2 of 3 done",
            exerciseRowDescription("Bench Press", null, "3 x 8", TallyMarks(2, 3), expanded = false),
        )
        assertEquals(
            "Collapse Bench Press, 3 x 8, 2 of 3 done",
            exerciseRowDescription("Bench Press", null, "3 x 8", TallyMarks(2, 3), expanded = true),
        )
        // The plate dot's meaning rides the sentence — the legend maps colours
        // for the sighted, not tiers to rows for a screen reader.
        assertEquals(
            "Expand Bench Press, Heavy tier, 3 x 8, 2 of 3 done",
            exerciseRowDescription("Bench Press", "Heavy", "3 x 8", TallyMarks(2, 3), expanded = false),
        )
        // A single mark is a yes/no, not a count — the same thing the retired
        // progress pill said about a duration it could not count.
        assertEquals(
            "Expand Zone 2, 20 min, done",
            exerciseRowDescription("Zone 2", null, "20 min", TallyMarks(1, 1), expanded = false),
        )
        assertEquals(
            "Expand Zone 2, 20 min, not done",
            exerciseRowDescription("Zone 2", null, "20 min", TallyMarks(0, 1), expanded = false),
        )
    }

    @Test
    @DisplayName("a row with nothing countable and no scheme still announces what it is")
    fun rowDescriptionsDegrade() {
        assertEquals(
            "Expand Mobility",
            exerciseRowDescription("Mobility", null, "", null, expanded = false),
        )
        // Blank exposure is absent, the same rule the dot itself follows.
        assertEquals(
            "Expand Mobility",
            exerciseRowDescription("Mobility", " ", "", null, expanded = false),
        )
        assertEquals(
            "Expand Mobility, 4 items",
            exerciseRowDescription("Mobility", null, "4 items", TallyMarks(0, 0), expanded = false),
        )
        // Clamped like the marks themselves: a plan edited down must not claim
        // more done than it draws.
        assertEquals(
            "Expand Rows, 2 of 2 done",
            exerciseRowDescription("Rows", null, "", TallyMarks(3, 2), expanded = false),
        )
    }

    // ---- section heads ----------------------------------------------------------------

    // ---- the prescription meta line -------------------------------------------------

    @Test
    @DisplayName("a load that is not a number is introduced by the word for it")
    fun loadLabelling() {
        assertEquals(false, loadNeedsLabel("35 lb/hand"))
        assertEquals(false, loadNeedsLabel("  20"))
        assertEquals(true, loadNeedsLabel("light band assist"))
        assertEquals(true, loadNeedsLabel("hold prior assistance"))
        // Never assume the slot is numeric — including when it is empty.
        assertEquals(true, loadNeedsLabel(""))
    }

    // ---- the set table -------------------------------------------------------------------

    @Test
    @DisplayName("a read-only cell is announced with its column, and a ghost is announced as one")
    fun setCellDescriptions() {
        assertEquals("Set 2 weight, 20", setCellDescription(2, "weight", "20", isGhost = false))
        // Faintness is the visual tell and a screen reader cannot see it: saying
        // last session's number as though it were this one's is the exact
        // mistake the ghost convention exists to prevent.
        assertEquals("Set 2 weight, 20 last time", setCellDescription(2, "weight", "20", isGhost = true))
        assertEquals("Set 1 reps, not logged", setCellDescription(1, "reps", "", isGhost = false))
        assertEquals("Set 1 reps, not logged", setCellDescription(1, "reps", "", isGhost = true))
    }

    // ---- hook buttons ------------------------------------------------------------------

    @Test
    @DisplayName("Start fills and End outlines, until the state has something of its own to say")
    fun hookSkinFollowsTheAction() {
        val start = hookButtonSkin(HookAction.START, HookButtonState.DEFAULT)
        val end = hookButtonSkin(HookAction.END, HookButtonState.DEFAULT)

        assertTrue(start.filled, "Start is the day's one filled call to action")
        assertEquals(HookGlyph.NONE, start.glyph)
        assertEquals(false, end.filled, "End sits behind Start rather than competing with it")
        assertNull(end.note)
    }

    @Test
    @DisplayName("state outranks action: working outlines, settled ticks, failed crosses and says so")
    fun hookSkinFollowsTheState() {
        for (action in HookAction.entries) {
            val pending = hookButtonSkin(action, HookButtonState.PENDING)
            assertEquals(false, pending.filled, "$action pending")
            assertEquals(HookGlyph.NONE, pending.glyph)
            assertEquals("working", pending.a11yState)

            for (settled in listOf(HookButtonState.FIRED, HookButtonState.LOCKED)) {
                val skin = hookButtonSkin(action, settled)
                assertTrue(skin.filled, "$action $settled")
                assertEquals(HookGlyph.CHECK, skin.glyph, "$action $settled")
                assertNull(skin.note)
            }
            // Same fill, same glyph — but by ear FIRED and LOCKED are different
            // states, and DEFAULT says nothing at all.
            assertEquals("fired", hookButtonSkin(action, HookButtonState.FIRED).a11yState)
            assertEquals("locked", hookButtonSkin(action, HookButtonState.LOCKED).a11yState)
            assertNull(hookButtonSkin(action, HookButtonState.DEFAULT).a11yState)

            val failed = hookButtonSkin(action, HookButtonState.FAILED)
            assertEquals(false, failed.filled, "$action failed")
            assertEquals(HookGlyph.CROSS, failed.glyph)
            // The cross needs a word beside it; no colour is spent on either.
            assertEquals("Failed", failed.note)
            assertEquals("failed", failed.a11yState)
        }
    }

    // ---- empty notes ---------------------------------------------------------------------

    @Test
    @DisplayName("an editable day invites a note, whatever the calendar says about it")
    fun editableVoice() {
        for (eyebrow in ALL_EYEBROWS) {
            assertEquals(NoteVoice.EDITABLE, noteVoice(eyebrow, editable = true), "$eyebrow")
        }
        assertEquals("Add a note", NoteVoice.EDITABLE.emptyNote(NoteScope.EXERCISE))
        assertEquals("Add a note", NoteVoice.EDITABLE.emptyNote(NoteScope.SESSION))
    }

    @Test
    @DisplayName("a scheduled day says 'not yet', and says it differently at each level")
    fun scheduledVoice() {
        assertEquals(NoteVoice.SCHEDULED, noteVoice(WorkoutEyebrow.Scheduled("Thu, Jan 10"), editable = false))

        assertEquals(
            "Added when you log this session",
            NoteVoice.SCHEDULED.emptyNote(NoteScope.EXERCISE),
        )
        assertEquals(
            "None yet — recorded after the session",
            NoteVoice.SCHEDULED.emptyNote(NoteScope.SESSION),
        )
    }

    @Test
    @DisplayName("a shut gate speaks in the past tense, because nothing can be written through it")
    fun recordedVoice() {
        // Today with the start gate still closed is not "not yet" — it is
        // "nothing here", which is what the field honestly holds.
        assertEquals(NoteVoice.RECORDED, noteVoice(WorkoutEyebrow.TodayReady, editable = false))
        assertEquals(NoteVoice.RECORDED, noteVoice(WorkoutEyebrow.Past, editable = false))
        assertEquals(NoteVoice.RECORDED, noteVoice(WorkoutEyebrow.InProgress(null), editable = false))

        assertEquals("None recorded", NoteVoice.RECORDED.emptyNote(NoteScope.EXERCISE))
        assertEquals("None recorded", NoteVoice.RECORDED.emptyNote(NoteScope.SESSION))
    }

    // ---- calendar marks -------------------------------------------------------------

    @Test
    @DisplayName("every status has an ink mark, and a quiet day has the absence of one")
    fun statusMarks() {
        assertEquals(DayMark.FILLED, dayMark(WorkoutStatus.COMPLETED))
        assertEquals(DayMark.OUTLINED, dayMark(WorkoutStatus.SCHEDULED))
        assertEquals(DayMark.SLASHED, dayMark(WorkoutStatus.MISSED))
        assertEquals(DayMark.NONE, dayMark(null))
    }

    // ---- the cardio target-HR timeline -------------------------------------------------
    //
    // Twin of `test/js/segments.test.js`: the two stacks assert the same strings,
    // case for case, because one plan renders on both.

    @Test
    @DisplayName("no timeline renders nothing")
    fun segmentsAbsent() {
        assertEquals("", formatSegments(null))
        assertEquals("", formatSegments(emptyList()))
    }

    @Test
    @DisplayName("a range uses an en dash, a floor reads at-least, a ceiling at-most")
    fun segmentBoundShapes() {
        assertEquals("5:00 125–140", formatSegments(listOf(segment(300, min = 125, max = 140))))
        assertEquals("1:30 ≥132", formatSegments(listOf(segment(90, min = 132))))
        assertEquals("2:00 ≤150", formatSegments(listOf(segment(120, max = 150))))
    }

    @Test
    @DisplayName("segments join with a middle dot, in order")
    fun segmentsJoinInOrder() {
        assertEquals(
            "5:00 125–140 · 3:00 160–175 · 2:00 ≤150",
            formatSegments(
                listOf(
                    segment(300, min = 125, max = 140, label = "warmup"),
                    segment(180, min = 160, max = 175, label = "hard"),
                    segment(120, max = 150, label = "easy"),
                ),
            ),
        )
    }

    @Test
    @DisplayName("durations are M:SS — seconds padded, minutes not, and never wrapped at an hour")
    fun segmentClockFormat() {
        assertEquals(
            "0:05 ≤110 · 1:05 ≤110 · 10:00 ≤110 · 61:01 ≤110",
            formatSegments(
                listOf(5, 65, 600, 3661).map { segment(it, max = 110) },
            ),
        )
    }

    @Test
    @DisplayName("the label is not on the static line — it belongs beside its segment in the guide")
    fun segmentLabelIsNotDrawnHere() {
        assertEquals("1:00 ≥140", formatSegments(listOf(segment(60, min = 140, label = "HARD"))))
    }

    @Test
    @DisplayName("a segment with neither bound degrades to its duration")
    fun segmentWithoutBounds() {
        // The server rejects this shape, so it can only arrive from a
        // hand-edited row; the line stays readable rather than losing a number.
        assertEquals("0:45", formatSegments(listOf(segment(45))))
    }

    @Test
    @DisplayName("a zero bound is absent, not a bound of zero — the PWA's truthiness")
    fun segmentZeroBoundIsAbsent() {
        assertEquals("0:45 ≤130", formatSegments(listOf(segment(45, min = 0, max = 130))))
        assertEquals("0:45", formatSegments(listOf(segment(45, min = 0, max = 0))))
    }

    @Test
    @DisplayName("a role changes nothing about the line — it is semantics, and this line is display")
    fun segmentRoleIsNotDrawnHere() {
        // The twin of the same assertion in `test/js/segments.test.js`: the
        // guide reads roles, the static line does not, and the two clients
        // agree on that by both refusing to render them.
        val plain = listOf(
            segment(300, max = 118),
            segment(2_400, min = 122, max = 140),
            segment(300, max = 118),
        )
        val roled = listOf(
            segment(300, max = 118, role = "warmup"),
            segment(2_400, min = 122, max = 140, role = "work"),
            segment(300, max = 118, role = "cooldown"),
        )

        assertEquals(formatSegments(plain), formatSegments(roled))
        assertEquals("5:00 ≤118 · 40:00 122–140 · 5:00 ≤118", formatSegments(roled))
        // A value no client knows renders like any other — the line is display.
        assertEquals("1:00 ≥140", formatSegments(listOf(segment(60, min = 140, role = "sprint"))))
    }

    // ---- the provenance footer --------------------------------------------------------

    @Test
    @DisplayName("the footer names the faint values while any are still showing")
    fun provenanceWording() {
        assertEquals(
            "Ghost values · last at this tier · Aug 1",
            GhostProvenance(date = "Aug 1", ghostsShowing = true).label,
        )
        assertEquals(
            "Last at this tier · Aug 1",
            GhostProvenance(date = "Aug 1", ghostsShowing = false).label,
        )
    }

    private fun segment(
        durationSec: Int,
        min: Int? = null,
        max: Int? = null,
        label: String? = null,
        role: String? = null,
    ) = PlanSegmentDto(
        durationSec = durationSec,
        hrMin = min,
        hrMax = max,
        label = label,
        role = role,
    )

    private companion object {
        const val TODAY = "2026-08-08"

        /** One of each lifecycle, for the rules that must hold across all of them. */
        val ALL_EYEBROWS = listOf(
            WorkoutEyebrow.Past,
            WorkoutEyebrow.Scheduled("Thu, Jan 10"),
            WorkoutEyebrow.TodayReady,
            WorkoutEyebrow.InProgress(startedAt = null),
        )
    }
}
