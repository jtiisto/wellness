package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import kotlinx.coroutines.CancellationException
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
 * Failure posture throughout: **a render on someone's home screen has no error
 * surface**, so nothing here throws for a reason the caller could not act on
 * anyway. A cancelled render is the one exception — that is not a failure.
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
     * The first copy under [keys] — in order — that this build can decode.
     *
     * Order is the caller's preference, not a fallback ladder of decreasing
     * correctness: the widget asks for its own key first because the worker
     * keeps it freshest, and for the user's range second because a widget
     * placed before that worker's first run can still be right.
     *
     * A row that fails to decode **stays**, and the peek moves to the next key:
     * a payload this build cannot read may be exactly what the next one can
     * (the same reasoning `TrendsRepository.serveCached` records), and deleting
     * it buys nothing today. Nothing on this path writes to the cache at all.
     */
    suspend fun sleep(keys: List<String>): PeekedSleep? {
        for (key in keys) {
            val row: PayloadCacheEntity? = try {
                cacheDao.find(TrendsRepository.MODULE, key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (readFailure: Throwable) {
                // A read that throws is a fact about the *database* — unopenable,
                // migrating, gone — never about the key, so the peek ends here
                // rather than spending the same error again on the next one. The
                // widget draws its pending state, which is what a home screen
                // should show when there is nothing to say.
                return null
            }
            if (row == null) continue
            val dto = try {
                json.decodeFromString(SleepDebtDto.serializer(), row.payloadJson)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (decodeFailure: Throwable) {
                continue
            }
            return PeekedSleep(dto, row.fetchedAt)
        }
        return null
    }
}
