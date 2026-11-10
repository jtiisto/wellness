package dev.jtiisto.wellness.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.jtiisto.wellness.MainActivity
import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.trends.SleepTonightModel
import dev.jtiisto.wellness.core.ui.theme.INK_BANG

/**
 * A torn-off corner of the logbook page: the day's tally and tonight's number,
 * on the same paper, with one hairline between them.
 *
 * **This file lays out; it decides nothing.** Which bucket a size is, whether a
 * tally draws its marks or its sentence, which drawable a judgment takes, which
 * ink a mark is drawn in — every one of those answers arrives from
 * `TodayWidgetLogic`, where it is a plain function a JVM test can pin. The rule
 * is the one the widget's Kover exclusions rest on, and it is the reason this
 * file is allowed to be uncounted: nothing here can be *wrong* in a way a test
 * could have caught.
 *
 * Colour never encodes meaning, exactly as in the app: judgment and completion
 * are carried by shape and fill, and the five inks in `TodayWidgetPalette` are
 * the whole palette. See `specs/widget.md`.
 */

// ---- The type scale, as close to LogbookType as RemoteViews allows ----------

/*
 * Two losses are taken deliberately and are recorded in the spec's §Accepted
 * deviations: the face is the *system's* mono rather than IBM Plex Mono (Glance
 * cannot load a font resource into a RemoteViews tree), and the eyebrow is not
 * tracked out (Glance's TextStyle has no letter-spacing). Everything on this
 * surface is a number or the label of a number, which is what makes a generic
 * mono an acceptable stand-in and a proportional face not one.
 */

/** `needText` and the bang — StatTile's 24sp mono Medium, unchanged. */
private val WIDGET_HEADLINE = TextStyle(
    color = WIDGET_INK,
    fontSize = 24.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = FontFamily.Monospace,
)

/**
 * PAGE's headline: one step up. The tallest bucket was setting the card's own
 * 24sp in a cell three sizes larger and reading as a note in a margin; the
 * number is the widget's reason to exist, so at PAGE it takes the room it has.
 */
private val WIDGET_HEADLINE_PAGE = WIDGET_HEADLINE.copy(fontSize = 32.sp)

/** The pending floor's `-:--`: the same headline, receded to ink-faint. */
private val WIDGET_HEADLINE_PENDING = WIDGET_HEADLINE.copy(color = WIDGET_INK_FAINT)

private val WIDGET_HEADLINE_PAGE_PENDING = WIDGET_HEADLINE_PAGE.copy(color = WIDGET_INK_FAINT)

/** The debt line, the one meta line that is the answer rather than a caveat. */
private val WIDGET_META_INK = TextStyle(
    color = WIDGET_INK,
    fontSize = 11.5.sp,
    fontFamily = FontFamily.Monospace,
)

/** The strip's pending `-:--`: the same meta line, receded to ink-faint. */
private val WIDGET_META_FAINT = WIDGET_META_INK.copy(color = WIDGET_INK_FAINT)

/** The `h:mm` unit, strain, freshness — context beside the number. */
private val WIDGET_META_SOFT = TextStyle(
    color = WIDGET_INK_SOFT,
    fontSize = 11.5.sp,
    fontFamily = FontFamily.Monospace,
)

/** Labels and the cached badge: caps, smallest, softest. */
private val WIDGET_EYEBROW = TextStyle(
    color = WIDGET_INK_SOFT,
    fontSize = 10.5.sp,
    fontFamily = FontFamily.Monospace,
)

// ---- The page ---------------------------------------------------------------

/**
 * The whole widget.
 *
 * One tap target over everything — a launcher surface that is partly tappable
 * teaches the user to aim, and there is only one destination anyway.
 *
 * The two elements are independent: a null [rollup] omits the tally *with its
 * separator rule*, and a null [model] draws the sleep block's pending floor
 * rather than nothing, because "no data yet" is a state and a blank rectangle is
 * a bug. STRIP holds one idea and that idea is the tally; the single case where
 * it would hold none — no trackers configured — gives the line to the next
 * element in the compact form the floor can hold ([showsCompactSleep]).
 *
 * The user's font scale is read here and handed to the fit rule: the counts are
 * sized in sp and the marks in dp, so the row's arithmetic is only true if it
 * knows how much the words grew.
 */
