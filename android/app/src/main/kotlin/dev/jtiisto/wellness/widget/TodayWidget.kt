package dev.jtiisto.wellness.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dev.jtiisto.wellness.core.data.journal.JournalDayPeek
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.TrendsCachePeek
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate

/**
 * The "Today" widget's session: read two local sources, hand the result to
 * [TodayWidgetContent], and never touch a network.
 *
 * **Only pre-resolution-safe singles are resolved here, and that is a hard
 * rule.** A launcher may be the very thing that created this process — no
 * Activity, no boot, no `ServerConfig` — and `TrendsRepository` /
 * `JournalSyncStore` are built with APIs that resolve
 * `ServerBootstrap.requireConfig()`, which *throws* by design before the boot
 * decision has been made. So the render path sees `TrendsCachePeek` and
 * `JournalDayPeek` (a DAO and the shared `Json`, nothing else),
 * [TrendsPrefs] and [DebugLog]. Fetching is the worker's job, in a process that
 * has booted; see [TodayWidgetWorker].
 *
 * **`today` and `now` are computed here, at every render, and never
 * remembered** — the same clock rule `SleepTonightCard` follows. A widget that
 * has sat on a home screen since yesterday must not answer with yesterday's
 * `today`, and the hourly worker plus the background hook are what bound how
 * long a stale day can survive (spec §Refresh model).
 *
 * The two elements fail **independently**. A journal database that will not
 * decode must not blank tonight's number, and an undecodable sleep payload must
 * not cost the tally, so each peek gets its own `runCatching` and its own null.
 * A cancelled render is not a failure and is rethrown untouched; nothing but the
 * exception's class name reaches [DebugLog], which never carries payloads.
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
        val now = System.currentTimeMillis()

        val rollup = runCatching { journalPeek.rollup(today) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                debugLog.log("widget", "journal peek failed (${failure.javaClass.simpleName})")
            }
            .getOrNull()

        val model = runCatching {
            widgetModel(trendsPeek.sleep(widgetPeekKeys(prefs.range.first())), now, today)
        }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                debugLog.log("widget", "sleep peek failed (${failure.javaClass.simpleName})")
            }
            .getOrNull()

        provideContent { TodayWidgetContent(rollup, model) }
    }
}
