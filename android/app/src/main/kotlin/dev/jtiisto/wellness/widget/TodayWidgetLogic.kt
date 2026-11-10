package dev.jtiisto.wellness.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.R
import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.journal.describeCategoryRollup
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.trends.SleepTonightModel
import dev.jtiisto.wellness.core.data.trends.TonightJudgment
import dev.jtiisto.wellness.core.data.trends.TrendsCachePeek
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import dev.jtiisto.wellness.core.data.trends.sleepTonightModel
import java.time.LocalDate

/**
 * Every decision the "Today" widget makes, with no Glance in the file.
 *
 * The widget's own classes cannot execute off a device — they need a `GlanceId`,
 * an AppWidget host or WorkManager's runtime — so anything that could be *wrong*
 * is pulled down here into plain functions and pinned by `TodayWidgetLogicTest`.
 * The Kover exclusions the surface files carry are a claim that this file holds
 * their decisions; the claim is only true while it does.
 *
 * The size types come from `androidx.compose.ui.unit` rather than from Glance
 * deliberately: `DpSize` is what `LocalSize` hands the composable anyway, and
 * importing it from the plain Compose artifact keeps this file constructible in
 * a JVM test with no RemoteViews behind it.
 *
 * Nothing here fetches, remembers a clock, or reaches for a theme. `now` and
 * `today` arrive as parameters for the reason `sleepTonightModel` takes them
 * that way: a widget that has sat on a home screen since yesterday must not
 * answer with yesterday's `today`.
 *
 * See `specs/widget.md`.
 */

// ---- Cache keys and the fetch window ---------------------------------------

/**
 * The range the widget's own worker writes its copy under.
 *
 * Not a user range and never offered as one: it names *whose* copy this is, so
 * the hourly fetch cannot invalidate the window the user is reading in Trends,
 * and a range change on that screen cannot orphan the widget's row.
 */
const val WIDGET_RANGE = "widget"

/**
 * Where to look for a sleep payload, in preference order.
 *
 * Own key first, because the worker keeps it freshest. The user's range second
 * is not a lesser answer but a *different copy of the same number*: `tonight` is
 * range-independent — the server computes the ledger over its own history and
 * clips only `days`, which this surface never draws — so a 12-week copy carries
 * the identical tonight. That fallback is what makes a widget placed before the
 * worker's first run show a number instead of the pending floor.
 */
fun widgetPeekKeys(userRange: String): List<String> = listOf(
    TrendsRepository.sleepKey(WIDGET_RANGE),
    TrendsRepository.sleepKey(userRange),
)

/**
 * A week of nights, ending today — the request the worker makes.
 *
 * The window exists only to be a legal request: nothing on this surface draws
 * `days`, and the ledger behind `tonight` is computed from the server's whole
 * history regardless. Seven days is small enough to be cheap hourly and long
 * enough that the trailing row (the one whose `gap` raises ATTENTION) is always
 * in it.
 */
fun widgetFetchWindow(today: LocalDate): Pair<DateString, DateString> =
    today.minusDays(WIDGET_WINDOW_DAYS - 1).toString() to today.toString()

private const val WIDGET_WINDOW_DAYS = 7L

// ---- Freshness --------------------------------------------------------------

/**
 * How long a peeked copy is allowed to call itself current: the hourly refresh
 * period plus half of one, which is Doze's slack. A single missed worker run
 * does not flip the badge; two consecutive ones do.
 */
const val WIDGET_FRESH_WINDOW_MS = 90 * 60_000L

/**
 * The stamp to hand `sleepTonightModel`, or null while the copy counts as fresh.
 *
 * Every widget render is by definition from cache, so passing the raw stamp
 * through would brand *every* render `cached · Nm ago` — including one drawn
 * ninety seconds after a successful fetch — and a badge that is always on says
 * nothing. Within [WIDGET_FRESH_WINDOW_MS] the copy is treated as fresh (null,
 * so SETTLED stays reachable); past it the real stamp goes through and the
 * honest `cachedLine` and its PARTIAL judgment take over.
 *
 * A stamp in the future — a clock moved backwards — reads fresh, which is the
 * harmless direction: it costs one badge, where the other way costs a permanent
 * false one.
 */
fun widgetStaleFetchedAt(fetchedAt: Long, now: Long): Long? =
    if (now - fetchedAt < WIDGET_FRESH_WINDOW_MS) null else fetchedAt

