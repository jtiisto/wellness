package dev.jtiisto.wellness.feature.coach.guidance

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import kotlinx.coroutines.delay

/**
 * The cardio guide, over the day.
 *
 * A full-canvas sheet of paper mounted from `CoachContent` rather than a
 * destination: the app has five tab routes and no non-tab destination at all
 * (`DestinationScreen` errors on anything else, by design), and a guide the
 * rider opens for a few minutes and dismisses is not the thing to make the first
 * one with. The day stays composed underneath, which is what lets a dismiss
 * return to the same scroll position rather than to the top of the workout.
 *
 * ## What this file owns, and what it does not
 *
 * The shell and the lifecycle: the eyebrow, the title, the way out, the 1 Hz
 * clock, the capture gate, and the footer — its elapsed line, its `START` and
 * its `+ 5 MIN`. The instrument itself is [GuidanceInstrument]'s: the header
 * grammar's three lines, the scrolling window and the session strip, all of
 * them behind the capture gate because all of them are readings of a stream.
 *
 * The extension deliberately sits on the *shell* side of that gate, with the
 * elapsed line it belongs beside. Appending five minutes is a statement about
 * the timeline, which runs whether or not a strap is delivering — a rider whose
 * belt slipped mid-ride can still say "make it five more", and the notice above
 * says as much in so many words.
 *
 * ## The motion contract
 *
 * The clock is a `LaunchedEffect` keyed on this composition and nothing else, so
 * it exists exactly while the overlay does. That is the whole rule: a live trace
 * is a permanent frame clock standing next to a wake lock, and the design
 * system's own `SyncStatusDot` already records what an animation left composed
 * while nothing is happening costs. Dismissing the overlay ends the coroutine
 * with the composition; the *run* survives in the ViewModel, because a clock and
 * a timeline are different things.
 */
@Composable
@Suppress("LongParameterList")
internal fun GuidanceOverlay(
    state: GuidanceOverlayState,
    samples: List<TraceSample>,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier,
    // The same kind of seam the ViewModel's injected clock is: the tick and
    // the anchor must read the same idea of now, or injected test time and
    // displayed progress could disagree.
    now: () -> Long = System::currentTimeMillis,
) {
    val palette = LogbookTheme.palette

    // The gesture and the ✕ are one act with two spellings; back must not fall
    // through to the nav host and switch tabs out from under a running guide.
    BackHandler(onBack = onDismiss)
    KeepScreenOn()

    val nowMs = guidanceTick(now)
    val status = guidanceStatus(state.timeline, state.run, nowMs)
    // Fixed for the whole session, and fixed *here* — one domain held above the
    // tick is what makes "no per-tick rescaling" structural rather than a
    // promise the painter has to keep. The extension cannot move it either: it
    // changes when a segment ends, never what the segment asks for.
    val domain = remember(state.timeline) { bpmDomain(state.timeline) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.paper)
            // Paper hides the day; this is what stops a finger going through it.
            // A background alone does not take part in hit testing, so without
            // this a tap in the overlay's empty middle would toggle an accordion
            // on a screen the user cannot see. The Main pass runs child-first,
            // so the overlay's own controls are served before this eats the rest.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(horizontal = OVERLAY_PADDING),
    ) {
        GuideHeader(state = state, onDismiss = onDismiss)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // The visibility rule, once: nothing is arriving, so there is
            // nothing to draw an instrument out of. The overlay stays — the
            // timeline in the footer is still running, and a strap that comes
            // back puts the instrument back with it.
            if (state.capture == null) {
                InkNotice(
                    text = GuidanceOverlayCopy.CAPTURE_IDLE,
                    modifier = Modifier.padding(top = LogbookSpace.group),
                )
            } else {
                GuidanceInstrument(
                    capture = state.capture,
                    status = status,
                    samples = samples,
                    domain = domain,
                    nowMs = nowMs,
                )
            }
        }

        GuideFooter(status = status, onStart = onStart, onExtend = onExtend)
    }
}

