package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json

/**
 * The Trends cache, read without the network in front of it.
 *
 * This exists apart from [TrendsRepository] for one hard reason: the repository
 * **cannot be constructed before the server has resolved**. Koin builds it with
 * `api = get()`, `TrendsApi` takes a `ServerConfig`, and that single is
 * `ServerBootstrap.requireConfig()` — which throws by design, because asking
 * which server this process talks to before the boot decision has been made is
 * a bug worth crashing on. That is the right rule for a screen, and the wrong
 * one for a home-screen widget: the launcher renders it in a process that may
 * have been created *for the widget*, with no Activity and no boot behind it.
 *
 * So this class touches nothing but a DAO and the shared [Json] — the whole of
 * its dependency graph is pre-resolution-safe — and it never fetches. A widget
 * draws whatever the last successful fetch left behind, says how old it is, and
 * lets its worker be the thing that talks to a server.
 *
 * The read is a **Flow, collected inside the widget's composition**, because a
 * Glance session recomposes `provideContent` without re-running `provideGlance`:
 * a value captured before the composition would stay frozen for the session's
 * whole life, which on a device read as a tally and a number that ignored the
 * app until the session happened to die. Room re-emits when any watched row
 * changes, so a fetch landing in the cache — the hourly worker's or the app's
 * own — redraws the home screen in place.
 */
class TrendsCachePeek(
    private val cacheDao: PayloadCacheDao,
    private val json: Json,
) {

    /**
     * A cached sleep payload and the stamp it was stored with.
     *
     * [fetchedAt] is the *row's* age, not the current time, and is passed
     * through verbatim: what the surface decides to do with it (see the
     * widget's freshness window) is a display rule, and rewriting the stamp
     * here would take that decision away from the only code that can make it.
     */
    data class PeekedSleep(val dto: SleepDebtDto, val fetchedAt: Long)

    /**
     * The freshest copy under [keys] this build can decode, live.
     *
     * **Freshest wins, not first**: the widget watches its own worker's key and
     * the key the app's Health tab fetches under, and whichever copy carries
     * the newer [PayloadCacheEntity.fetchedAt] is the one drawn. Preferring a
     * fixed key would let the widget keep showing its hourly copy for up to an
     * hour after the app fetched a newer one — a home screen contradicting the
     * app it belongs to.
     *
     * A row that fails to decode is **kept and skipped**: a payload this build
     * cannot read may be exactly what the next one can (the same reasoning
     * `TrendsRepository.serveCached` records), and a fresher-but-unreadable
     * copy must not shadow an older one that decodes. Nothing on this path
     * writes to the cache at all.
     *
     * A database that cannot be read propagates through the flow — the
     * collector owns the no-error-surface rule and renders pending; see the
     * widget's collection site.
     */
    fun sleepFlow(keys: List<String>): Flow<PeekedSleep?> =
        if (keys.isEmpty()) {
            // combine() over nothing never emits, and a flow that never emits
            // hangs the first-frame priming. No keys means nothing to watch.
            flowOf(null)
        } else {
            combine(keys.map { key -> cacheDao.observe(TrendsRepository.MODULE, key) }) { rows ->
                rows.filterNotNull()
                    .mapNotNull(::decodeOrNull)
                    .maxByOrNull { it.fetchedAt }
            }
        }

    private fun decodeOrNull(row: PayloadCacheEntity): PeekedSleep? = try {
        PeekedSleep(json.decodeFromString(SleepDebtDto.serializer(), row.payloadJson), row.fetchedAt)
    } catch (_: IllegalArgumentException) {
        null
    }
}
