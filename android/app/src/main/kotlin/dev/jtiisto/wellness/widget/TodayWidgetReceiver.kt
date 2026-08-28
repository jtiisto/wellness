package dev.jtiisto.wellness.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The launcher's handle on [TodayWidget], and the one place its hourly refresh
 * is turned on and off.
 *
 * Glance owns everything else this receiver would otherwise do — `onUpdate` and
 * the resize/delete traffic are handled by the base class, which is why every
 * override here calls through before doing anything of its own.
 */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    /** First placement: there is now someone to refresh for. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayWidgetWorker.schedule(context)
    }

    /**
     * Re-asserted on every update, which costs nothing: the unique work is
     * enqueued `KEEP`, so an already-scheduled job is left exactly as it is —
     * same period, same pending run.
     *
     * It is not decoration. `onEnabled` fires once in the widget's life, and the
     * schedule it created does not survive the app's data being cleared while
     * the widget stays on the home screen — after which nothing would ever
     * re-create it. `onUpdate` fires on boot and on app replacement, so this is
     * how that case heals itself.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TodayWidgetWorker.schedule(context)
    }

    /** Fires only when the **last** instance is removed — see [TodayWidgetWorker.cancel]. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TodayWidgetWorker.cancel(context)
    }
}