/**
 * Tonight's card from whatever the peek found, or null for the pending floor.
 *
 * The whole reduction is `sleepTonightModel`'s, unchanged and shared with the
 * in-app card — this only decides which staleness the model is told about.
 */
fun widgetModel(
    peeked: TrendsCachePeek.PeekedSleep?,
    now: Long,
    today: DateString,
): SleepTonightModel? {
    val row = peeked ?: return null
    return sleepTonightModel(
        dto = row.dto,
        staleFetchedAt = widgetStaleFetchedAt(row.fetchedAt, now),
        now = now,
        today = today,
    )
}

/**
 * Whether the hourly worker may talk to a server.
 *
 * `SyncFlushWorker`'s guard, with the widget's own answer to it: an unresolved
 * server means `TrendsRepository` cannot even be constructed (its `TrendsApi`
 * resolves `requireConfig()`, which throws), and a closed gate means a server
 * switch has been confirmed and this process is finishing. Neither is retryable
 * by waiting, and the widget's response to both is the same — skip the fetch,
 * re-render what is cached, and let the next hour try.
 */
fun shouldFetch(resolved: Boolean, gateOpen: Boolean): Boolean = resolved && gateOpen

// ---- Size buckets -----------------------------------------------------------

/** Tally only: the 2×1 strip. */
val STRIP = DpSize(110.dp, 40.dp)

/**
 * Tally · rule · the sleep block, with the compact headline: the floor at font
 * scale 1.0, which a 4×1 on the reference launcher (110.5dp tall) clears.
 * Agrees with [widgetBucket] at 1.0 by construction.
 */
val CARD = DpSize(STACKED_MIN_WIDTH_DP.dp, stackHeightDp(WidgetStack.CARD_COMPACT, 1f).dp)

/**
 * Everything, card-faithful: strain and the honesty lines join. 207.5dp at font
 * scale 1.0 — the measured gap-night worst case, where until 2026-11-10 this was
 * a guessed 170 that could not hold the page it promised.
 */
val PAGE = DpSize(STACKED_MIN_WIDTH_DP.dp, stackHeightDp(WidgetStack.PAGE, 1f).dp)

/** Which of the three compositions a given size gets. */
enum class WidgetBucket { STRIP, WIDE, CARD, PAGE }

/**
 * The bucket for the widget's real size (`SizeMode.Exact`) at the reader's
 * system font scale.
 *
 * Both dimensions have to clear a stacked floor: the sleep block's headline
 * needs the 180dp width whatever the height is, so a tall narrow widget stays a
 * STRIP rather than rendering a truncated number. The height floors are the
 * measured stacks of `specs/widget.md` §Vertical budgets at [fontScale] — a
 * widget too short for a stack at the reader's text size falls to the next
 * bucket down instead of clipping its last line. CARD height outranks WIDE:
 * given the height to stack, the page stacks.
 *
 * Nothing is shrunk to fit, with the one dated exception the spec records
 * (owner, 2026-11-10): at CARD the need headline drops to a compact size when
 * the widget is too short for 24sp — see [headlineStep].
 */
fun widgetBucket(size: DpSize, fontScale: Float): WidgetBucket {
    val stacks = size.width.value >= STACKED_MIN_WIDTH_DP
    val height = size.height.value
    return when {
        stacks && height >= stackHeightDp(WidgetStack.PAGE, fontScale) -> WidgetBucket.PAGE
        stacks && height >= stackHeightDp(WidgetStack.CARD_COMPACT, fontScale) -> WidgetBucket.CARD
        size.width.value >= WIDE_MIN_WIDTH_DP -> WidgetBucket.WIDE
        else -> WidgetBucket.STRIP
    }
}

/** The width both stacked buckets need: the sleep block's widest headline. */
const val STACKED_MIN_WIDTH_DP = 180f

/**
 * WIDE's entry width: a strip-height shape with room for two ideas side by
 * side. Below it, one idea (STRIP); at CARD height the page stacks instead.
 */
const val WIDE_MIN_WIDTH_DP = 250

/**
 * The tally's share of a WIDE row — the rest is the vertical rule and the
 * compact sleep line. Tally-first because that is the element priority; the
 * split is fixed rather than measured so the two columns cannot fight over a
 * boundary that moves with the day's counts.
 */
fun wideTallyWidthDp(bucketWidthDp: Float): Float =
    (bucketWidthDp - 2 * WIDGET_PADDING_DP) * 0.55f

