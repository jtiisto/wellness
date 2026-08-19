package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The only colour Logbook spends on something that is not a plate.
 *
 * Logbook owns no semantic palette — a failed hook is an ink glyph and a mono
 * word, never a red one — and that rule holds for everything the page *states*.
 * It cannot hold for the two things the page *measures*: whether the heart-rate
 * strap is still sending, and whether what is on the phone has reached the
 * server. Those are instrument readings rather than judgements, they change
 * while you watch them, and an ink dot that means four different things
 * depending on how full it is would be reading a gauge by its shape.
 *
 * So this is the documented exception, and being a separate file is the point:
 * four colours, two consumers ([dev.jtiisto.wellness.core.ui.SyncStatusDot] and
 * the HR tone dot), and nothing else in the system may reach for them. They came
 * across from Graphite Signal's semantic tokens unchanged when that system
 * retired — measured against Logbook paper rather than inherited on faith, and
 * pinned by `LiveSignalColorsTest`.
 *
 * Colour is never the *only* channel: both consumers spell their state into the
 * content description, so the dot is a faster read of something that is also
 * written down, not the sole carrier of it.
 */
@Immutable
data class LiveSignalColors(
    /** Connected and sending; everything local is on the server. */
    val live: Color,
    /** Establishing a reading — scanning, connecting, reconnecting. */
    val waiting: Color,
    /** The reading is gone, or the phone is still holding changes. */
    val attention: Color,
    /** Nothing to report: no strap, no known sync state. Not an error. */
    val idle: Color,
)

/** Grey-500 in both modes: an idle dot should not read as a state at all. */
private val Idle = Color(0xFF6B7280)

val LiveSignalDark = LiveSignalColors(
    live = Color(0xFF4ADE80),
    waiting = Color(0xFFFBBF24),
    // Red-400 rather than red-500: #EF4444 was the one token that missed its
    // floor on a dark ground, and it is kept at 400 here for the same reason.
    attention = Color(0xFFF87171),
    idle = Idle,
)

val LiveSignalLight = LiveSignalColors(
    // A tier darker than the first draft — these are dark inks on white paper,
    // not bright fills, so they hold their own against a light page.
    live = Color(0xFF166534),
    waiting = Color(0xFF854D0E),
    attention = Color(0xFFB91C1C),
    idle = Idle,
)

/**
 * The pair for the page in force.
 *
 * Resolved from the Logbook palette's own mode rather than from
 * `isSystemInDarkTheme()`: the theme is what decides which paper is under the
 * dot, and a theme told to be dark on a light device would otherwise get a
 * light-mode dot on dark paper.
 */
@Composable
@ReadOnlyComposable
fun liveSignalColors(): LiveSignalColors =
    if (LogbookTheme.palette.isDark) LiveSignalDark else LiveSignalLight
