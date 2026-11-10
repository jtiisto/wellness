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
        debtText = "debt 0:30",
        napText = null,
        resetText = null,
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
    @DisplayName("a strip with the width for two ideas is WIDE — and CARD height outranks it")
    fun wideBucketBoundaries() {
        assertEquals(WidgetBucket.WIDE, widgetBucket(DpSize(250.dp, 100.dp)))
        assertEquals(WidgetBucket.STRIP, widgetBucket(DpSize(249.9.dp, 100.dp)))
        assertEquals(WidgetBucket.WIDE, widgetBucket(DpSize(300.dp, 109.9.dp)))
        // At CARD height the page stacks instead: WIDE is strictly the
        // strip-height family using its width.
        assertEquals(WidgetBucket.CARD, widgetBucket(DpSize(300.dp, 110.dp)))
    }

    @Test
    @DisplayName("WIDE pairs the tally with the compact sleep line, and draws no block")
    fun wideShowsBothColumns() {
        assertFalse(showsSleep(WidgetBucket.WIDE))
        assertFalse(showsStrain(WidgetBucket.WIDE))
        assertFalse(showsHonestyLines(WidgetBucket.WIDE))
        assertTrue(showsCompactSleep(WidgetBucket.WIDE, hasTally = true))
        assertTrue(showsCompactSleep(WidgetBucket.WIDE, hasTally = false))
    }

    @Test
    @DisplayName("the WIDE tally is fitted to its own column, not to the page")
    fun wideTallyFitWidth() {
        // 55% of the content width; the +24 in the fit form gives back the
        // padding tallyLayout subtracts from what it assumes is a page width.
        assertEquals(206.8f, wideTallyWidthDp(400f), 0.01f)
        assertEquals(230.8f, tallyFitWidthDp(WidgetBucket.WIDE, 400f), 0.01f)
        assertEquals(180f, tallyFitWidthDp(WidgetBucket.CARD, 180f))
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
        // Entry order STRIP, WIDE, CARD, PAGE. WIDE carries sleep only in the
        // compact form (its own pin below), so the full block starts at CARD.
        assertEquals(
            listOf(false, false, true, true),
            WidgetBucket.entries.map { showsSleep(it) },
        )
        assertEquals(
            listOf(false, false, false, true),
            WidgetBucket.entries.map { showsStrain(it) },
        )
        // The honesty words wait for PAGE: at the CARD floor the glyph is the
        // caveat, which is what PARTIAL is defined as.
        assertEquals(
            listOf(false, false, false, true),
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

    /** The marks a layout draws, flattened back to reading order; null when it draws none. */
    private fun TallyLayout.marks(): List<TallyMark>? =
        groups?.flatMap { it.posts + listOfNotNull(it.gate) }

    /** [count] met marks, grouped — the widths and budgets below do not care about state. */
    private fun groupsOf(count: Int) = tallyGroups(List(count) { TallyMark.MET })

    /** The day the change was made for: twelve trackers, two done, one half done. */
    private val twelve = CategoryRollup(habitsMet = 2, habitsPartial = 1, habitsNotYet = 9)

    @Test
    @DisplayName("no rollup is no tally, and neither is a day of no habits")
    fun tallyAbsent() {
        assertNull(tally(null, WidgetBucket.CARD, 180f))
        // Avoidances and observations get no mark here — a day holding nothing
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
    @DisplayName("marks read met, then partial, then not-yet — left to right")
    fun markOrder() {
        val layout = tally(
            CategoryRollup(habitsMet = 3, habitsPartial = 2, habitsNotYet = 1),
            WidgetBucket.PAGE,
            180f,
        )

        // Six trackers: the fifth is struck across the first four as their
        // gate, and the sixth starts the next group as a lone post.
        assertEquals(
            listOf(
                TallyGroup(
                    posts = listOf(TallyMark.MET, TallyMark.MET, TallyMark.MET, TallyMark.PARTIAL),
                    gate = TallyMark.PARTIAL,
                ),
                TallyGroup(posts = listOf(TallyMark.NOT_YET), gate = null),
            ),
            layout?.groups,
        )
        assertEquals(
            listOf(
                TallyMark.MET, TallyMark.MET, TallyMark.MET,
                TallyMark.PARTIAL, TallyMark.PARTIAL,
                TallyMark.NOT_YET,
            ),
            layout?.marks(),
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
        assertEquals(4, layout?.marks()?.size)
    }

    @Test
    @DisplayName("a tally is kept in fives: four posts, and the fifth struck across them")
    fun groupsOfFive() {
        val four = groupsOf(4)
        assertEquals(1, four.size)
        assertEquals(4, four[0].posts.size)
        assertNull(four[0].gate)
        assertFalse(four[0].gated)

        val five = groupsOf(5)
        assertEquals(1, five.size)
        assertEquals(4, five[0].posts.size)
        assertEquals(TallyMark.MET, five[0].gate)
        assertTrue(five[0].gated)

        val six = groupsOf(6)
        assertEquals(listOf(4, 1), six.map { it.posts.size })
        assertEquals(listOf(true, false), six.map { it.gated })

        val dozen = groupsOf(12)
        assertEquals(listOf(4, 4, 2), dozen.map { it.posts.size })
        assertEquals(listOf(true, true, false), dozen.map { it.gated })

        assertEquals(emptyList<TallyGroup>(), tallyGroups(emptyList()))
    }

    @Test
    @DisplayName("the gate is the fifth tracker, and carries that tracker's own state")
    fun gateTakesTheFifthState() {
        // Four met and one half done: the posts are ink and the gate is the
        // partial one. Because the marks are state-sorted, a gate is never more
        // done than the posts it crosses.
        val partialGate = tally(
            CategoryRollup(habitsMet = 4, habitsPartial = 1),
            WidgetBucket.CARD,
            180f,
        )
        assertEquals(List(4) { TallyMark.MET }, partialGate?.groups?.single()?.posts)
        assertEquals(TallyMark.PARTIAL, partialGate?.groups?.single()?.gate)

        val openGate = tally(
            CategoryRollup(habitsMet = 4, habitsNotYet = 1),
            WidgetBucket.CARD,
            180f,
        )
        assertEquals(TallyMark.NOT_YET, openGate?.groups?.single()?.gate)

        // The twelve-tracker day: the half-done tracker is the third post of
        // the first group, and both gates are still to come.
        val layout = tally(twelve, WidgetBucket.CARD, 180f)
        assertEquals(
            listOf(TallyMark.MET, TallyMark.MET, TallyMark.PARTIAL, TallyMark.NOT_YET),
            layout?.groups?.get(0)?.posts,
        )
        assertEquals(listOf(TallyMark.NOT_YET, TallyMark.NOT_YET, null), layout?.groups?.map { it.gate })
    }

    @Test
    @DisplayName("the width of a row of marks, from one tracker to fifty")
    fun marksWidthTable() {
        // A post is 2dp with 3dp between posts; a full five is the gate's 21dp
        // box; 8dp separates one cluster of posts from the next. The spec's
        // geometry table, number for number.
        assertEquals(2f, TALLY_POST_DP)
        assertEquals(12f, TALLY_MARK_HEIGHT_DP)
        assertEquals(
            listOf(2f, 17f, 21f, 29f, 39f, 44f, 46f, 59f, 96f, 146f, 246f),
            listOf(1, 4, 5, 6, 8, 9, 10, 12, 20, 30, 50).map { tallyMarksWidthDp(groupsOf(it)) },
        )
        assertEquals(0f, tallyMarksWidthDp(emptyList()))
    }

    @Test
    @DisplayName("a group's own width: the gate's box when gated, just its posts when not")
    fun groupWidths() {
        assertEquals(
            listOf(2f, 7f, 12f, 17f, 21f),
            (1..5).map { tallyGroupWidthDp(groupsOf(it).single()) },
        )
    }

    @Test
    @DisplayName("both group gaps leave 8dp between one cluster of posts and the next")
    fun groupStarts() {
        val gated = groupsOf(5).single()
        val ungated = groupsOf(3).single()

        // The first group starts on the keyline whatever it is.
        assertEquals(0f, tallyGroupStartDp(0, gated))
        assertEquals(0f, tallyGroupStartDp(0, ungated))
        // A gated group brings 2dp of overhang at each edge, so 4dp of gap is
        // 2 + 4 + 2 post to post; an ungated one brings none on its own side,
        // so it takes 6dp after the 2dp the group before it overhangs.
        assertEquals(4f, tallyGroupStartDp(1, gated))
        assertEquals(6f, tallyGroupStartDp(1, ungated))
        assertEquals(4f, tallyGroupStartDp(7, gated))
    }

    @Test
    @DisplayName("posts stand 3dp apart, and a gated group's first stands in by the overhang")
    fun postStarts() {
        assertEquals(listOf(2f, 3f, 3f, 3f), (0..3).map { tallyPostStartDp(it, gated = true) })
        assertEquals(listOf(0f, 3f, 3f, 3f), (0..3).map { tallyPostStartDp(it, gated = false) })

        // The four posts and their gaps span exactly the gate's box less its
        // far overhang: 2 + 2 + 3×(3 + 2) = 19 of 21.
        assertEquals(19f, (0..3).map { tallyPostStartDp(it, gated = true) + TALLY_POST_DP }.sum())
    }

    @Test
    @DisplayName("twelve trackers draw their marks at every size")
    fun twelveTrackersAtEverySize() {
        // The reason for the change: as dots, a twelfth tracker pushed every
        // size down to the sentence. Twelve marks are 59dp; `2 OF 12 DONE` is
        // 75.6dp and `2/12` is 25.2dp.

        // The 2×1 floor: 86dp of content holds the marks and nothing beside
        // them (86 − 59 − 8 = 19).
        val floor = tally(twelve, WidgetBucket.STRIP, 110f)
        assertEquals(12, floor?.marks()?.size)
        assertNull(floor?.text)

        // A typical 2×1: 116dp of content leaves 49dp, room for the fraction.
        val strip = tally(twelve, WidgetBucket.STRIP, 140f)
        assertEquals(12, strip?.marks()?.size)
        assertEquals("2/12", strip?.text)

        // CARD and PAGE at their 180dp floor: 59 + 8 + 75.6 = 142.6 ≤ 156.
        listOf(WidgetBucket.CARD, WidgetBucket.PAGE).forEach { bucket ->
            val page = tally(twelve, bucket, 180f)
            assertEquals(12, page?.marks()?.size)
            assertEquals("2 OF 12 DONE", page?.text)
        }
    }

    @Test
    @DisplayName("the WIDE tally fits its own column: marks and the fraction at the floor")
    fun wideColumnAtItsFloor() {
        // 250dp wide is a 124.3dp tally column: 59 + 8 leaves 57.3dp, which
        // holds `2/12` and not the 75.6dp sentence.
        val layout = tally(twelve, WidgetBucket.WIDE, tallyFitWidthDp(WidgetBucket.WIDE, 250f))

        assertEquals(12, layout?.marks()?.size)
        assertEquals("2/12", layout?.text)
    }

    @Test
    @DisplayName("the five rungs, in order, as the same tally loses room")
    fun theFitLadder() {
        // One rollup, five widths: 146dp of marks, a 75.6dp sentence and a
        // 25.2dp fraction, so each step is the one below the last thing that
        // stopped fitting. Thirty trackers are six groups, well inside the
        // child budget, so only width decides here; the budget is pinned
        // separately below. The narrow widths are under every bucket's floor —
        // the parameter under test is the width, not the bucket.
        val rollup = CategoryRollup(habitsMet = 8, habitsNotYet = 22)

        // 236 − 146 − 8 = 82 ≥ 75.6.
        val sentenceBeside = tally(rollup, WidgetBucket.CARD, 260f)
        assertEquals(30, sentenceBeside?.marks()?.size)
        assertEquals("8 OF 30 DONE", sentenceBeside?.text)

        // 186 − 146 − 8 = 32: the sentence is out, the fraction is in.
        val fractionBeside = tally(rollup, WidgetBucket.CARD, 210f)
        assertEquals(30, fractionBeside?.marks()?.size)
        assertEquals("8/30", fractionBeside?.text)

        // 156 − 146 − 8 = 2: nothing goes beside, and the marks still fit.
        val marksAlone = tally(rollup, WidgetBucket.CARD, 180f)
        assertEquals(30, marksAlone?.marks()?.size)
        assertNull(marksAlone?.text)

        // 86dp of content: the marks are out, the sentence fits alone.
        val sentence = tally(rollup, WidgetBucket.CARD, 110f)
        assertNull(sentence?.groups)
        assertEquals("8 OF 30 DONE", sentence?.text)

        // 36dp of content: the floor.
        val fraction = tally(rollup, WidgetBucket.CARD, 60f)
        assertNull(fraction?.groups)
        assertEquals("8/30", fraction?.text)
    }

    @Test
    @DisplayName("the separation is part of the fit: a count that would touch the marks is not beside them")
    fun separationBoundary() {
        // Twelve marks and `2/12`: 59 + 8 + 25.2 = 92.2dp of content, so
        // 116.2dp of widget. A hair under that and the fraction goes.
        assertEquals("2/12", tally(twelve, WidgetBucket.STRIP, 116.3f)?.text)
        assertNull(tally(twelve, WidgetBucket.STRIP, 116.1f)?.text)
        assertEquals(12, tally(twelve, WidgetBucket.STRIP, 116.1f)?.marks()?.size)
    }

    @Test
    @DisplayName("the render budget: ten children, counted in groups, a count costing two more")
    fun theGlanceChildBudget() {
        // A Glance container renders at most ten children and silently DROPS
        // the rest — on a device that read as five met dots and every mark
        // after them gone. So the budget is a term of the ladder, not a layout
        // accident. The row's children are the groups: at a width where
        // everything fits, eight groups can take the count (8 + spacer + text =
        // 10), nine and ten stand alone, and eleven fall to the sentence
        // outright.
        val wide = 400f

        val forty = tally(CategoryRollup(habitsMet = 9, habitsNotYet = 31), WidgetBucket.CARD, wide)
        assertEquals(8, forty?.groups?.size)
        assertEquals(40, forty?.marks()?.size)
        assertEquals("9 OF 40 DONE", forty?.text)

        val fortyOne = tally(CategoryRollup(habitsMet = 9, habitsNotYet = 32), WidgetBucket.CARD, wide)
        assertEquals(9, fortyOne?.groups?.size)
        assertEquals(41, fortyOne?.marks()?.size)
        assertNull(fortyOne?.text)

        val fifty = tally(CategoryRollup(habitsMet = 9, habitsNotYet = 41), WidgetBucket.CARD, wide)
        assertEquals(10, fifty?.groups?.size)
        assertEquals(50, fifty?.marks()?.size)
        assertNull(fifty?.text)

        val fiftyOne = tally(CategoryRollup(habitsMet = 9, habitsNotYet = 42), WidgetBucket.CARD, wide)
        assertNull(fiftyOne?.groups)
        assertEquals("9 OF 51 DONE", fiftyOne?.text)
    }

    @Test
    @DisplayName("whatever is drawn is the whole tally: every mark, inside the width and the budget")
    fun marksAreNeverTruncated() {
        // The rule the ladder exists for, swept rather than sampled: at any
        // count, width and font scale, a layout that draws marks draws ALL of
        // them, in a row Glance will render whole and the content can hold.
        for (count in 1..60) {
            for (width in listOf(60f, 110f, 140f, 180f, 250f, 400f)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    val met = count / 3
                    val partial = count % 3
                    val rollup = CategoryRollup(
                        habitsMet = met,
                        habitsPartial = partial,
                        habitsNotYet = count - met - partial,
                    )
                    val layout = tally(rollup, WidgetBucket.CARD, width, scale)
                    val where = "$count trackers at ${width}dp ×$scale"

                    assertNotNull(layout, where)
                    assertTrue(layout!!.groups != null || layout.text != null, where)
                    val content = width - 24f
                    // The estimate the ladder fits with, restated: 6.3dp a
                    // character, times the font scale.
                    val textWidth = (layout.text?.length ?: 0) * 6.3f * scale
                    val groups = layout.groups
                    if (groups == null) {
                        // Words alone. The sentence is only ever chosen where
                        // it fits; `N/M` is the floor and draws regardless.
                        if (layout.text!!.contains(" OF ")) {
                            assertTrue(textWidth <= content, where)
                        }
                        continue
                    }
                    assertEquals(count, layout.marks()?.size, where)
                    val marksWidth = tallyMarksWidthDp(groups)
                    assertTrue(marksWidth <= content, where)
                    // A count beside the marks is inside the row WITH its 8dp
                    // of separation — marks that fit and words that overflow
                    // them would be a truncated count.
                    if (layout.text != null) {
                        assertTrue(marksWidth + 8f + textWidth <= content, where)
                    }
                    val children = groups.size + if (layout.text != null) 2 else 0
                    assertTrue(children <= 10, where)
                }
            }
        }
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

        assertNull(layout?.groups)
        assertEquals("60/100", layout?.text)
    }

    @Test
    @DisplayName("a larger system font shrinks the words, never the marks")
    fun fontScaleDegradesTheRow() {
        val unscaled = tally(twelve, WidgetBucket.CARD, 180f, fontScale = 1f)
        assertEquals(12, unscaled?.marks()?.size)
        assertEquals("2 OF 12 DONE", unscaled?.text)

        // At 1.3 the sentence measures 98.3dp against the 89dp beside the
        // marks — and the marks, being dp, have not moved. The count steps down
        // to the fraction (32.8dp) rather than leaving. Without the scale this
        // layout would have been chosen for a width it overflows.
        val scaled = tally(twelve, WidgetBucket.CARD, 180f, fontScale = 1.3f)
        assertEquals(12, scaled?.marks()?.size)
        assertEquals("2/12", scaled?.text)
    }

    @Test
    @DisplayName("the five rungs again at font scale 1.3: every boundary moves, the order does not")
    fun theFitLadderAtLargeFont() {
        // The same thirty trackers as theFitLadder: 146dp of marks, which the
        // scale does not touch, beside a sentence now 98.3dp and a fraction
        // now 32.8dp. Three of these widths sat one rung higher at 1.0.
        val rollup = CategoryRollup(habitsMet = 8, habitsNotYet = 22)
        fun at(widthDp: Float) = tally(rollup, WidgetBucket.CARD, widthDp, fontScale = 1.3f)

        // 256 − 146 − 8 = 102 ≥ 98.3.
        assertEquals(30, at(280f)?.marks()?.size)
        assertEquals("8 OF 30 DONE", at(280f)?.text)

        // 236 − 146 − 8 = 82: held the sentence at 1.0, holds the fraction now.
        assertEquals(30, at(260f)?.marks()?.size)
        assertEquals("8/30", at(260f)?.text)

        // 186 − 146 − 8 = 32 < 32.8: held the fraction at 1.0, marks alone now.
        assertEquals(30, at(210f)?.marks()?.size)
        assertNull(at(210f)?.text)

        // 106dp of content: no marks, and the 98.3dp sentence fits alone.
        assertNull(at(130f)?.groups)
        assertEquals("8 OF 30 DONE", at(130f)?.text)

        // 86dp of content: held the sentence at 1.0, the floor now.
        assertNull(at(110f)?.groups)
        assertEquals("8/30", at(110f)?.text)
    }

    @Test
    @DisplayName("font scale can walk a strip's count all the way down to N/M")
    fun fontScaleReachesTheCompactForm() {
        // Sixty trackers are twelve groups, past the child budget, so the row
        // is words at any width: 75.6dp fits the strip's 86dp, 98.3dp does not.
        val rollup = CategoryRollup(habitsMet = 4, habitsNotYet = 56)

        assertEquals("4 OF 60 DONE", tally(rollup, WidgetBucket.STRIP, 110f)?.text)
        assertEquals("4/60", tally(rollup, WidgetBucket.STRIP, 110f, fontScale = 1.3f)?.text)
        assertNull(tally(rollup, WidgetBucket.STRIP, 110f)?.groups)
    }

    @Test
    @DisplayName("at the strip's floor: ten marks with their fraction, seventeen alone, then the count")
    fun stripFloorArithmetic() {
        // 86dp of content. Ten marks are 46dp and leave 32dp, which holds
        // `4/10` (25.2dp) but never the sentence.
        val ten = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 6), WidgetBucket.STRIP, 110f)
        assertEquals(10, ten?.marks()?.size)
        assertEquals("4/10", ten?.text)

        // Seventeen marks are 84dp: they fit, alone.
        val seventeen = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 13), WidgetBucket.STRIP, 110f)
        assertEquals(17, seventeen?.marks()?.size)
        assertNull(seventeen?.text)

        // Eighteen are 89dp: the marks go, whole, and the sentence takes over.
        val eighteen = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 14), WidgetBucket.STRIP, 110f)
        assertNull(eighteen?.groups)
        assertEquals("4 OF 18 DONE", eighteen?.text)

        // A finished day of twenty: 96dp of marks, an 81.9dp sentence.
        val twenty = tally(CategoryRollup(habitsMet = 20), WidgetBucket.STRIP, 110f)
        assertNull(twenty?.groups)
        assertEquals("20 OF 20 DONE", twenty?.text)
    }

    @Test
    @DisplayName("a strip dragged wider uses the width it was given")
    fun stripUsesRealWidth() {
        // The first device build drew a lone fraction on a strip twice its
        // floor, because Responsive handed the fit rule the 110dp bucket size
        // instead of the real one. With SizeMode.Exact the width is the truth:
        // eight marks and the sentence both fit 376dp of content.
        val layout = tally(CategoryRollup(habitsMet = 4, habitsNotYet = 4), WidgetBucket.STRIP, 400f)

        assertEquals(8, layout?.marks()?.size)
        assertEquals("4 OF 8 DONE", layout?.text)
    }

    @Test
    @DisplayName("the spoken sentence names the partial marks nobody can hear")
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
            debtText = "debt 0:30",
            // A napped day, to pin what the widget does NOT say: the nap is
            // already inside the headline need, and the cell has no room to
            // name it, so the sentence read aloud is the one drawn — the app
            // card's `cardDebtLine` is the surface that names it.
            napText = "nap −0:45",
            resetText = null,
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
    @DisplayName("the seven mark drawables are seven different drawables")
    fun marksAreDistinct() {
        // Guards every mapping below from passing on a tree where the ids all
        // resolved to the same value.
        assertEquals(
            7,
            setOf(
                R.drawable.widget_mark_filled,
                R.drawable.widget_mark_half,
                R.drawable.widget_mark_hollow,
                R.drawable.widget_tally_post,
                R.drawable.widget_tally_post_half,
                R.drawable.widget_tally_gate,
                R.drawable.widget_tally_gate_half,
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
    @DisplayName("a stroke is ink when met, faint when not yet, and both when half done")
    fun strokeInk() {
        assertEquals(
            listOf(StrokeLayer(StrokePart.WHOLE, WidgetTint.INK)),
            strokeLayers(TallyMark.MET),
        )
        assertEquals(
            listOf(StrokeLayer(StrokePart.WHOLE, WidgetTint.INK_FAINT)),
            strokeLayers(TallyMark.NOT_YET),
        )
        // Order is z-order, bottom first: the whole stroke in faint, then its
        // first half in ink over it. The other way round the faint layer would
        // paint the ink out, and a half-done tracker would read as not started.
        assertEquals(
            listOf(
                StrokeLayer(StrokePart.WHOLE, WidgetTint.INK_FAINT),
                StrokeLayer(StrokePart.FIRST_HALF, WidgetTint.INK),
            ),
            strokeLayers(TallyMark.PARTIAL),
        )
    }

    @Test
    @DisplayName("a partial mark keeps its full length — it never reads as a smaller mark")
    fun partialKeepsItsLength() {
        // Every state's bottom layer is the WHOLE stroke, so the three states
        // differ in ink and never in extent.
        TallyMark.entries.forEach { mark ->
            assertEquals(StrokePart.WHOLE, strokeLayers(mark).first().part)
        }
    }

    @Test
    @DisplayName("posts and gates each have a whole drawable and a first-half one")
    fun strokeDrawables() {
        assertEquals(R.drawable.widget_tally_post, postDrawable(StrokePart.WHOLE))
        assertEquals(R.drawable.widget_tally_post_half, postDrawable(StrokePart.FIRST_HALF))
        assertEquals(R.drawable.widget_tally_gate, gateDrawable(StrokePart.WHOLE))
        assertEquals(R.drawable.widget_tally_gate_half, gateDrawable(StrokePart.FIRST_HALF))
    }

    @Test
    @DisplayName("a gate's faint ink goes under the posts and its ink over them")
    fun gateLayerOrder() {
        val faintWhole = StrokeLayer(StrokePart.WHOLE, WidgetTint.INK_FAINT)

        // A gate not yet met must not cut posts that are: all of it is under.
        assertEquals(listOf(faintWhole), gateLayersUnder(TallyMark.NOT_YET))
        assertEquals(emptyList<StrokeLayer>(), gateLayersOver(TallyMark.NOT_YET))

        // A met gate strikes across the posts.
        assertEquals(emptyList<StrokeLayer>(), gateLayersUnder(TallyMark.MET))
        assertEquals(listOf(StrokeLayer(StrokePart.WHOLE, WidgetTint.INK)), gateLayersOver(TallyMark.MET))

        // A half-done gate is split across them: faint below, its ink half above.
        assertEquals(listOf(faintWhole), gateLayersUnder(TallyMark.PARTIAL))
        assertEquals(
            listOf(StrokeLayer(StrokePart.FIRST_HALF, WidgetTint.INK)),
            gateLayersOver(TallyMark.PARTIAL),
        )

        // An ungated group draws no gate at all.
        assertEquals(emptyList<StrokeLayer>(), gateLayersUnder(null))
        assertEquals(emptyList<StrokeLayer>(), gateLayersOver(null))
    }

    @Test
    @DisplayName("under and over together are exactly the gate's layers — none lost, none drawn twice")
    fun gateLayersAreComplete() {
        TallyMark.entries.forEach { gate ->
            assertEquals(
                strokeLayers(gate).toSet(),
                (gateLayersUnder(gate) + gateLayersOver(gate)).toSet(),
            )
            assertEquals(strokeLayers(gate).size, gateLayersUnder(gate).size + gateLayersOver(gate).size)
        }
    }

    @Test
    @DisplayName("a group box never needs more than three children, nor a post more than two")
    fun groupBoxChildCeiling() {
        // The audited ceilings in the spec's Glance constraints: gate under,
        // the posts row, gate over; and the two layers of a partial post.
        TallyMark.entries.forEach { mark ->
            assertTrue(gateLayersUnder(mark).size + 1 + gateLayersOver(mark).size <= 3)
            assertTrue(strokeLayers(mark).size <= 2)
        }
    }
}
