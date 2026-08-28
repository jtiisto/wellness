package dev.jtiisto.wellness.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.R
import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.trends.SleepDebtDto
import dev.jtiisto.wellness.core.data.trends.SleepTonight
import dev.jtiisto.wellness.core.data.trends.SleepTonightModel
import dev.jtiisto.wellness.core.data.trends.TonightJudgment
import dev.jtiisto.wellness.core.data.trends.TrendsCachePeek
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Everything the widget decides, decided here.
 *
 * The Glance surface, its receiver and its worker are excluded from coverage by
 * name, and that exclusion is a claim: it says those files hold no decisions,
 * because every decision was pushed down into `TodayWidgetLogic`. This file is
 * the other half of the bargain — if a rule is not pinned here, nothing pins it
 * at all, since none of the four shells can execute off a device.
 *
 * Dates are the far-future `2030-01-*` convention and every value is invented;
 * this repo is public.
 */
class TodayWidgetLogicTest {

    /** A stamp in 2030, so no fixture here can be mistaken for a real one. */
    private val fetchedAt = 1_900_000_000_000L
    private val today = "2030-01-15"

    private fun peeked(available: Boolean = true) = TrendsCachePeek.PeekedSleep(
        dto = SleepDebtDto(
            available = available,
            asOf = today,
            tonight = SleepTonight(
                date = today,
                needMin = 500.0,
                debtMin = 30.0,
                strainEst = 9.9,
            ),
            days = emptyList(),
        ),
        fetchedAt = fetchedAt,
    )

    private fun model(judgment: TonightJudgment) = SleepTonightModel(
        needText = "8:20",
        debtLine = "debt 0:30",
        strainLine = "strain 9.9 · so far",
        freshnessLine = null,
        cachedLine = null,
        judgment = judgment,
        flagged = judgment == TonightJudgment.ATTENTION,
    )

    // ---- Keys and window ---------------------------------------------------

    @Test
    @DisplayName("the widget asks for its own copy first, the user's range second")
    fun peekKeyOrder() {
        // Pinned verbatim, order included: the fallback is only sound because
        // `tonight` is range-independent, and the preference for the widget's
        // own key is what keeps the freshest copy first.
        assertEquals(
            listOf("health/sleep:widget", "health/sleep:12w"),
            widgetPeekKeys("12w"),
        )
    }

    @Test
    @DisplayName("the fetch window is seven days ending today, inclusive")
    fun fetchWindow() {
        assertEquals(
            "2030-01-09" to "2030-01-15",
            widgetFetchWindow(LocalDate.parse("2030-01-15")),
        )
    }

    @Test
    @DisplayName("the window walks back across a month boundary rather than clamping")
    fun fetchWindowCrossesMonths() {
        assertEquals(
            "2029-12-28" to "2030-01-03",
            widgetFetchWindow(LocalDate.parse("2030-01-03")),
        )
    }

    // ---- Freshness ---------------------------------------------------------

    @Test
    @DisplayName("a copy under 90 minutes old is fresh: no stamp reaches the model")
    fun freshUnderTheWindow() {
        assertNull(widgetStaleFetchedAt(fetchedAt, fetchedAt + 89 * 60_000L + 59_000L))
    }

    @Test
    @DisplayName("at exactly 90 minutes the copy is stale — the window is half-open")
    fun staleAtTheBoundary() {
        // The inequality is `<`, so the boundary instant belongs to the stale
        // side. Which side it falls on matters less than it never moving.
        assertEquals(fetchedAt, widgetStaleFetchedAt(fetchedAt, fetchedAt + WIDGET_FRESH_WINDOW_MS))
        assertEquals(90 * 60_000L, WIDGET_FRESH_WINDOW_MS)
    }

    @Test
    @DisplayName("past the window the real stamp goes through untouched")
    fun stalePastTheWindow() {
        assertEquals(
            fetchedAt,
            widgetStaleFetchedAt(fetchedAt, fetchedAt + WIDGET_FRESH_WINDOW_MS + 1_000L),
        )
    }

    @Test
    @DisplayName("a stamp from the future reads fresh — the harmless direction")
    fun clockMovedBackwards() {
        // A clock moved back costs one missing badge; the other way round would
        // brand every render cached for as long as the skew lasted.
        assertNull(widgetStaleFetchedAt(fetchedAt, fetchedAt - 60_000L))
    }

