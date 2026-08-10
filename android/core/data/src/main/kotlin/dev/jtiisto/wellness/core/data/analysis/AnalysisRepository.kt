package dev.jtiisto.wellness.core.data.analysis

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.network.AnalysisApi
import dev.jtiisto.wellness.core.data.network.AnalysisHttpException
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A report fetch, with the bytes behind it when there were any.
 *
 * [raw] is the body exactly as it came off the wire, and null when the value was
 * served from the cache — there is nothing to write back in that case, and
 * re-writing it would restore a row a concurrent delete had just removed.
 */
data class FetchedReport(val detail: ReportDetailDto, val raw: String?)

/**
 * Reads and writes for the Analysis module, with `payload_cache` behind the two
 * calls worth reading offline.
 *
 * The failure matrix is the Trends contract, deliberately, and it is narrower
 * than the PWA's: there, *every* failure fell back to a cached copy. Here only a
 * transport failure or a 5xx does. A 4xx is the request being wrong — a report
 * that no longer exists, a query id the server has never heard of — and
 * answering it with an old payload would hide the real problem behind stale
 * content. The 409s and 404s this module leans on would be swallowed entirely.
 *
 * Only two shapes are ever cached: the history list, and a report **whose status
 * is terminal**. A pending report cached mid-flight would come back from disk on
 * the next launch looking permanently unfinished.
 */