/**
 * The width [tallyLayout] is fitted against: the page for the stacked buckets,
 * the tally's own column at WIDE. The `+ padding` term compensates the
 * subtraction [tallyLayout] performs on what it assumes is a page width, so the
 * ladder sees exactly the column the row will actually occupy.
 */
fun tallyFitWidthDp(bucket: WidgetBucket, bucketWidthDp: Float): Float = when (bucket) {
    WidgetBucket.WIDE -> wideTallyWidthDp(bucketWidthDp) + 2 * WIDGET_PADDING_DP
    else -> bucketWidthDp
}

/** The sleep block — glyph, eyebrow, headline, debt — from CARD up. */
fun showsSleep(bucket: WidgetBucket): Boolean =
    bucket == WidgetBucket.CARD || bucket == WidgetBucket.PAGE

/** Strain is the first thing dropped: it is context, not the answer. */
fun showsStrain(bucket: WidgetBucket): Boolean = bucket == WidgetBucket.PAGE

/**
 * `freshnessLine` and `cachedLine`, at PAGE only.
 *
 * A measured compromise, not an oversight: at the 180×110dp CARD floor the
 * headline, the debt line and a words line cannot all fit, and the judgment
 * glyph already *is* the caveat signal — PARTIAL is defined as "cachedLine or
 * freshnessLine present". At CARD the glyph carries it alone; the words wait one
 * size up, or one tap away.
 */
fun showsHonestyLines(bucket: WidgetBucket): Boolean = bucket == WidgetBucket.PAGE

/**
 * The strip's one line when the tally has nothing to draw: the judgment mark and
 * the number, and no label at all.
 *
 * A 2×1 holds one idea, and that idea is normally the tally — but on a day that
 * expects no habits there is no tally, and a blank strip reads as broken. So the
 * next element in priority order takes the line, in the only form 86dp of
 * content can hold whole: `TONIGHT'S SLEEP NEED` alone measures about 126dp in
 * mono and wraps at the floor, which is why the eyebrow is dropped rather than
 * shrunk. The glyph is not decoration here — it is the sentence the label would
 * have carried.
 */
fun showsCompactSleep(bucket: WidgetBucket, hasTally: Boolean): Boolean = when (bucket) {
    // WIDE always pairs the two: the whole point of the shape is a second
    // column, and the compact line is the only sleep form a strip height holds.
    WidgetBucket.WIDE -> true
    else -> !showsSleep(bucket) && !hasTally
}

// ---- Vertical budgets ------------------------------------------------------

/**
 * The three stacked pages the floors are budgeted for (`specs/widget.md`
 * §Vertical budgets): CARD with the compact 16sp headline, CARD with the 24sp
 * one, and PAGE's gap-night worst case.
 */
enum class WidgetStack { CARD_COMPACT, CARD_24SP, PAGE }

/**
 * How tall [stack] draws at [fontScale], with 2dp to spare: its fixed dp
 * (padding, rule, gaps) plus its lines of text, and only the text grows with
 * the scale.
 *
 * The scale is applied linearly, which is an **upper bound**: Android scales
 * large text by less than the nominal factor (the 32sp headline does not grow
 * at all at 1.3), so the estimate can send a widget one bucket down that would
 * have fitted and can never draw one whose counted lines clip. A line that
 * wraps on a narrow widget is not counted (`specs/widget.md` §Vertical budgets,
 * *Width*): a gap-night debt line on a CARD, or a long freshness line on a
 * PAGE, narrower than the reference cells. A scale below 1.0 does not
 * lower a floor — the line constants were measured at 1.0 and are the floor of
 * what a line costs.
 */
fun stackHeightDp(stack: WidgetStack, fontScale: Float): Float {
    val (fixed, text) = when (stack) {
        WidgetStack.CARD_COMPACT -> CARD_FIXED_DP to CARD_COMPACT_TEXT_DP
        WidgetStack.CARD_24SP -> CARD_FIXED_DP to CARD_24SP_TEXT_DP
        WidgetStack.PAGE -> PAGE_FIXED_DP to PAGE_TEXT_DP
    }
    return fixed + text * maxOf(fontScale, 1f) + STACK_SPARE_DP
}

/** The sizes the need headline is drawn at; [headlineSp] maps each to its sp. */
enum class HeadlineStep {
    /** 16sp: a CARD too short for 24sp — the owner's one exception to never shrinking. */
    COMPACT,

    /** 24sp: StatTile's own size, at a CARD with the height for it. */
    CARD,

    /** 32sp: the page's number takes the room it has. */
    PAGE,
}

