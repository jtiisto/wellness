package dev.jtiisto.wellness.core.data.trends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tonight's card, reduced to strings — pinned verbatim.
 *
 * Every line here ends up on a headline surface and, later, on a home-screen
 * widget nobody will be watching while it renders. There is no screenshot test
 * behind either, so the wording IS the contract: an assertion loose enough to
 * pass on "debt 1:11" and "debt 1.2h" alike would not be pinning anything.
 *
 * All values are invented, and the dates follow the far-future `2030-01-*`
 * fixture convention.
 */
class SleepDebtLogicTest {

    // ---- h:mm ---------------------------------------------------------------

    @Test
    @DisplayName("minutes read as h:mm, with the minute field always two digits")
    fun hoursMinutesTable() {
        assertEquals("8:42", hoursMinutes(522.0))
        assertEquals("0:00", hoursMinutes(0.0))
        assertEquals("1:11", hoursMinutes(71.0))
        assertEquals("0:07", hoursMinutes(7.0))
        assertEquals("12:00", hoursMinutes(720.0))
    }

    @Test
    @DisplayName("rounding happens ONCE, on the total — 59.6 minutes is 1:00, never 0:60")
    fun hoursMinutesRoundsTheTotal() {
        // Rounding the hour and the minute halves separately is the bug this
        // pins: 59.6 would floor to 0 hours and round to 60 minutes.
        assertEquals("1:00", hoursMinutes(59.6))
        assertEquals("0:59", hoursMinutes(59.4))
        assertEquals("8:00", hoursMinutes(479.5))
        assertEquals("1:00", hoursMinutes(60.49))
    }

    @Test
    @DisplayName("ties go up toward +inf, the way every other number in this app rounds")
    fun hoursMinutesRoundsHalfUp() {
        // `kotlin.math.round` is half-to-even and would give 0:30 here.
        assertEquals("0:31", hoursMinutes(30.5))
        assertEquals("0:32", hoursMinutes(31.5))
    }

    @Test
    @DisplayName("a negative duration clamps rather than inventing a sign convention")
    fun hoursMinutesClampsNegatives() {
        // The server guarantees a non-negative debt and the other two are
        // elapsed time, so this is a contract violation, not a display case.
        assertEquals("0:00", hoursMinutes(-5.0))
    }

    // ---- the null model -----------------------------------------------------

    @Test
    @DisplayName("no card at all when there is nothing fetched, nothing available, or no tonight")
    fun nullModelMatrix() {
        assertNull(sleepTonightModel(null, null, NOW, TODAY))
        assertNull(
            sleepTonightModel(SleepDebtDto(available = false, days = emptyList()), null, NOW, TODAY),
        )
        // Available, but the payload somehow carries no upcoming night: the
        // card is the number, so there is no card.
        assertNull(
            sleepTonightModel(SleepDebtDto(available = true, days = emptyList()), null, NOW, TODAY),
        )
    }

    @Test
    @DisplayName("an available ledger with no history still draws its card")
    fun availableWithoutDaysStillDraws() {
        val model = requireNotNull(
            sleepTonightModel(dto(days = emptyList(), asOf = null), null, NOW, TODAY),
        )

        assertEquals("8:15", model.needText)
        assertEquals(NO_SCORED_NIGHTS_TEXT, model.freshnessLine)
        assertEquals(TonightJudgment.PARTIAL, model.judgment)
    }

    // ---- the lines ----------------------------------------------------------

    @Test
    @DisplayName("a settled card: the need in h:mm, a debt line, a strain line, nothing else")
    fun settledCard() {
        val model = requireNotNull(sleepTonightModel(dto(), null, NOW, TODAY))

        assertEquals("8:15", model.needText)
        assertEquals("debt 1:11", model.debtText)
        assertEquals("debt 1:11", model.debtLine)
        // With nothing to qualify it, the two surfaces say the same sentence.
        assertEquals("debt 1:11", model.cardDebtLine)
        assertNull(model.napText)
        assertNull(model.resetText)
        assertEquals("strain 4.6 · so far", model.strainLine)
        assertNull(model.freshnessLine)
        assertNull(model.cachedLine)
        assertEquals(TonightJudgment.SETTLED, model.judgment)
        assertFalse(model.flagged)
    }