@Composable
fun TodayWidgetContent(rollup: CategoryRollup?, model: SleepTonightModel?) {
    val size = LocalSize.current
    val bucket = widgetBucket(size)
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val tally = tallyLayout(rollup, bucket, tallyFitWidthDp(bucket, size.width.value), fontScale)
    val sleep = showsSleep(bucket)
    // A page's worth of air: the rule earns wider margins where there is height
    // to spend, and the headline steps up with it (WIDGET_HEADLINE_PAGE).
    val page = bucket == WidgetBucket.PAGE
    val blockGap = if (page) 10.dp else 6.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_PAPER)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        // Centred at every bucket. Launcher cells are taller than the bucket
        // floors, and a column pinned to the top leaves all of the surplus as
        // a void under the last line — the first thing the space round's
        // report named.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bucket == WidgetBucket.WIDE && tally != null && rollup != null) {
            // Two ideas side by side: the tally in its own column, a vertical
            // rule, and the compact sleep line. The gutters are explicit
            // spacers — five children, well inside the ten-child budget — so
            // the rule's background cannot bleed into padding it would own.
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(GlanceModifier.width(wideTallyWidthDp(size.width.value).dp)) {
                    TallyRow(tally, tallyContentDescription(rollup))
                }
                Spacer(GlanceModifier.width(8.dp))
                Spacer(GlanceModifier.width(1.dp).fillMaxHeight().background(WIDGET_RULE))
                Spacer(GlanceModifier.width(8.dp))
                CompactSleepRow(model)
            }
        } else if (bucket == WidgetBucket.WIDE) {
            // WIDE with no tally: the rule would separate a column from
            // nothing, so the sleep line takes the row alone.
            CompactSleepRow(model)
        } else {
            if (tally != null && rollup != null) {
                TallyRow(tally, tallyContentDescription(rollup))
            }

            // The rule exists to separate two things; with one of them absent
            // it would be a line under a heading that isn't there.
            if (tally != null && sleep) {
                Spacer(GlanceModifier.height(blockGap))
                Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(WIDGET_RULE))
                Spacer(GlanceModifier.height(blockGap))
            }

            if (sleep) {
                SleepBlock(
                    model = model,
                    showStrain = showsStrain(bucket),
                    showHonesty = showsHonestyLines(bucket),
                    headline = if (page) WIDGET_HEADLINE_PAGE else WIDGET_HEADLINE,
                    headlinePending = if (page) WIDGET_HEADLINE_PAGE_PENDING else WIDGET_HEADLINE_PENDING,
                )
            } else if (showsCompactSleep(bucket, hasTally = tally != null)) {
                CompactSleepRow(model)
            }
        }
    }
}

// ---- Element 1: the tracker tally -------------------------------------------

/**
 * Tally marks on the keyline, the count pushed to the far edge — one line, read
 * left to right the way a tally fills.
 *
 * The row speaks [spoken] as a single node whatever the fit rule drew: the marks
 * are decorative (they are the count, drawn), and a screen reader announcing
 * twelve anonymous images would be worse than silence.
 */
@Composable
private fun TallyRow(layout: TallyLayout, spoken: String) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One child per GROUP, never per mark: a Glance container renders AT
        // MOST TEN children and silently drops the rest. The first device build
        // spent that whole budget on five dots plus their five spacers, and
        // every mark after the fifth vanished. The limit is a term in the fit
        // ladder (tallyLayout), which is why this loop can trust its list.
        layout.groups?.forEachIndexed { index, group ->
            TallyGroupBox(group, startDp = tallyGroupStartDp(index, group))
        }
        // Only when both halves drew: with one of them the row starts on the
        // keyline and ends where it ends.
        if (layout.groups != null && layout.text != null) {
            Spacer(GlanceModifier.defaultWeight())
        }
        layout.text?.let { Text(text = it, style = WIDGET_EYEBROW, maxLines = 1) }
    }
}

/**
 * Up to five marks: the posts in a row, and the gate struck across them.
 *
 * Three children at most, in z-order: the gate's ink-faint layers, the posts,
 * the gate's ink layers — so a gate that is not yet met never cuts a post that
 * is. The gap in front of the group is start-padding on this box, never a
 * `Spacer` child of the row: padding sits inside a view's bounds, so the box is
 * sized gap + group to keep the group's own canvas.
 */
@Composable
private fun TallyGroupBox(group: TallyGroup, startDp: Float) {
    val widthDp = tallyGroupWidthDp(group)
    Box(
        modifier = GlanceModifier
            .size(width = (startDp + widthDp).dp, height = TALLY_MARK_HEIGHT_DP.dp)
            .padding(start = startDp.dp),
    ) {
        gateLayersUnder(group.gate).forEach { layer ->
            TallyStroke(gateDrawable(layer.part), layer.tint, widthDp)
        }
        Row {
            group.posts.forEachIndexed { index, post ->
                TallyPost(post, startDp = tallyPostStartDp(index, group.gated))
            }
        }
        gateLayersOver(group.gate).forEach { layer ->
            TallyStroke(gateDrawable(layer.part), layer.tint, widthDp)
        }
    }
}

/**
 * One upright stroke, as the one or two layers its state is drawn in. The gap
 * in front of it is start-padding, for the reason [TallyGroupBox] gives.
 */