/**
 * The headline's size at [bucket], for a widget [heightDp] tall at [fontScale].
 *
 * PAGE draws 32sp. CARD draws 24sp once the real height reaches the 24sp
 * stack, and the compact 16sp below it — the largest whole size that holds the
 * 110dp floor with 2dp to spare. STRIP and WIDE draw no headline (their sleep
 * line is the meta-size compact row); they answer [HeadlineStep.COMPACT] so the
 * function is total, and nothing reads it there.
 */
fun headlineStep(bucket: WidgetBucket, heightDp: Float, fontScale: Float): HeadlineStep = when (bucket) {
    WidgetBucket.PAGE -> HeadlineStep.PAGE
    WidgetBucket.CARD ->
        if (heightDp >= stackHeightDp(WidgetStack.CARD_24SP, fontScale)) HeadlineStep.CARD else HeadlineStep.COMPACT
    WidgetBucket.STRIP, WidgetBucket.WIDE -> HeadlineStep.COMPACT
}

/** The headline's font size in sp; the composable builds its style from this number. */
fun headlineSp(step: HeadlineStep): Float = when (step) {
    HeadlineStep.COMPACT -> 16f
    HeadlineStep.CARD -> 24f
    HeadlineStep.PAGE -> 32f
}

/**
 * The air on each side of the tally/sleep rule: a page's worth at PAGE, where
 * there is height to spend, and 6dp everywhere else.
 */
fun blockGapDp(bucket: WidgetBucket): Float =
    if (bucket == WidgetBucket.PAGE) PAGE_BLOCK_GAP_DP else BLOCK_GAP_DP

/**
 * Whether a configuration's font scale differs from the one the widget last
 * laid out for — the whole decision behind the font-scale redraw, so a rotation
 * or a dark-mode switch schedules nothing.
 *
 * A [new] scale that is not finite and positive is never a change, so it is
 * never adopted: `Configuration.fontScale` reads 0 when the field is undefined,
 * and a NaN would compare unequal to everything and report a change on every
 * configuration callback.
 */
fun fontScaleChanged(current: Float, new: Float): Boolean =
    isUsableFontScale(new) && current != new

/**
 * Whether [fontScale] is one the widget can lay out for: finite and positive.
 * The one rule both [fontScaleChanged] and the holder's initial value
 * ([WidgetFontScale]) go by, so the holder can never expose a scale an offer
 * would refuse.
 */
fun isUsableFontScale(fontScale: Float): Boolean = fontScale.isFinite() && fontScale > 0f

/** 12dp of padding on each side; the content is what is left between them. */
private const val WIDGET_PADDING_DP = 12f

// Measured line heights at font scale 1.0 (API 35 emulator, 420dpi, system
// mono), each rounded up to the next half dp — §Vertical budgets has the px.
/** 10.5sp: the eyebrow, the tally count, the cached badge. */
private const val EYEBROW_LINE_DP = 14.5f

/** 11.5sp: debt, `h:mm`, strain, freshness. */
private const val META_LINE_DP = 16f

/** 16sp, computed from the font's vertical metrics. */
private const val HEADLINE_COMPACT_LINE_DP = 22f
private const val HEADLINE_CARD_LINE_DP = 32.5f
private const val HEADLINE_PAGE_LINE_DP = 43f

private const val BLOCK_GAP_DP = 6f
private const val PAGE_BLOCK_GAP_DP = 10f
private const val RULE_DP = 1f

/** The top padding each line under the eyebrow carries inside the sleep block. */
private const val LINE_GAP_DP = 2f

/** Covers the pixel rounding of four to eight lines on an unmeasured density. */
private const val STACK_SPARE_DP = 2f

/** Padding, gap · rule · gap, the headline's and the debt line's top padding, padding: 41dp. */
private const val CARD_FIXED_DP =
    WIDGET_PADDING_DP + (BLOCK_GAP_DP + RULE_DP + BLOCK_GAP_DP) + 2 * LINE_GAP_DP + WIDGET_PADDING_DP

/** Padding, the wider gap · rule · gap, five lines' top padding, padding: 55dp. */
private const val PAGE_FIXED_DP =
    WIDGET_PADDING_DP + (PAGE_BLOCK_GAP_DP + RULE_DP + PAGE_BLOCK_GAP_DP) + 5 * LINE_GAP_DP + WIDGET_PADDING_DP