    // ---- The model ---------------------------------------------------------

    @Test
    @DisplayName("nothing peeked is no model: the surface draws its pending floor")
    fun noPeekNoModel() {
        assertNull(widgetModel(null, now = fetchedAt, today = today))
    }

    @Test
    @DisplayName("a copy minutes old is settled and says nothing about being cached")
    fun freshCopyIsSettled() {
        val card = widgetModel(peeked(), now = fetchedAt + 60_000L, today = today)

        assertNotNull(card)
        assertNull(card!!.cachedLine)
        assertEquals(TonightJudgment.SETTLED, card.judgment)
    }

    @Test
    @DisplayName("a copy three hours old confesses its age and drops to PARTIAL")
    fun staleCopyIsPartial() {
        val card = widgetModel(peeked(), now = fetchedAt + 3 * 60 * 60_000L, today = today)

        // The wording is `sleepTonightModel`'s and pinned by its own suite; what
        // this asserts is that the stamp was handed over at all.
        assertEquals("cached · 3h ago", card?.cachedLine)
        assertEquals(TonightJudgment.PARTIAL, card?.judgment)
    }

    @Test
    @DisplayName("an unavailable payload is still no model")
    fun unavailableIsNoModel() {
        assertNull(widgetModel(peeked(available = false), now = fetchedAt, today = today))
    }

    // ---- The fetch guard ---------------------------------------------------

    @Test
    @DisplayName("the worker fetches only with a resolved server and an open gate")
    fun fetchGuard() {
        assertTrue(shouldFetch(resolved = true, gateOpen = true))
        assertFalse(shouldFetch(resolved = true, gateOpen = false))
        assertFalse(shouldFetch(resolved = false, gateOpen = true))
        assertFalse(shouldFetch(resolved = false, gateOpen = false))
    }

    // ---- Buckets -----------------------------------------------------------

    @Test
    @DisplayName("each declared size lands in its own bucket")
    fun declaredSizesMapToThemselves() {
        assertEquals(WidgetBucket.STRIP, widgetBucket(STRIP))
        assertEquals(WidgetBucket.CARD, widgetBucket(CARD))
        assertEquals(WidgetBucket.PAGE, widgetBucket(PAGE))
    }

    @Test
    @DisplayName("110dp of height is where the sleep block becomes affordable")
    fun cardHeightBoundary() {
        assertEquals(WidgetBucket.STRIP, widgetBucket(DpSize(180.dp, 109.9.dp)))
        assertEquals(WidgetBucket.CARD, widgetBucket(DpSize(180.dp, 110.dp)))
    }

    @Test
    @DisplayName("170dp of height is where strain and the honesty lines fit")
    fun pageHeightBoundary() {
        assertEquals(WidgetBucket.CARD, widgetBucket(DpSize(180.dp, 169.9.dp)))
        assertEquals(WidgetBucket.PAGE, widgetBucket(DpSize(180.dp, 170.dp)))
    }

    @Test
    @DisplayName("a tall narrow widget stays a strip — the headline needs the width")
    fun narrowButTallIsStrip() {
        // Both dimensions have to clear the floor. A 179dp column would render
        // `! 8:20 h:mm` clipped, which is worse than not rendering it.
        assertEquals(WidgetBucket.STRIP, widgetBucket(DpSize(179.9.dp, 400.dp)))
        assertEquals(WidgetBucket.STRIP, widgetBucket(DpSize(110.dp, 170.dp)))
    }

    @Test
    @DisplayName("anything larger than PAGE is still PAGE")
    fun oversizeIsPage() {
        assertEquals(WidgetBucket.PAGE, widgetBucket(DpSize(320.dp, 400.dp)))
    }

    // ---- What each bucket shows --------------------------------------------

    @Test
    @DisplayName("elements are added in priority order and dropped in reverse")
    fun elementsPerBucket() {
        assertEquals(
            listOf(false, true, true),
            WidgetBucket.entries.map { showsSleep(it) },
        )
        assertEquals(
            listOf(false, false, true),
            WidgetBucket.entries.map { showsStrain(it) },
        )
        // The honesty words wait for PAGE: at the CARD floor the glyph is the
        // caveat, which is what PARTIAL is defined as.
        assertEquals(
            listOf(false, false, true),
            WidgetBucket.entries.map { showsHonestyLines(it) },
        )
    }

