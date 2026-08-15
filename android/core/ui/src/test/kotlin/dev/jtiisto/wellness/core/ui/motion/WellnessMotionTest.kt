package dev.jtiisto.wellness.core.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Reduced motion, pinned.
 *
 * Compose's own `animate*` APIs honour the coroutine context's
 * [MotionDurationScale] for free; the two animations this system hand-rolls —
 * the stat count-up and the dot-row stagger — have to ask for themselves, and
 * "asks correctly" is exactly what is untestable by looking at an emulator with
 * animations on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WellnessMotionTest {

    // ---- count-up -------------------------------------------------------

    @Test
    @DisplayName("scale 0: the count-up emits the final value only, and never suspends")
    fun countUpSnapsUnderReducedMotion() = runTest {
        val emitted = mutableListOf<Int>()
        withContext(FixedMotionScale(0f)) {
            animateCountUp(target = 128) { emitted.add(it) }
        }
        assertEquals(listOf(128), emitted)
        assertEquals(0L, currentTime, "a reduced-motion count-up must not wait for a single frame")
    }

    @Test
    @DisplayName("scale 1: the count-up runs 600ms, climbs, and lands exactly on the target")
    fun countUpRunsFullLength() = runTest {
        val emitted = mutableListOf<Int>()
        withContext(FixedMotionScale(1f)) {
            animateCountUp(target = 100) { emitted.add(it) }
        }
        assertEquals(100, emitted.last())
        assertTrue(emitted.size > 30, "600ms at ~60fps is tens of frames, got ${emitted.size}")
        assertTrue(emitted.zipWithNext().all { (a, b) -> b >= a }, "the count-up must never go backwards")
        assertTrue(
            currentTime >= WellnessMotion.COUNT_UP_MS &&
                currentTime < WellnessMotion.COUNT_UP_MS + 2 * COUNT_UP_FRAME_MS,
            "expected ~${WellnessMotion.COUNT_UP_MS}ms, took ${currentTime}ms",
        )
    }

    @Test
    @DisplayName("half scale halves the count-up")
    fun countUpFollowsTheScale() = runTest {
        withContext(FixedMotionScale(0.5f)) {
            animateCountUp(target = 100) { }
        }
        assertTrue(
            currentTime in 300L..(300L + 2 * COUNT_UP_FRAME_MS),
            "expected ~300ms at half scale, took ${currentTime}ms",
        )
    }

    // ---- dot stagger ----------------------------------------------------

    @Test
    @DisplayName("scale 0: the whole dot row appears at once")
    fun dotStaggerSnapsUnderReducedMotion() = runTest {
        val emitted = mutableListOf<Int>()
        withContext(FixedMotionScale(0f)) {
            animateDotStagger(count = 7) { emitted.add(it) }
        }
        assertEquals(listOf(7), emitted)
        assertEquals(0L, currentTime)
    }

    @Test
    @DisplayName("scale 1: seven dots reveal one at a time, 30ms apart, the first immediately")
    fun dotStaggerWalksTheRow() = runTest {
        val emitted = mutableListOf<Int>()
        withContext(FixedMotionScale(1f)) {
            animateDotStagger(count = 7) { emitted.add(it) }
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), emitted)
        assertEquals(6L * WellnessMotion.DOT_STAGGER_MS, currentTime)
    }

    @Test
    @DisplayName("an empty dot row emits nothing at all")
    fun dotStaggerIgnoresAnEmptyRow() = runTest {
        val emitted = mutableListOf<Int>()
        animateDotStagger(count = 0) { emitted.add(it) }
        assertTrue(emitted.isEmpty())
    }

    // ---- the built-ins --------------------------------------------------

    /**
     * The claim the whole reduced-motion story rests on: a stock Compose
     * animation snaps on its own when the scale is 0, so no `AnimatedVisibility`
     * or `animateFloatAsState` in the app needs a reduced-motion branch.
     */
    @Test
    @DisplayName("scale 0: a built-in animation reaches its target on the first frame")
    fun builtInAnimationsSnap() = runTest {
        val animatable = Animatable(0f)
        withContext(TestFrameClock(this) + FixedMotionScale(0f)) {
            animatable.animateTo(1f, animationSpec = tween(durationMillis = 600))
        }
        assertEquals(1f, animatable.value)
        assertTrue(currentTime <= FRAME_INTERVAL_MS, "snapped in ${currentTime}ms, expected one frame")
    }

    @Test
    @DisplayName("scale 1: the same animation takes its full 600ms")
    fun builtInAnimationsOtherwiseRun() = runTest {
        val animatable = Animatable(0f)
        withContext(TestFrameClock(this) + FixedMotionScale(1f)) {
            animatable.animateTo(1f, animationSpec = tween(durationMillis = 600))
        }
        assertEquals(1f, animatable.value)
        assertTrue(currentTime >= 600L, "ran for only ${currentTime}ms")
    }

    /** [MotionDurationScale] is a plain interface, so the fake spells the property out. */
    private class FixedMotionScale(override val scaleFactor: Float) : MotionDurationScale

    /** A frame clock on virtual time: one frame per [FRAME_INTERVAL_MS] of `delay`. */
    private class TestFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_INTERVAL_MS)
            return onFrame(scope.currentTime * NANOS_PER_MS)
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 16L
        const val NANOS_PER_MS = 1_000_000L
    }
}