/** Tally row, eyebrow, the 16sp headline, one debt line: 67dp. */
private const val CARD_COMPACT_TEXT_DP =
    EYEBROW_LINE_DP + EYEBROW_LINE_DP + HEADLINE_COMPACT_LINE_DP + META_LINE_DP

/** Tally row, eyebrow, the 24sp headline, one debt line: 77.5dp. */
private const val CARD_24SP_TEXT_DP =
    EYEBROW_LINE_DP + EYEBROW_LINE_DP + HEADLINE_CARD_LINE_DP + META_LINE_DP

/**
 * Tally row, eyebrow, the 32sp headline, the debt line wrapped to two, strain,
 * freshness, and the cached badge: 150.5dp — a gap night through a stale cache.
 */
private const val PAGE_TEXT_DP =
    EYEBROW_LINE_DP + EYEBROW_LINE_DP + HEADLINE_PAGE_LINE_DP + 2 * META_LINE_DP + META_LINE_DP +
        META_LINE_DP + EYEBROW_LINE_DP

// ---- The tracker tally ------------------------------------------------------

/** One tracker's state in the tally — `categoryRollup`'s three answers, nothing more. */
enum class TallyMark { MET, PARTIAL, NOT_YET }

/**
 * Up to five marks, drawn the way a tally is kept: four upright [posts] and the
 * fifth struck across them as the [gate].
 *
 * [gate] is null for the trailing group that has not reached five. It is a
 * tracker like any other — it carries its own state — and because the marks are
 * state-sorted it is never more done than the posts it crosses.
 */
data class TallyGroup(val posts: List<TallyMark>, val gate: TallyMark?) {
    /** A full five: the group is drawn in the gate's 21dp box, posts inset. */
    val gated: Boolean get() = gate != null
}

/**
 * What the tally row draws after the fit rule has spoken: marks, a count, or
 * both. A null half is a part that is *absent*, never an empty one — and both
 * halves are never null at once, because a tally that exists always renders as
 * something.
 *
 * [text] is whichever count form survived the ladder: the sentence `5 OF 8 DONE`
 * where it fits, the compact `5/8` where it does not.
 */
data class TallyLayout(val groups: List<TallyGroup>?, val text: String?)

/**
 * The marks, five at a time: the first four of each five are posts, the fifth
 * is their gate. Order is preserved — the caller sorted by state and the row
 * reads left to right.
 */
fun tallyGroups(marks: List<TallyMark>): List<TallyGroup> =
    marks.chunked(TALLY_GROUP_SIZE).map { five ->
        TallyGroup(posts = five.take(TALLY_GROUP_SIZE - 1), gate = five.getOrNull(TALLY_GROUP_SIZE - 1))
    }

/**
 * A group's own width: the 21dp box of a gated group — four posts with the gate
 * overhanging them 2dp each side — or just the posts of an ungated one.
 */
fun tallyGroupWidthDp(group: TallyGroup): Float =
    if (group.gated) {
        TALLY_GATED_GROUP_DP
    } else {
        TALLY_POST_DP * group.posts.size + TALLY_POST_GAP_DP * (group.posts.size - 1)
    }

/**
 * The gap in front of the group at [index]. Both non-zero answers put the same
 * 8dp between one cluster of posts and the next: a gated group brings its own
 * 2dp of gate overhang on each side, an ungated one brings none.
 */
fun tallyGroupStartDp(index: Int, group: TallyGroup): Float = when {
    index == 0 -> 0f
    group.gated -> TALLY_GATED_GAP_DP
    else -> TALLY_UNGATED_GAP_DP
}

/**
 * The gap in front of the post at [index] within its group. A gated group's
 * first post stands in from the box edge by the gate's overhang; every later
 * post, gated or not, stands one post gap from the one before.
 */
fun tallyPostStartDp(index: Int, gated: Boolean): Float = when {
    index > 0 -> TALLY_POST_GAP_DP
    gated -> TALLY_GATE_OVERHANG_DP
    else -> 0f
}

/** The whole mark row: every group and the gap in front of it. */
fun tallyMarksWidthDp(groups: List<TallyGroup>): Float {
    var width = 0f
    groups.forEachIndexed { index, group ->
        width += tallyGroupStartDp(index, group) + tallyGroupWidthDp(group)
    }
    return width
}

