package dev.jtiisto.wellness.widget

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Re-renders the widget the moment the app goes away, and when the system font
 * scale changes.
 *
 * The tally is read from local Room at render time, so it is only ever as
 * current as the last render — and the moment it is most likely to be *wrong*
 * is the one right after the user ticks a tracker in Journal and swipes the app
 * away. [TodayWidgetWorker]'s hourly tick would eventually catch it; an hour of
 * a home screen contradicting the app it came from is not the answer. This is,
 * and it costs one local read: **no fetch happens here**, and nothing about this
 * hook depends on a server having resolved.
 *
 * `ON_STOP` rather than `ON_PAUSE` because `ProcessLifecycleOwner` debounces it
 * across configuration changes and Activity handoffs — a rotation must not
 * schedule a render.
 *
 * **The font scale.** The bucket, the headline step and the tally's rung are all
 * chosen from it, and a configuration change does not recompose a Glance
 * session. So a `ComponentCallbacks` on the application offers each new
 * configuration's scale to [WidgetFontScale.process] — which recomposes the live
 * sessions that collect it — and, only when that reports a change, re-renders
 * every widget the same way `ON_STOP` does. Whether it changed is the holder's
 * pure decision ([fontScaleChanged]); a rotation or a dark-mode switch schedules
 * nothing. **Best effort:** the callback lives only as long as this process —
 * a manifest receiver cannot be sent `CONFIGURATION_CHANGED` — and with the
 * process dead the next render reads the new scale itself (spec §Refresh model).
 *
 * @param scope the app scope (`SupervisorJob`), because the render outlives the
 *   Activity that triggered it by definition and a failure must not take
 *   siblings down with it.
 */
class WidgetBackgroundRefresh(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** Call once, from `Application.onCreate` on the main thread. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    renderAll()
                }
            },
        )

        val app = context.applicationContext
        // Primed with the scale the process started under, so the first
        // configuration change that is NOT a font-scale change (a rotation)
        // compares against the truth rather than the holder's default.
        WidgetFontScale.process.offer(app.resources.configuration.fontScale)
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (WidgetFontScale.process.offer(newConfig.fontScale)) renderAll()
                }

                // Abstract in ComponentCallbacks, and deprecated in the platform
                // in favour of ComponentCallbacks2.onTrimMemory, which this hook
                // has no use for either. It must be implemented for the object
                // to exist; it is a no-op and calls nothing deprecated.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onLowMemory() = Unit
            },
        )
    }

    private fun renderAll() {
        scope.launch {
            // A launcher that has since dropped the widget, or a Glance session
            // that cannot start, is not something the app can act on — and this
            // runs on the app scope, where an escaping throw is a crash with no
            // user in front of it.
            runCatching { TodayWidget().updateAll(context) }
        }
    }
}
