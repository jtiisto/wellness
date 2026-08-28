package dev.jtiisto.wellness.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Re-renders the widget the moment the app goes away.
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
                    scope.launch {
                        // A launcher that has since dropped the widget, or a
                        // Glance session that cannot start, is not something the
                        // app can act on — and this runs on the app scope, where
                        // an escaping throw is a crash with no user in front of
                        // it.
                        runCatching { TodayWidget().updateAll(context) }
                    }
                }
            },
        )
    }
}
