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

/** Tally · rule · the sleep block. The default 3×2 placement. */
val CARD = DpSize(180.dp, 110.dp)

/** Everything, card-faithful: strain and the honesty lines join. */
val PAGE = DpSize(180.dp, 170.dp)

/** Which of the three compositions a given size gets. */
enum class WidgetBucket { STRIP, CARD, PAGE }

/**
 * The bucket for a size handed down by `SizeMode.Responsive`.
 *
 * Both dimensions have to clear the floor: the sleep block's headline needs the
 * 180dp width whatever the height is, so a tall narrow widget stays a STRIP
 * rather than rendering a truncated number. Nothing is ever shrunk to fit — a
 * size that cannot hold an element omits it.
 */
fun widgetBucket(size: DpSize): WidgetBucket = when {
    size.height >= PAGE.height && size.width >= PAGE.width -> WidgetBucket.PAGE
    size.height >= CARD.height && size.width >= CARD.width -> WidgetBucket.CARD
    else -> WidgetBucket.STRIP
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
fun showsCompactSleep(bucket: WidgetBucket, hasTally: Boolean): Boolean =
    !showsSleep(bucket) && !hasTally

// ---- The tracker tally ------------------------------------------------------

/** One mark in the tally row. */
enum class TallyDot { FILLED, HALF, OPEN }

/**
 * What the tally row draws after the fit rule has spoken: dots, a count, or
 * both. A null half is a part that is *absent*, never an empty one — and both
 * halves are never null at once, because a tally that exists always renders as
 * something.
 *
 * [text] is whichever count form survived the ladder: the sentence `5 OF 8 DONE`
 * where it fits, the compact `5/8` where it does not.
 */
data class TallyLayout(val dots: List<TallyDot>?, val text: String?)

/**
 * The tally, laid out for [bucket] at [bucketWidthDp] under [fontScale].
 *
 * The element owns no judgment of its own: which trackers were met, partial or
 * not yet is entirely `categoryRollup`'s answer, and this arranges it. Dots read
 * left to right the way tallies fill a line — met, then partial, then not yet.
 *
 * Null when there is nothing to say: no rollup, or a rollup holding no habits.
 * Avoidances and observations get no mark here (the app's ring gives them their
 * own), so a day of nothing but those is a day this element has no line about,
 * and it is omitted with its separator rule rather than drawn empty.
 *
 * **The ladder, in order:** dots + count, then dots alone, then the sentence
 * alone, then `N/M`. Each step is a whole layout — nothing is ever shrunk or
 * clipped to make the step above fit, because a silently shortened row of dots
 * is a confident wrong answer about the day. The last step is the **floor of the
 * never-truncate rule rather than an exception to it**: `N/M` is the shortest
 * true statement of a tally that exists, so it is drawn even where the estimate
 * says it will clip — there is nothing left to fall back to.
 *
 * **The arithmetic is an approximation and says so.** Glance measures on the
 * device and we cannot, so a count's width is taken as [EYEBROW_ADVANCE_DP] per
 * character — 10.5sp of monospace at the ≈0.6em advance a mono face gives —
 * times [fontScale], because the counts are sized in **sp** and the dots in
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

    val dots = buildList {
        repeat(rollup.habitsMet) { add(TallyDot.FILLED) }
        repeat(rollup.habitsPartial) { add(TallyDot.HALF) }
        repeat(rollup.habitsNotYet) { add(TallyDot.OPEN) }
    }
    val text = tallyCountText(rollup)
    val content = bucketWidthDp - 2 * WIDGET_PADDING_DP

    // The strip holds one idea, and the width it was given does not change which
    // one: a 2×1 dragged wider is still a strip. [STRIP_MAX_DOTS] is the general
    // arithmetic below evaluated at 110dp, frozen as the rule so the answer
    // cannot drift with a launcher's grid — dots are dp-sized, so no font scale
    // moves this threshold either.
    if (bucket == WidgetBucket.STRIP) {
        return if (dots.size <= STRIP_MAX_DOTS) {
            TallyLayout(dots = dots, text = null)
        } else {
            countOnly(rollup, text, content, fontScale)
        }
    }

    val dotsWidth = dotsWidthDp(dots.size)
    val textWidth = textWidthDp(text, fontScale)
    return when {
        dotsWidth + TALLY_SEPARATION_DP + textWidth <= content ->
            TallyLayout(dots = dots, text = text)
        dotsWidth <= content -> TallyLayout(dots = dots, text = null)
        // The count is what is left when the marks will not fit: it states the
        // same fact in fewer characters, and it never lies by omission.
        else -> countOnly(rollup, text, content, fontScale)
    }
}

/** The last two rungs: the sentence where it fits, `N/M` where nothing else does. */
private fun countOnly(
    rollup: CategoryRollup,
    text: String,
    contentDp: Float,
    fontScale: Float,
): TallyLayout = if (textWidthDp(text, fontScale) <= contentDp) {
    TallyLayout(dots = null, text = text)
} else {
    TallyLayout(dots = null, text = tallyCompactText(rollup))
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
 * states the partial count. Here the dots are only ever positive trackers, and
 * the half-dots are a fact nobody can hear otherwise — so this names both. If
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

/** 12dp of padding on each side; the content is what is left between them. */
private const val WIDGET_PADDING_DP = 12f

private const val TALLY_DOT_DP = 8f
private const val TALLY_DOT_GAP_DP = 4f

/** The gap that keeps the dots and the count from reading as one string. */
private const val TALLY_SEPARATION_DP = 8f

/** 10.5sp monospace ≈ 0.6em per character. An estimate, and only ever a choice. */
private const val EYEBROW_ADVANCE_DP = 6.3f

/** `12n − 4`: what STRIP's 86dp of content holds, and the rule it became. */
private const val STRIP_MAX_DOTS = 7

private fun dotsWidthDp(count: Int): Float =
    TALLY_DOT_DP * count + TALLY_DOT_GAP_DP * (count - 1)

/** Text is sized in sp, so the user's font scale is part of its width. */
private fun textWidthDp(text: String, fontScale: Float): Float =
    text.length * EYEBROW_ADVANCE_DP * fontScale

// ---- Marks: drawable and ink ------------------------------------------------

/**
 * The judgment glyph, as one of the three mark drawables.
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

/** One tally dot, from the same three drawables the judgment glyph uses. */
fun dotDrawable(dot: TallyDot): Int = when (dot) {
    TallyDot.FILLED -> R.drawable.widget_mark_filled
    TallyDot.HALF -> R.drawable.widget_mark_half
    TallyDot.OPEN -> R.drawable.widget_mark_hollow
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

/** Not-yet recedes: "not yours yet" must not read as strongly as what is done. */
fun dotTint(dot: TallyDot): WidgetTint =
    if (dot == TallyDot.OPEN) WidgetTint.INK_FAINT else WidgetTint.INK