    @Test
    @DisplayName("a zero debt is said in words, not as 0:00")
    fun zeroDebtReadsAsWords() {
        val model = requireNotNull(sleepTonightModel(dto(debt = 0.0), null, NOW, TODAY))

        assertEquals("no sleep debt", model.debtLine)
    }

    // ---- naps ---------------------------------------------------------------

    @Test
    @DisplayName("a nap is named as a credit, and only on the card — the widget's line is untouched")
    fun napIsNamedOnTheCardOnly() {
        val model = requireNotNull(sleepTonightModel(dto(nap = 45.0), null, NOW, TODAY))

        // U+2212 MINUS SIGN, not a hyphen: the need above it has already had
        // this taken off, and nothing here subtracts it a second time.
        assertEquals("nap −0:45", model.napText)
        assertEquals("debt 1:11 · nap −0:45", model.cardDebtLine)
        assertEquals(
            "debt 1:11",
            model.debtLine,
            "the widget's cell keeps the line it had; the nap is already inside its headline",
        )
        assertEquals(TonightJudgment.SETTLED, model.judgment, "a nap is not a caveat")
    }

    @Test
    @DisplayName("the card's line reads figure, then nap, then reset — in that order")
    fun napPrecedesTheReset() {
        val model = requireNotNull(
            sleepTonightModel(
                dto(debt = 0.0, nap = 30.0, days = listOf(day("2030-01-25", gap = true))),
                null,
                NOW,
                TODAY,
            ),
        )

        // The nap qualifies the figure beside it; the reset qualifies the whole
        // ledger and reads last. The widget's line carries only the second.
        assertEquals("no sleep debt · nap −0:30 · reset — missing night", model.cardDebtLine)
        assertEquals("no sleep debt · reset — missing night", model.debtLine)
        assertEquals(TonightJudgment.ATTENTION, model.judgment)
    }

    @Test
    @DisplayName("a zero nap is no nap: the key is omitted, and nothing is said")
    fun zeroNapSaysNothing() {
        // `nap_min` is omitted when zero, so the DTO default IS the common
        // case — a card that printed `nap −0:00` would be reporting the absence
        // of a nap as an event.
        val model = requireNotNull(sleepTonightModel(dto(nap = 0.0), null, NOW, TODAY))

        assertNull(model.napText)
        assertEquals(model.debtLine, model.cardDebtLine)
    }

    @Test
    @DisplayName("strain keeps its decimal even on a whole number, and drops 'so far' when settled")
    fun strainFormatting() {
        val whole = requireNotNull(sleepTonightModel(dto(strain = 9.0), null, NOW, TODAY))
        assertEquals("strain 9.0 · so far", whole.strainLine)

        val complete = requireNotNull(
            sleepTonightModel(dto(strainPartial = false), null, NOW, TODAY),
        )
        assertEquals("strain 4.6", complete.strainLine)
    }

    @Test
    @DisplayName("a trailing gap says the record has a hole, and takes the card to ATTENTION")
    fun trailingGapFlagsTheCard() {
        val model = requireNotNull(
            sleepTonightModel(dto(debt = 0.0, days = listOf(day("2030-01-25", gap = true))), null, NOW, TODAY),
        )

        // The suffix composes onto either debt wording; on a zero it is what
        // stops the reset reading as a night that cleared the ledger.
        assertEquals("no sleep debt · reset — missing night", model.debtLine)
        assertEquals(TonightJudgment.ATTENTION, model.judgment)
        assertTrue(model.flagged, "the mono ! is the only thing marking it — never a colour")
    }

