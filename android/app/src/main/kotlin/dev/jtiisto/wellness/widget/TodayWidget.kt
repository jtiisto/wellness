package dev.jtiisto.wellness.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dev.jtiisto.wellness.core.data.journal.JournalDayPeek
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.TrendsCachePeek
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate

/**
 * The "Today" widget's session: watch two local sources, hand each emission to
 * [TodayWidgetContent], and never touch a network.
 *
 * **Only pre-resolution-safe singles are resolved here, and that is a hard
 * rule.** A launcher may be the very thing that created this process — no
 * Activity, no boot, no `ServerConfig` — and `TrendsRepository` /
 * `JournalSyncStore` are built with APIs that resolve
 * `ServerBootstrap.requireConfig()`, which *throws* by design before the boot
 * decision has been made. So the render path sees `TrendsCachePeek` and
 * `JournalDayPeek` (DAOs and the shared `Json`, nothing else), [TrendsPrefs]
 * and [DebugLog]. Fetching is the worker's job, in a process that has booted;
 * see [TodayWidgetWorker].
 *
 * **The data is collected INSIDE the composition, and that is the load-bearing
 * shape.** A Glance session recomposes `provideContent` without re-running
 * `provideGlance`; the first cut computed both values up here, so every
 * `updateAll` that landed on a live session redrew the same captured day — a
 * tally that ignored the app until the session happened to die (third device
 * report). As flows, Room re-emits the moment a tracker is ticked or a fetch
 * lands in the cache, and the widget follows the app instead of trailing it.
 * The first frame is primed with each flow's current value so a session start
 * never flashes pending on the way to the truth.
 *
 * `today` is fixed per session — the hourly worker restarts dead sessions, so a
 * stale calendar day survives at most one period past midnight (spec §Refresh
 * model). `now` is read at every recomposition, the card's own clock rule.
 *
 * The two elements fail **independently**: each flow's failures are caught at
 * the collection seam, logged as nothing but the exception's class name (the
 * [DebugLog] privacy rule), and rendered as that element's honest absence.
 * `catch` rethrows cancellation on its own — a cancelled render is not a
 * failure.
 *
 * See `specs/widget.md`.
 */
class TodayWidget : GlanceAppWidget(), KoinComponent {

    // Exact, not Responsive, and the difference reached a device: Responsive
    // hands the composition the matched BUCKET size, so a strip stretched to
    // twice its floor still laid out — and fit its tally — as if it were
    // 110×40dp (first device report: a lone fraction in a field of paper).
    // The bucket thresholds in [widgetBucket] still decide what renders; Exact
    // just makes the width they and the fit ladder read the truth.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val journalPeek: JournalDayPeek = get()
        val trendsPeek: TrendsCachePeek = get()
        val prefs: TrendsPrefs = get()
        val debugLog: DebugLog = get()

        val today = LocalDate.now().toString()

        val rollupFlow = journalPeek.rollupFlow(today)
            .catch { failure ->
                debugLog.log("widget", "journal peek failed (${failure.javaClass.simpleName})")
                emit(null)
            }
        val sleepFlow = trendsPeek.sleepFlow(widgetPeekKeys(prefs.range.first()))
            .catch { failure ->
                debugLog.log("widget", "sleep peek failed (${failure.javaClass.simpleName})")
                emit(null)
            }

        // Primed before composition: `catch` above has already turned a failed
        // first read into a null, so these cannot throw past it.
        val initialRollup = rollupFlow.first()
        val initialSleep = sleepFlow.first()

        provideContent {
            val rollup by rollupFlow.collectAsState(initial = initialRollup)
            val peeked by sleepFlow.collectAsState(initial = initialSleep)
            TodayWidgetContent(
                rollup = rollup,
                model = widgetModel(peeked, now = System.currentTimeMillis(), today = today),
            )
        }
    }
}
