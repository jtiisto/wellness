package dev.jtiisto.wellness.core.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The motion tokens.
 *
 * Four specs cover everything: a gentle spring for anything that changes size,
 * and three tweens for anything that changes appearance. Durations are short
 * enough that the app never feels like it is performing.
 */
object WellnessMotion {

    /**
     * Expansion and layout.
     *
     * Stiffness 200 with damping .85 settles in about a third of a second and
     * overshoots by under a percent — present, but not bouncy.
     *
     * The specs are functions rather than values because the same token has to
     * animate a Float, a Color and an IntSize, and an `AnimationSpec` is typed.
     */
    fun <T> springGentle(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 200f)

    private val springGentleSize: SpringSpec<IntSize> = spring(
        dampingRatio = 0.85f,
        stiffness = 200f,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )

    /** The default state change: selection, colour, tone. */
    fun <T> standard(): TweenSpec<T> = tween(durationMillis = 250, easing = FastOutSlowInEasing)

    /** Small, immediate feedback — a chip filling, a border appearing. */
    fun <T> fast(): TweenSpec<T> = tween(durationMillis = 150, easing = FastOutSlowInEasing)

    /** A path drawing itself in. */
    fun <T> draw(): TweenSpec<T> = tween(durationMillis = 500, easing = LinearOutSlowInEasing)

    /** Accordion and category expansion. */
    val expandEnter: EnterTransition =
        expandVertically(animationSpec = springGentleSize) + fadeIn(animationSpec = fast())

    val expandExit: ExitTransition =
        shrinkVertically(animationSpec = springGentleSize) + fadeOut(animationSpec = tween(100))

    /** Fade-through for a layer arriving over another (tab content). */
    val enterFadeThrough: EnterTransition =
        fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.95f, animationSpec = tween(200, easing = FastOutSlowInEasing))

    val exitFadeThrough: ExitTransition = fadeOut(animationSpec = tween(90))

    /** A popover growing out of the corner it is anchored to. */
    val popoverEnter: EnterTransition =
        fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0f, 0f),
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            )

    /** Stat tiles count up once per screen entry. */
    const val COUNT_UP_MS = 600

    /** Dot rows reveal left to right, on first composition only. */
    const val DOT_STAGGER_MS = 30
}

/**
 * The animator scale the system is asking for, from the coroutine context.
 *
 * Compose plumbs `MotionDurationScale` through the recomposer, and every
 * `animate*` API already honours it — a hand-rolled animation has to ask. The
 * platform setting is never read directly: `Settings.Global` would miss the
 * per-window and test overrides that this element exists to carry.
 */
suspend fun motionScale(): Float =
    currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f

/**
 * Count from zero to [target].
 *
 * At scale 0 the final value is the only value emitted, on the first
 * invocation, with no suspension at all — a reduced-motion user gets the number
 * rather than a shorter animation of it.
 */
suspend fun animateCountUp(
    target: Int,
    durationMillis: Int = WellnessMotion.COUNT_UP_MS,
    frameMillis: Int = COUNT_UP_FRAME_MS,
    emit: (Int) -> Unit,
) {
    val total = (durationMillis * motionScale()).roundToInt()
    if (total <= 0 || target == 0) {
        emit(target)
        return
    }
    var elapsed = 0
    while (elapsed < total) {
        delay(frameMillis.toLong())
        elapsed += frameMillis
        val fraction = (elapsed.toFloat() / total).coerceAtMost(1f)
        emit((LinearOutSlowInEasing.transform(fraction) * target).roundToInt())
    }
    emit(target)
}

/**
 * Reveal [count] dots left to right, [perDotMillis] apart.
 *
 * Emits the number of dots that should be visible. The first is immediate, so a
 * seven-dot row costs six gaps rather than seven.
 */
suspend fun animateDotStagger(
    count: Int,
    perDotMillis: Int = WellnessMotion.DOT_STAGGER_MS,
    emit: (Int) -> Unit,
) {
    if (count <= 0) return
    val step = (perDotMillis * motionScale()).roundToLong()
    if (step <= 0L) {
        emit(count)
        return
    }
    for (index in 1..count) {
        if (index > 1) delay(step)
        emit(index)
    }
}

/** The count-up as a value to draw. Runs once per [target] change. */
@Composable
fun rememberCountUp(target: Int): Int {
    var value by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { animateCountUp(target) { value = it } }
    return value
}

/**
 * How many of [count] dots are revealed.
 *
 * Staggers once, not on every recomposition — the row is history, and history
 * re-dealing itself each time the value above it changes would be noise.
 */
@Composable
fun rememberDotReveal(count: Int): Int {
    var visible by remember { mutableIntStateOf(0) }
    LaunchedEffect(count) { animateDotStagger(count) { visible = it } }
    return visible.coerceAtMost(count)
}

/** ~60fps. Exposed so a test can pin the frame count instead of guessing it. */
const val COUNT_UP_FRAME_MS = 16