class AnalysisRepository(
    private val api: AnalysisApi,
    private val cacheDao: PayloadCacheDao,
    private val debugLog: DebugLog,
    private val session: ServerSessionGate,
    private val json: Json = WellnessJson,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Network only, PWA parity: an empty query list offline is honest. */
    suspend fun queries(): List<AnalysisQueryDto> =
        json.decodeFromString(QUERY_LIST, api.queries())

    /** Network only: submitting is meaningless without a server to submit to. */
    suspend fun pending(): List<ReportDetailDto> =
        json.decodeFromString(DETAIL_LIST, api.pending())

    suspend fun submit(queryId: String, location: String?): SubmitResponseDto =
        api.submit(queryId, location)

    /**
     * The newest 50 reports, network-first.
     *
     * A fresh list also prunes: every `report_{id}` body whose id was in the
     * previous cached list and is not in this one has fallen out of the server's
     * window and can never be opened from History again. The PWA let those
     * accumulate forever; 50 bodies is the cap here, and the active report's id
     * is in the fetched list by construction, so a running poll's row is never
     * the one that goes.
     *
     * [deletedIds] are rows this session has already deleted. A list fetched
     * *before* a delete can arrive *after* it, and without this filter that
     * response would put the row back — on screen and, worse, on disk. When
     * nothing is filtered out the raw body is cached verbatim, so the ordinary
     * case still stores exactly what the server said.
     */
    suspend fun history(deletedIds: Set<Long> = emptySet()): List<ReportSummaryDto> {
        val body: String
        try {
            body = api.reports()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return serveCached(KEY_HISTORY, SUMMARY_LIST, error).filterNot { it.id in deletedIds }
        }

        val fetched = json.decodeFromString(SUMMARY_LIST, body)
        val value = fetched.filterNot { it.id in deletedIds }
        val superseded = previouslyCachedIds() - value.map { it.id }.toSet()
        writeCache(
            KEY_HISTORY,
            if (value.size == fetched.size) body else json.encodeToString(SUMMARY_LIST, value),
        )
        superseded.forEach { id -> deleteCached(reportKey(id)) }
        return value
    }

    /**
     * One full report, network-first. **Nothing is cached here.**
     *
     * The write is the caller's to make, through [cacheAcceptedReport], and only
     * once the store's generation check has accepted the result. Caching inside
     * this call put the decision in the wrong place: a poll tick whose state
     * mutation was correctly rejected — because the report had just been deleted
     * — had already written `report_{id}` back to disk on its way there, and the
     * deleted body came back on the next launch.
     *
     * When the network fails and nothing is cached the original error is
     * rethrown rather than converted: the caller shows "not available offline"
     * and leaves the current view alone, which needs the failure to arrive as a
     * failure.
     */
    suspend fun report(id: Long): FetchedReport {
        val body: String
        try {
            body = api.report(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return FetchedReport(serveCached(reportKey(id), ReportDetailDto.serializer(), error), raw = null)
        }
        return FetchedReport(json.decodeFromString(ReportDetailDto.serializer(), body), body)
    }

    /**
     * Store a report body the caller has decided is still wanted.
     *
     * Best-effort, like every other cache write here: the user has the report on
     * screen either way, and a row that would not save is next launch's problem.
     */
    suspend fun cacheAcceptedReport(id: Long, raw: String) {
        writeCache(reportKey(id), raw)
    }

    /**
     * Delete on the server, then tidy the cache.
     *
     * [remaining] is the caller's already-filtered list rather than a refetch:
     * the store holds the authoritative in-memory copy, and a second round trip
     * to rebuild something we know would be a request that can fail for no gain.
     *
     * Both cache writes are best-effort. The report is gone from the server
     * either way, and failing the delete because a local row would not budge
     * would leave the UI claiming something untrue.
     */
    suspend fun delete(id: Long, remaining: List<ReportSummaryDto>) {
        api.delete(id)
        deleteCached(reportKey(id))
        writeCache(KEY_HISTORY, json.encodeToString(SUMMARY_LIST, remaining))
    }

    /** Ids in the cached history as it stands *before* this fetch overwrites it. */
    private suspend fun previouslyCachedIds(): Set<Long> = try {
        cacheDao.find(MODULE, KEY_HISTORY)
            ?.let { json.decodeFromString(SUMMARY_LIST, it.payloadJson) }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        debugLog.log(TAG, "cached history unreadable for pruning (${error.javaClass.simpleName})")
        emptySet()
    }

    /**
     * The module's single cache-write path, and therefore the single place the
     * server-switch lease has to sit. A response that left the old server
     * before the switch would otherwise seed `payload_cache` with its content
     * moments after the wipe emptied it — and the lease, unlike a check, also
     * holds the switch back until this write has finished.
     */
    private suspend fun writeCache(key: String, payload: String) {
        try {
            session.withWriteLease { cacheDao.upsert(PayloadCacheEntity(MODULE, key, payload, clock())) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog.log(TAG, "cache write failed for $key (${error.javaClass.simpleName})")
        }
    }

    private suspend fun deleteCached(key: String) {
        try {
            cacheDao.delete(MODULE, key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog.log(TAG, "cache delete failed for $key (${error.javaClass.simpleName})")
        }
    }

    /**
     * The offline path, entered only for a transport failure or a 5xx.
     *
     * Whenever the cache cannot answer, the *original* error is what comes back
     * out — the user needs to hear about the network, not about a cache row that
     * happened to be missing behind it. A payload this build cannot decode stays
     * on disk: the next build may read it, and deleting it buys nothing today.
     */
    private suspend fun <T> serveCached(
        key: String,
        deserializer: DeserializationStrategy<T>,
        error: Throwable,
    ): T {
        val serverError = (error as? AnalysisHttpException)?.isServerError == true
        if (!isNetworkError(error) && !serverError) throw error

        val cached: PayloadCacheEntity? = try {
            cacheDao.find(MODULE, key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (readFailure: Throwable) {
            debugLog.log(TAG, "cache read failed for $key (${readFailure.javaClass.simpleName})")
            throw error
        }
        if (cached == null) throw error

        return try {
            json.decodeFromString(deserializer, cached.payloadJson)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            debugLog.log(TAG, "cached payload for $key failed to decode")
            throw error
        }
    }

    companion object {
        const val MODULE = "analysis"
        const val TAG = "analysis"

        const val KEY_HISTORY = "report_history"
        const val KEY_REPORT_PREFIX = "report_"

        fun reportKey(id: Long) = "$KEY_REPORT_PREFIX$id"

        private val QUERY_LIST = ListSerializer(AnalysisQueryDto.serializer())
        private val SUMMARY_LIST = ListSerializer(ReportSummaryDto.serializer())
        private val DETAIL_LIST = ListSerializer(ReportDetailDto.serializer())
    }
}
