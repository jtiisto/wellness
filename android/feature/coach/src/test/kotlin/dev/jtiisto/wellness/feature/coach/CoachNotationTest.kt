package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookButtonState
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
        val scheduled = eyebrow(date = "2026-08-20") as WorkoutEyebrow.Scheduled

        assertEquals("Thu, Aug 20", scheduled.logOn)
        assertEquals("Scheduled · Log on Thu, Aug 20", scheduled.label)
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

    // ---- calendar marks -------------------------------------------------------------

    @Test
    @DisplayName("every status has an ink mark, and a quiet day has the absence of one")
    fun statusMarks() {
        assertEquals(DayMark.FILLED, dayMark(WorkoutStatus.COMPLETED))
        assertEquals(DayMark.OUTLINED, dayMark(WorkoutStatus.SCHEDULED))
        assertEquals(DayMark.SLASHED, dayMark(WorkoutStatus.MISSED))
        assertEquals(DayMark.NONE, dayMark(null))
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

    private companion object {
        const val TODAY = "2026-08-08"
    }
}