    @Test
    @DisplayName("a strip with no tally gives its line to the compact sleep row")
    fun compactSleepTakesTheEmptyStrip() {
        // The one case a 2×1 has nothing to say otherwise: a day expecting no
        // habits. An empty strip reads as broken, so the next element takes it —
        // in the form the floor holds, which is the glyph and the number.
        assertTrue(showsCompactSleep(WidgetBucket.STRIP, hasTally = false))
        assertFalse(showsCompactSleep(WidgetBucket.STRIP, hasTally = true))
    }

    @Test
    @DisplayName("no bucket that draws the whole sleep block draws the compact row too")
    fun compactSleepNeverDoublesUp() {
        listOf(WidgetBucket.CARD, WidgetBucket.PAGE).forEach { bucket ->
            assertFalse(showsCompactSleep(bucket, hasTally = false))
            assertFalse(showsCompactSleep(bucket, hasTally = true))
        }
    }

    // ---- The tally ---------------------------------------------------------

    /** A tally at the system's default font scale — the tests about scaling say so. */
    private fun tally(
        rollup: CategoryRollup?,
        bucket: WidgetBucket,
        widthDp: Float,
        fontScale: Float = 1f,
    ) = tallyLayout(rollup, bucket, widthDp, fontScale)

    @Test
    @DisplayName("no rollup is no tally, and neither is a day of no habits")
    fun tallyAbsent() {
        assertNull(tally(null, WidgetBucket.CARD, 180f))
        // Avoidances and observations get no dot here — a day holding nothing
        // else has no line for this element to draw.
        assertNull(
            tally(
                CategoryRollup(avoidances = 2, observationsExpected = 1),
                WidgetBucket.CARD,
                180f,
            ),
        )
    }

    @Test
    @DisplayName("dots read met, then partial, then not-yet — left to right")
    fun dotOrder() {
        val layout = tally(
            CategoryRollup(habitsMet = 3, habitsPartial = 2, habitsNotYet = 1),
            WidgetBucket.PAGE,
            180f,
        )

        assertEquals(
            listOf(
                TallyDot.FILLED, TallyDot.FILLED, TallyDot.FILLED,
                TallyDot.HALF, TallyDot.HALF,
                TallyDot.OPEN,
            ),
            layout?.dots,
        )
        assertEquals("3 OF 6 DONE", layout?.text)
    }

    @Test
    @DisplayName("the count speaks only the habits, whatever else the day held")
    fun countIsHabitsOnly() {
        // The wording is `describeCategoryRollup`'s, but the rollup is projected
        // to its habits first: the widget draws no avoidance or observation
        // mark, so it must not claim one in words either.
        val layout = tally(
            CategoryRollup(
                habitsMet = 3,
                habitsNotYet = 1,
                avoidances = 2,
                avoidancesBroken = 1,
                observationsExpected = 2,
                observationsNoted = 1,
            ),
            WidgetBucket.CARD,
            180f,
        )

        assertEquals("3 OF 4 DONE", layout?.text)
        assertEquals(4, layout?.dots?.size)
    }

    @Test
    @DisplayName("at the CARD floor six habits and their count both fit")
    fun dotsAndTextTogether() {
        val layout = tally(
            CategoryRollup(habitsMet = 3, habitsPartial = 2, habitsNotYet = 1),
            WidgetBucket.CARD,
            180f,
        )

        // 68dp of dots + 8dp + 69.3dp of text = 145.3 ≤ 156dp of content.
        assertNotNull(layout?.dots)
        assertNotNull(layout?.text)
    }

    @Test
    @DisplayName("when both will not fit, the dots stay and the count goes")
    fun dotsWinOverText() {
        val layout = tally(CategoryRollup(habitsMet = 12), WidgetBucket.CARD, 180f)

        // 140dp of dots fits 156dp of content; 140 + 8 + 81.9 does not.
        assertEquals(12, layout?.dots?.size)
        assertNull(layout?.text)
    }

    @Test
    @DisplayName("dots that overflow are replaced by the sentence, never truncated")
    fun textWhenDotsOverflow() {
        val layout = tally(CategoryRollup(habitsMet = 14), WidgetBucket.CARD, 180f)

        // 164dp of dots against 156dp of content. A shortened row would be a
        // confident wrong answer about the day, which is the one thing a
        // glanceable surface must never produce.
        assertNull(layout?.dots)
        assertEquals("14 OF 14 DONE", layout?.text)
    }