/** The eyebrow and its ✕, then the exercise's name at display size. */
@Composable
private fun GuideHeader(state: GuidanceOverlayState, onDismiss: () -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.eyebrow.drawn.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = state.eyebrow.spoken },
        )
        Box(
            modifier = Modifier
                .size(LogbookSpace.touchTarget)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = GuidanceOverlayCopy.DISMISS },
            contentAlignment = Alignment.Center,
        ) {
            // The mockup's glyph, typed rather than iconised: it sits in a mono
            // line of the same size, and Material's Close mark next to an
            // eyebrow reads as a chrome control on a page that has none.
            Text(
                text = DISMISS_GLYPH,
                style = LogbookTheme.type.section,
                color = palette.ink,
            )
        }
    }

    Text(
        text = state.title.uppercase(),
        style = LogbookTheme.type.display,
        color = palette.ink,
        modifier = Modifier.padding(bottom = LogbookSpace.grid * 2),
    )
}

/**
 * `Elapsed 16:18 · of 25:00`, and the two ways a run is steered.
 *
 * The elapsed line is the one number the shell owns: it is a statement about the
 * *timeline*, which exists whether or not a strap is connected, so it keeps
 * ticking through the capture-idle notice above it.
 *
 * The controls share the End edge, and only in [GuidancePhase.DONE] are there
 * two of them — a finished steady ride can be ridden on (`+ 5 MIN`) or ridden
 * again (`START AGAIN`). The filled button keeps the outer edge there, because
 * a primary call to action that a hollow one had displaced would read as the
 * lesser of the two; through the whole of a running ride `+ 5 MIN` stands alone
 * in that slot, which is the arrangement the spec describes.
 */
@Composable
private fun GuideFooter(status: GuidanceStatus, onStart: () -> Unit, onExtend: () -> Unit) {
    val palette = LogbookTheme.palette
    val elapsed = elapsedFooter(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget)
            .padding(vertical = LogbookSpace.grid * 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        Text(
            text = elapsed.drawn,
            style = LogbookTheme.type.meta,
            color = palette.inkSoft,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = elapsed.spoken },
        )
        if (canOfferExtension(status)) {
            InkOutlineButton(label = GuidanceOverlayCopy.EXTEND, onClick = onExtend)
        }
        startButtonLabel(status.phase)?.let { label ->
            InkButton(label = label, onClick = onStart)
        }
    }
}

/**
 * The screen stays awake exactly while the guide is composed.
 *
 * This is the amendment: the manifest's recorded rationale for having no
 * `keepScreenOn` anywhere was that the app "shows a number, not a live
 * tachogram", and a live tachogram is precisely what this overlay is. The
 * *capture* keeps its own posture — a `PARTIAL_WAKE_LOCK` and nothing more, so
 * recording with the screen off stays first-class. Only looking at the
 * instrument holds the display, and only for as long as it is on screen:
 * dismiss, a tab switch and process teardown all dispose this and clear the
 * flag.
 *
 * The flag rides the Activity's window rather than a view property because the
 * window is what owns it — and clearing it on dispose is unconditional, so there
 * is no path on which the guide leaves the screen holding it.
 */
@Composable
private fun KeepScreenOn() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * The instant the whole overlay reads, advanced once a second.
 *
 * One clock for the guide, not one per number: every value on screen is derived
 * from this instant through [guidanceStatus], so nothing can tick a beat apart
 * from anything else. A paper instrument steps — the window scrolls a second at
 * a time rather than gliding — and this is where that is decided, which is why
 * the painters downstream need no animation of their own.
 *
 * `LaunchedEffect(Unit)`: the effect's lifetime is the composition's, which is
 * the rule the motion contract states. The `ElapsedClock` precedent, at the
 * cadence this display reads at.
 */
@Composable
private fun guidanceTick(now: () -> Long): Long {
    var nowMs by remember { mutableLongStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            nowMs = now()
        }
    }
    return nowMs
}

/** One second, the whole motion contract's step size. */
private const val TICK_MS = 1_000L

/** U+2715 — the mockup's dismiss mark. */
private const val DISMISS_GLYPH = "✕"

/** The page margin, matching the day view's own. */
private val OVERLAY_PADDING = 20.dp