    @Test
    @DisplayName("only the LAST night's gap matters — an older one has already been carried through")
    fun onlyTheTrailingGapCounts() {
        val model = requireNotNull(
            sleepTonightModel(
                dto(days = listOf(day("2030-01-24", gap = true), day("2030-01-25"))),
                null,
                NOW,
                TODAY,
            ),
        )

        assertEquals("debt 1:11", model.debtLine)
        assertEquals(TonightJudgment.SETTLED, model.judgment)
        assertFalse(model.flagged)
    }

    // ---- freshness and the cached copy --------------------------------------

    @Test
    @DisplayName("an as_of behind today names the day the data runs to")
    fun asOfLagIsNamed() {
        val model = requireNotNull(sleepTonightModel(dto(asOf = "2030-01-24"), null, NOW, TODAY))

        assertEquals("data through 2030-01-24", model.freshnessLine)
        assertEquals(TonightJudgment.PARTIAL, model.judgment)
        assertFalse(model.flagged, "a stale watch is not an alarm")
    }

    @Test
    @DisplayName("a payload for another night says so, and that statement outranks the as_of lag")
    fun dateMismatchOutranksTheLag() {
        // Both hold at once on a cached copy from yesterday. "for <date>" is
        // the whole correction — the lag behind it is implied by it.
        val model = requireNotNull(
            sleepTonightModel(dto(tonightDate = "2030-01-25", asOf = "2030-01-24"), null, NOW, TODAY),
        )

        assertEquals("for 2030-01-25", model.freshnessLine)
        assertEquals(TonightJudgment.PARTIAL, model.judgment)
    }

    @Test
    @DisplayName("a cached copy reports its own age, in the stale badge's words")
    fun cachedCopyReportsItsAge() {
        val fresh = requireNotNull(sleepTonightModel(dto(), NOW - 120_000L, NOW, TODAY))
        assertEquals("cached · 2m ago", fresh.cachedLine)
        assertEquals(TonightJudgment.PARTIAL, fresh.judgment)

        val hours = requireNotNull(sleepTonightModel(dto(), NOW - 7_200_000L, NOW, TODAY))
        assertEquals("cached · 2h ago", hours.cachedLine)

        // Under a minute still rounds UP to one: "cached · 0m ago" would read
        // as fresh, which is the one thing the line exists to deny.
        val seconds = requireNotNull(sleepTonightModel(dto(), NOW - 5_000L, NOW, TODAY))
        assertEquals("cached · 1m ago", seconds.cachedLine)
    }

    @Test
    @DisplayName("a gap outranks staleness: ATTENTION is not downgraded by a cached copy")
    fun gapOutranksStaleness() {
        val model = requireNotNull(
            sleepTonightModel(
                dto(days = listOf(day("2030-01-25", gap = true))),
                NOW - 120_000L,
                NOW,
                TODAY,
            ),
        )

        assertEquals(TonightJudgment.ATTENTION, model.judgment)
        assertEquals("cached · 2m ago", model.cachedLine, "the age is still stated, just not the verdict")
    }

    private companion object {
        const val TODAY = "2030-01-26"
        const val NOW = 1_800_000_000_000L

        // A gap row's `debt_min` is that night's own product like any other —
        // the reset applies to the debt it started on, not to what it left —
        // so the flag does not change the number here.
        fun day(date: String, gap: Boolean = false) = SleepDebtDay(
            date = date,
            needMin = 472.0,
            sleptMin = 388.5,
            debtMin = 12.5,
            strainEst = 7.5,
            gap = gap,
        )

        // The card takes the nap from `tonight` alone, so the ledger rows here
        // never need one.
        fun dto(
            tonightDate: String = TODAY,
            need: Double = 495.0,
            debt: Double = 71.0,
            strain: Double = 4.6,
            strainPartial: Boolean = true,
            nap: Double = 0.0,
            asOf: String? = "2030-01-26",
            days: List<SleepDebtDay> = listOf(day("2030-01-25")),
        ) = SleepDebtDto(
            available = true,
            asOf = asOf,
            tonight = SleepTonight(
                date = tonightDate,
                needMin = need,
                debtMin = debt,
                strainEst = strain,
                strainPartial = strainPartial,
                napMin = nap,
            ),
            days = days,
        )
    }
}