    @Test
    @DisplayName("the four rungs, in order, as the same tally loses room")
    fun theFitLadder() {
        // One rollup, four widths: 164dp of dots and 81.9dp of sentence, so each
        // step is the one below the last thing that stopped fitting. The final
        // width is under the CARD floor — the parameter under test is the width,
        // not the bucket, and this is the arithmetic the ladder actually runs.
        val rollup = CategoryRollup(habitsMet = 14)

        val both = tally(rollup, WidgetBucket.CARD, 280f)
        assertEquals(14, both?.dots?.size)
        assertEquals("14 OF 14 DONE", both?.text)

        val dotsOnly = tally(rollup, WidgetBucket.CARD, 200f)
        assertEquals(14, dotsOnly?.dots?.size)
        assertNull(dotsOnly?.text)

        val sentence = tally(rollup, WidgetBucket.CARD, 180f)
        assertNull(sentence?.dots)
        assertEquals("14 OF 14 DONE", sentence?.text)

        val compact = tally(rollup, WidgetBucket.CARD, 100f)
        assertNull(compact?.dots)
        assertEquals("14/14", compact?.text)
    }

    @Test
    @DisplayName("a sentence too wide for the row falls to N/M, which is the floor")
    fun compactFloor() {
        // `60 OF 100 DONE` is 88.2dp against the strip's 86dp of content. There
        // is nothing shorter than the fraction, so it draws whatever it measures
        // — the floor of the never-truncate rule, not an exception to it.
        val layout = tally(
            CategoryRollup(habitsMet = 60, habitsNotYet = 40),
            WidgetBucket.STRIP,
            110f,
        )

        assertNull(layout?.dots)
        assertEquals("60/100", layout?.text)
    }

    @Test
    @DisplayName("a larger system font shrinks the words, never the marks")
    fun fontScaleDegradesTheRow() {
        val rollup = CategoryRollup(habitsMet = 3, habitsPartial = 2, habitsNotYet = 1)

        val unscaled = tally(rollup, WidgetBucket.CARD, 180f, fontScale = 1f)
        assertEquals(6, unscaled?.dots?.size)
        assertEquals("3 OF 6 DONE", unscaled?.text)

        // At 1.3 the sentence measures 90.1dp, so 68 + 8 + 90.1 no longer fits
        // 156 — and the dots, being dp, still do. Without the scale this layout
        // would have been chosen for a width it overflows.
        val scaled = tally(rollup, WidgetBucket.CARD, 180f, fontScale = 1.3f)
        assertEquals(6, scaled?.dots?.size)
        assertNull(scaled?.text)
    }

    @Test
    @DisplayName("font scale can walk a strip's count all the way down to N/M")
    fun fontScaleReachesTheCompactForm() {
        val rollup = CategoryRollup(habitsMet = 4, habitsNotYet = 4)

        assertEquals("4 OF 8 DONE", tally(rollup, WidgetBucket.STRIP, 110f)?.text)
        assertEquals("4/8", tally(rollup, WidgetBucket.STRIP, 110f, fontScale = 1.3f)?.text)
    }

