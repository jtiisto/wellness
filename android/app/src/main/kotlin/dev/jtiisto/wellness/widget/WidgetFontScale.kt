package dev.jtiisto.wellness.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The system font scale the widget lays out for, held as state the composition
 * collects.
 *
 * **The font scale is state, not a read** (`specs/widget.md` §Refresh model).
 * The bucket, the headline step and the tally's rung are all chosen from it, and
 * a value read from the render context at draw time is frozen until something
 * else recomposes — nothing else does — so after a font-scale change the old
 * layout choice was drawn with bigger text. As a [StateFlow], an [offer] that
 * changes it recomposes every live session that collects [state]; the
 * configuration callback in [WidgetBackgroundRefresh] pairs it with an
 * `updateAll` to wake the sessions the launcher let die.
 *
 * Plain Kotlin with no Android types, so it is counted and JVM-tested: the
 * process-wide instance is [process], and a test builds its own.
 *
 * @param initial the scale before anything has offered one — the platform's
 *   default, which the first render or the refresh hook's start replaces. An
 *   unusable value (zero, negative, not finite; see [isUsableFontScale]) is
 *   replaced by [DEFAULT_FONT_SCALE], so the holder never exposes a scale
 *   [offer] would refuse.
 */
class WidgetFontScale(initial: Float = DEFAULT_FONT_SCALE) {

    private val current = MutableStateFlow(if (isUsableFontScale(initial)) initial else DEFAULT_FONT_SCALE)

    /** The scale to lay out for; collected inside `provideContent`. */
    val state: StateFlow<Float> = current.asStateFlow()

    /**
     * Records [fontScale] and answers whether it differs from the held one
     * ([fontScaleChanged]); only a change is written. An unusable scale (zero,
     * negative, not finite) is no change by that rule, so it is neither written
     * nor reported. Safe from any thread: a racing offer of another value is
     * retried against it rather than lost.
     */
    fun offer(fontScale: Float): Boolean {
        while (true) {
            val held = current.value
            if (!fontScaleChanged(held, fontScale)) return false
            if (current.compareAndSet(held, fontScale)) return true
        }
    }

    companion object {
        /** The system's default font scale. */
        const val DEFAULT_FONT_SCALE = 1f

        /** The one holder this process's widget sessions and refresh hook share. */
        val process = WidgetFontScale()
    }
}