/**
 * The tally, laid out for [bucket] at [bucketWidthDp] under [fontScale].
 *
 * The element owns no judgment of its own: which trackers were met, partial or
 * not yet is entirely `categoryRollup`'s answer, and this arranges it. Marks
 * read left to right the way tallies fill a line — met, then partial, then not
 * yet — in groups of five.
 *
 * Null when there is nothing to say: no rollup, or a rollup holding no habits.
 * Avoidances and observations get no mark here (the app's ring gives them their
 * own), so a day of nothing but those is a day this element has no line about,
 * and it is omitted with its separator rule rather than drawn empty.
 *
 * **The ladder, in order:** marks + sentence, then marks + `N/M`, then marks
 * alone, then the sentence alone, then `N/M`. Each step is a whole layout —
 * nothing is ever shrunk or clipped to make the step above fit, because a
 * silently shortened row of marks is a confident wrong answer about the day.
 * The last step is the **floor of the never-truncate rule rather than an
 * exception to it**: `N/M` is the shortest true statement of a tally that
 * exists, so it is drawn even where the estimate says it will clip — there is
 * nothing left to fall back to.
 *
 * **The arithmetic is an approximation and says so.** Glance measures on the
 * device and we cannot, so a count's width is taken as [EYEBROW_ADVANCE_DP] per
 * character — 10.5sp of monospace at the ≈0.6em advance a mono face gives —
 * times [fontScale], because the counts are sized in **sp** and the marks in
 * **dp**: at a system font scale of 1.3 the words grow by a third and the marks
 * do not, and a layout fitted at 1.0 would overflow the row it was chosen for.
 */
fun tallyLayout(
    rollup: CategoryRollup?,
    bucket: WidgetBucket,
    bucketWidthDp: Float,
    fontScale: Float,
): TallyLayout? {
    if (rollup == null || rollup.habits == 0) return null

    val groups = tallyGroups(
        buildList {
            repeat(rollup.habitsMet) { add(TallyMark.MET) }
            repeat(rollup.habitsPartial) { add(TallyMark.PARTIAL) }
            repeat(rollup.habitsNotYet) { add(TallyMark.NOT_YET) }
        },
    )
    val text = tallyCountText(rollup)
    val compact = tallyCompactText(rollup)
    val content = bucketWidthDp - 2 * WIDGET_PADDING_DP

    // One ladder for every bucket, because [bucketWidthDp] is the widget's REAL
    // width (SizeMode.Exact), not a bucket floor. The first cut froze a
    // seven-dot strip rule at the 110dp minimum — under Responsive the width
    // handed in here WAS the floor, so a 220dp strip laid out as if it had 86dp
    // of content and drew a lone fraction in a field of paper (first device
    // report). With the width telling the truth, the arithmetic is the rule.
    //
    // Width is not the only budget. A Glance container renders AT MOST
    // [GLANCE_MAX_CHILDREN] children and silently drops the rest — which is a
    // truncated tally, the confident wrong answer this ladder exists to
    // prevent, discovered exactly that way on a device (five met dots survived,
    // every half and open mark after them vanished). So a rung is only open if
    // the row can legally RENDER it. The row's children are the GROUPS, not the
    // marks: with a count it spends one child per group plus the weight spacer
    // plus the text, alone one per group. That is what the tally marks bought —
    // as dots, a twelfth tracker pushed every size down to the sentence.
    val marksWidth = tallyMarksWidthDp(groups)
    val withCount = groups.size + 2 <= GLANCE_MAX_CHILDREN
    val beside = content - marksWidth - TALLY_SEPARATION_DP
    return when {
        withCount && textWidthDp(text, fontScale) <= beside ->
            TallyLayout(groups = groups, text = text)
        // The fraction beside the marks, where the sentence will not go: it is
        // never drawn INSTEAD of marks that fit, only as the count that still
        // fits next to them.
        withCount && textWidthDp(compact, fontScale) <= beside ->
            TallyLayout(groups = groups, text = compact)
        groups.size <= GLANCE_MAX_CHILDREN && marksWidth <= content ->
            TallyLayout(groups = groups, text = null)
        // The count is what is left when the marks will not fit: it states the
        // same fact in fewer characters, and it never lies by omission.
        else -> countOnly(text, compact, content, fontScale)
    }
}

/** The last two rungs: the sentence where it fits, `N/M` where nothing else does. */
private fun countOnly(
    text: String,
    compact: String,
    contentDp: Float,
    fontScale: Float,
): TallyLayout = if (textWidthDp(text, fontScale) <= contentDp) {
    TallyLayout(groups = null, text = text)
} else {
    TallyLayout(groups = null, text = compact)
}