@Composable
private fun TallyPost(mark: TallyMark, startDp: Float) {
    Box(
        modifier = GlanceModifier
            .size(width = (startDp + TALLY_POST_DP).dp, height = TALLY_MARK_HEIGHT_DP.dp)
            .padding(start = startDp.dp),
    ) {
        strokeLayers(mark).forEach { layer ->
            TallyStroke(postDrawable(layer.part), layer.tint, TALLY_POST_DP)
        }
    }
}

/** One layer of a tally stroke: a fill-only drawable, tinted here and nowhere else. */
@Composable
private fun TallyStroke(drawable: Int, tint: WidgetTint, widthDp: Float) {
    Image(
        provider = ImageProvider(drawable),
        contentDescription = null,
        modifier = GlanceModifier.size(width = widthDp.dp, height = TALLY_MARK_HEIGHT_DP.dp),
        colorFilter = ColorFilter.tint(widgetTintColor(tint)),
    )
}

// ---- Element 2 (+3): tonight's sleep -----------------------------------------

/**
 * The tonight card, line for line with `SleepTonightCard`, minus whatever this
 * bucket cannot hold.
 *
 * A null [model] is the **pending floor**, never an error: a hollow ink-faint
 * glyph, `-:--`, and "no data yet". The `h:mm` unit is dropped there on purpose
 * — it labels a number, and there is no number to label.
 *
 * The block is one spoken node carrying [sleepSpoken] — the card's own sentence,
 * whole, including the lines this bucket does not draw.
 */
@Composable
private fun SleepBlock(
    model: SleepTonightModel?,
    showStrain: Boolean,
    showHonesty: Boolean,
    headline: TextStyle,
    headlinePending: TextStyle,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .semantics { contentDescription = sleepSpoken(model) },
    ) {
        // Rhythm is top-padding on each line, not Spacer children — the same
        // ten-child container budget the tally row ran into: at PAGE with both
        // honesty lines, line-plus-spacer children counted eleven and the
        // cached badge would have been the one silently dropped.
        SleepEyebrow(model)

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = GlanceModifier.padding(top = 2.dp),
        ) {
            if (model?.flagged == true) {
                Text(
                    text = INK_BANG,
                    style = headline,
                    modifier = GlanceModifier.padding(end = 4.dp),
                )
            }
            if (model == null) {
                Text(text = PENDING_NEED, style = headlinePending)
            } else {
                Text(text = model.needText, style = headline)
                Text(
                    text = "h:mm",
                    style = WIDGET_META_SOFT,
                    modifier = GlanceModifier.padding(start = 3.dp),
                )
            }
        }
        if (model == null) {
            Text(
                text = "no data yet",
                style = WIDGET_META_SOFT,
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        } else {
            // Two lines because a gap night's `· reset — missing night` suffix
            // needs them at PAGE, and truncating the reason would leave the
            // bang unexplained.
            Text(
                text = model.debtLine,
                style = WIDGET_META_INK,
                maxLines = 2,
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        }

        if (model != null && showStrain) {
            Text(
                text = model.strainLine,
                style = WIDGET_META_SOFT,
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        }
        if (model != null && showHonesty) {
            model.freshnessLine?.let {
                Text(
                    text = it,
                    style = WIDGET_META_SOFT,
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
            model.cachedLine?.let {
                Text(
                    text = it.uppercase(),
                    style = WIDGET_EYEBROW,
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The strip's whole page when the day expects no habits: the judgment mark and
 * the number it judges, nothing else.
 *
 * The eyebrow is dropped rather than shrunk — `TONIGHT'S SLEEP NEED` measures
 * about 126dp against the floor's 86dp of content and would wrap into the row
 * below — and the unit goes with it, because `h:mm` labels a number that is
 * already the only thing on the line. The mark carries what the label would have
 * said, which is what the mark vocabulary is for. It speaks the full card
 * sentence all the same: what a strip can draw was never a claim about what it
 * knows.
 */
@Composable
private fun CompactSleepRow(model: SleepTonightModel?) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .semantics { contentDescription = sleepSpoken(model) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(judgmentDrawable(model)),
            contentDescription = null,
            modifier = GlanceModifier.size(9.dp),
            colorFilter = ColorFilter.tint(widgetTintColor(judgmentTint(model))),
        )
        Spacer(GlanceModifier.width(6.dp))
        if (model == null) {
            Text(text = PENDING_NEED, style = WIDGET_META_FAINT, maxLines = 1)
        } else {
            Text(text = model.needText, style = WIDGET_META_INK, maxLines = 1)
        }
    }
}

/** The judgment mark and the label it stands beside. */
@Composable
private fun SleepEyebrow(model: SleepTonightModel?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(judgmentDrawable(model)),
            contentDescription = null,
            modifier = GlanceModifier.size(9.dp),
            colorFilter = ColorFilter.tint(widgetTintColor(judgmentTint(model))),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(text = "TONIGHT'S SLEEP NEED", style = WIDGET_EYEBROW, maxLines = 1)
    }
}

/** The headline with no number behind it yet — dashes in the number's shape. */
private const val PENDING_NEED = "-:--"