    @Test
    @DisplayName("at the strip's floor the arithmetic holds seven dots, then the count")
    fun stripFloorArithmetic() {
        // 86dp of content: seven dots (80dp) fit alone, eight (92dp) do not.
        // These are the same answers the retired frozen seven-dot rule gave —
        // pinned here so the floor keeps behaving after the rule became plain
        // width arithmetic.
        val seven = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 3), WidgetBucket.STRIP, 110f)
        assertEquals(7, seven?.dots?.size)
        assertNull(seven?.text)

        val eight = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 4), WidgetBucket.STRIP, 110f)
        assertNull(eight?.dots)
        assertEquals("4 OF 8 DONE", eight?.text)
    }

    @Test
    @DisplayName("a strip dragged wider uses the width it was given")
    fun stripUsesRealWidth() {
        // The first device build drew a lone fraction on a strip twice its
        // floor, because Responsive handed the fit rule the 110dp bucket size
        // instead of the real one. With SizeMode.Exact the width is the truth:
        // eight dots and the sentence both fit 376dp of content.
        val layout = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 4), WidgetBucket.STRIP, 400f)

        assertEquals(8, layout?.dots?.size)
        assertEquals("4 OF 8 DONE", layout?.text)
    }

    @Test
    @DisplayName("the spoken sentence names the partial dots nobody can hear")
    fun spokenTally() {
        assertEquals(
            "5 of 8 positive trackers done, 2 partial",
            tallyContentDescription(CategoryRollup(habitsMet = 5, habitsPartial = 2, habitsNotYet = 1)),
        )
    }

    @Test
    @DisplayName("with no partials the clause is absent, not zero")
    fun spokenTallyWithoutPartials() {
        assertEquals(
            "5 of 8 positive trackers done",
            tallyContentDescription(CategoryRollup(habitsMet = 5, habitsNotYet = 3)),
        )
    }

    // ---- The sleep block, read aloud ---------------------------------------

    @Test
    @DisplayName("the sleep sentence carries every line, including the ones no bucket drew")
    fun spokenSleep() {
        // The strain and honesty lines are spoken at CARD and at the strip's
        // compact row, where neither is drawn: a bucket decides how much fits,
        // never how much is true.
        val card = SleepTonightModel(
            needText = "8:20",
            debtLine = "debt 0:30",
            strainLine = "strain 9.9 · so far",
            freshnessLine = "data through 2030-01-14",
            cachedLine = "cached · 3h ago",
            judgment = TonightJudgment.PARTIAL,
            flagged = false,
        )

        assertEquals(
            "Tonight's sleep need 8h20m. debt 0:30. strain 9.9 · so far. " +
                "data through 2030-01-14. cached · 3h ago",
            sleepSpoken(card),
        )
    }

    @Test
    @DisplayName("the omitted lines are omitted, not spoken as empty")
    fun spokenSleepWithoutCaveats() {
        assertEquals(
            "Tonight's sleep need 8h20m. debt 0:30. strain 9.9 · so far",
            sleepSpoken(model(TonightJudgment.SETTLED)),
        )
    }

    @Test
    @DisplayName("pending says it is pending — an unlabelled -:-- speaks to nobody")
    fun spokenSleepPending() {
        assertEquals("Tonight's sleep need pending. no data yet", sleepSpoken(null))
    }

    // ---- Marks -------------------------------------------------------------

    @Test
    @DisplayName("the three mark drawables are three different drawables")
    fun marksAreDistinct() {
        // Guards every mapping below from passing on a tree where the ids all
        // resolved to the same value.
        assertEquals(
            3,
            setOf(
                R.drawable.widget_mark_filled,
                R.drawable.widget_mark_half,
                R.drawable.widget_mark_hollow,
            ).size,
        )
    }

    @Test
    @DisplayName("the judgment glyph: filled, half, hollow — and hollow for nothing yet")
    fun judgmentMarks() {
        assertEquals(R.drawable.widget_mark_filled, judgmentDrawable(model(TonightJudgment.SETTLED)))
        assertEquals(R.drawable.widget_mark_half, judgmentDrawable(model(TonightJudgment.PARTIAL)))
        assertEquals(R.drawable.widget_mark_hollow, judgmentDrawable(model(TonightJudgment.ATTENTION)))
        assertEquals(R.drawable.widget_mark_hollow, judgmentDrawable(null))
    }

    @Test
    @DisplayName("tally dots use the same three marks")
    fun dotMarks() {
        assertEquals(R.drawable.widget_mark_filled, dotDrawable(TallyDot.FILLED))
        assertEquals(R.drawable.widget_mark_half, dotDrawable(TallyDot.HALF))
        assertEquals(R.drawable.widget_mark_hollow, dotDrawable(TallyDot.OPEN))
    }

    @Test
    @DisplayName("ink for every judged state; faint is what pending means")
    fun judgmentInk() {
        // ATTENTION and pending share the hollow ring, so the ink is the whole
        // difference between "needs an eye" and "nothing has been said yet".
        assertEquals(WidgetTint.INK_FAINT, judgmentTint(null))
        TonightJudgment.entries.forEach { judgment ->
            assertEquals(WidgetTint.INK, judgmentTint(model(judgment)))
        }
    }

    @Test
    @DisplayName("only the not-yet dot recedes")
    fun dotInk() {
        assertEquals(WidgetTint.INK, dotTint(TallyDot.FILLED))
        assertEquals(WidgetTint.INK, dotTint(TallyDot.HALF))
        assertEquals(WidgetTint.INK_FAINT, dotTint(TallyDot.OPEN))
    }
}