/**
 * The count, in the eyebrow's voice: `5 OF 8 DONE`.
 *
 * `describeCategoryRollup` is the app's sentence for the same cluster and stays
 * the single author of the phrasing — but it speaks the whole cluster, and this
 * surface draws only the habits half of it. So the rollup is projected down to
 * its habits before it is read aloud: the wording still lives in one place, and
 * the widget cannot start claiming avoidances it never marked.
 */
private fun tallyCountText(rollup: CategoryRollup): String = describeCategoryRollup(
    rollup.copy(
        avoidances = 0,
        avoidancesBroken = 0,
        observationsExpected = 0,
        observationsNoted = 0,
    ),
).uppercase()

/**
 * The same count with every word taken out: `5/8`.
 *
 * Not a style choice — a fraction is what a tally reduces to when the row cannot
 * hold a sentence, and the mono face keeps the two numbers legible at the size
 * this appears at. The spoken description is untouched by any of this: what the
 * row draws never changes what the tally *says* (see [tallyContentDescription]).
 */
private fun tallyCompactText(rollup: CategoryRollup): String =
    "${rollup.habitsMet}/${rollup.habits}"

/**
 * What the tally says to someone not looking at it — the whole sentence,
 * whatever the fit rule drew.
 *
 * Deliberately not `describeCategoryRollup`: that sentence describes a category
 * cluster in the app, where the marks beside it disambiguate, and it never
 * states the partial count. Here the marks are only ever positive trackers, and
 * the half-done ones are a fact nobody can hear otherwise — so this names both. If
 * the app's phrasing changes, this does not have to follow it; they answer about
 * different drawings.
 */
fun tallyContentDescription(rollup: CategoryRollup): String = buildString {
    append("${rollup.habitsMet} of ${rollup.habits} positive trackers done")
    if (rollup.habitsPartial > 0) append(", ${rollup.habitsPartial} partial")
}

/**
 * The sleep block read aloud — the card's own sentence, whole, at every bucket.
 *
 * Deliberately **not** filtered by what the bucket draws. A bucket decides how
 * much fits, not how much is true, and the reader who cannot see the judgment
 * glyph is exactly the one who needs the caveat spelled out: at CARD the strain
 * and honesty lines are spoken though no pixel of them is drawn, and the compact
 * strip row speaks the same sentence as the full block. `SleepTonightCard` says
 * it this way in the app, and a widget that said less would be a second, quieter
 * version of the same data.
 *
 * A null model is the pending floor, and it says so rather than staying silent —
 * an unlabelled `-:--` is exactly the thing a screen reader cannot convey.
 */
fun sleepSpoken(model: SleepTonightModel?): String {
    if (model == null) return "Tonight's sleep need pending. no data yet"
    return buildString {
        append("Tonight's sleep need ${model.needText.replace(':', 'h')}m")
        append(". ${model.debtLine}")
        append(". ${model.strainLine}")
        model.freshnessLine?.let { append(". $it") }
        model.cachedLine?.let { append(". $it") }
    }
}

/** A tally is kept in fives: four posts and the gate struck across them. */
private const val TALLY_GROUP_SIZE = 5

/** One upright stroke is 2dp wide. Public for the layout, which sizes its views from it. */
const val TALLY_POST_DP = 2f

/** Every tally stroke's height — posts and the gate's box — inside the 14dp eyebrow line. */
const val TALLY_MARK_HEIGHT_DP = 12f

private const val TALLY_POST_GAP_DP = 3f

/** How far the gate reaches past the first and the last post it crosses. */
private const val TALLY_GATE_OVERHANG_DP = 2f

/** Four posts (17dp) and the gate's overhang on each side. */
private const val TALLY_GATED_GROUP_DP = 21f

/**
 * The gaps in front of a group. Different numbers, one distance: each leaves
 * 8dp between one cluster of posts and the next, since a gated group carries
 * 2dp of overhang at each edge and an ungated one carries none.
 */
private const val TALLY_GATED_GAP_DP = 4f
private const val TALLY_UNGATED_GAP_DP = 6f

/** The gap that keeps the marks and the count from reading as one string. */
private const val TALLY_SEPARATION_DP = 8f

/** 10.5sp monospace ≈ 0.6em per character. An estimate, and only ever a choice. */
private const val EYEBROW_ADVANCE_DP = 6.3f

/**
 * Glance's hard cap on children per Row/Column/Box: the generated RemoteViews
 * layout pool has ten slots, and an eleventh child is DROPPED at render with no
 * error surfaced to the app. Layout code keeps gaps as padding rather than
 * Spacer children for the same reason — a spacer is a child too.
 */
private const val GLANCE_MAX_CHILDREN = 10

/** Text is sized in sp, so the user's font scale is part of its width. */
private fun textWidthDp(text: String, fontScale: Float): Float =
    text.length * EYEBROW_ADVANCE_DP * fontScale

// ---- Marks: drawable and ink ------------------------------------------------

/**
 * The judgment glyph, as one of the three circle drawables.
 *
 * ATTENTION and the pending state share the hollow ring on purpose — it is what
 * `InkJudgment` does too, and they are told apart by ink versus ink-faint (see
 * [judgmentTint]) and by the mono `!` the model raises beside the number. Shape
 * and fill carry the meaning here exactly as they do in the app; no mark on this
 * surface ever carries a hue.
 */
fun judgmentDrawable(model: SleepTonightModel?): Int = when (model?.judgment) {
    TonightJudgment.SETTLED -> R.drawable.widget_mark_filled
    TonightJudgment.PARTIAL -> R.drawable.widget_mark_half
    TonightJudgment.ATTENTION -> R.drawable.widget_mark_hollow
    null -> R.drawable.widget_mark_hollow
}

/**
 * Which of the two inks a mark is drawn in.
 *
 * Named rather than handed out as a colour so this file stays free of Glance:
 * the content maps it to the day/night providers in `TodayWidgetPalette`.
 */
enum class WidgetTint { INK, INK_FAINT }

/** Ink for every judged state; faint only while there is no judgment to make. */
fun judgmentTint(model: SleepTonightModel?): WidgetTint =
    if (model == null) WidgetTint.INK_FAINT else WidgetTint.INK

/** How much of a tally stroke one layer draws. */
enum class StrokePart {
    WHOLE,

    /** The half a stroke starts with: the bottom of a post, the left of a gate. */
    FIRST_HALF,
}

/** One tinted drawable of a tally stroke. */
data class StrokeLayer(val part: StrokePart, val tint: WidgetTint)

/**
 * A tally stroke as the layers that draw it, bottom first.
 *
 * Not-yet recedes — "not yours yet" must not read as strongly as what is done.
 * Partial is the one mark on this surface in two inks: the whole stroke in
 * ink-faint with its first half in ink over it, so it keeps its full length and
 * reads as unfinished rather than as a shorter mark. Each layer is still one
 * drawable under one tint; no drawable carries a colour of its own.
 */
fun strokeLayers(mark: TallyMark): List<StrokeLayer> = when (mark) {
    TallyMark.MET -> listOf(StrokeLayer(StrokePart.WHOLE, WidgetTint.INK))
    TallyMark.PARTIAL -> listOf(
        StrokeLayer(StrokePart.WHOLE, WidgetTint.INK_FAINT),
        StrokeLayer(StrokePart.FIRST_HALF, WidgetTint.INK),
    )
    TallyMark.NOT_YET -> listOf(StrokeLayer(StrokePart.WHOLE, WidgetTint.INK_FAINT))
}

/** The upright stroke, whole or its bottom half. */
fun postDrawable(part: StrokePart): Int = when (part) {
    StrokePart.WHOLE -> R.drawable.widget_tally_post
    StrokePart.FIRST_HALF -> R.drawable.widget_tally_post_half
}

/** The diagonal struck across four posts, whole or its left half. */
fun gateDrawable(part: StrokePart): Int = when (part) {
    StrokePart.WHOLE -> R.drawable.widget_tally_gate
    StrokePart.FIRST_HALF -> R.drawable.widget_tally_gate_half
}

/**
 * The gate layers drawn *before* the posts: the ink-faint ones.
 *
 * A gate that is not yet met must not cut across posts that are, so its faint
 * ink goes underneath them and only ink ([gateLayersOver]) goes on top. An
 * ungated group has no layers either way.
 */
fun gateLayersUnder(gate: TallyMark?): List<StrokeLayer> =
    gate?.let { mark -> strokeLayers(mark).filter { it.tint == WidgetTint.INK_FAINT } }.orEmpty()

/** The gate layers drawn *after* the posts: the ink ones. See [gateLayersUnder]. */
fun gateLayersOver(gate: TallyMark?): List<StrokeLayer> =
    gate?.let { mark -> strokeLayers(mark).filter { it.tint == WidgetTint.INK } }.orEmpty()
