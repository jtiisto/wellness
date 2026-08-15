package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.TrendsApi
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One fetch's outcome, with the freshness of the copy it carries.
 *
 * [staleFetchedAt] is null when the value came off the network just now, and
 * otherwise holds the epoch-millis stamp the *cached* copy was stored with —
 * its own age, not the current time. There is deliberately no repository-wide
 * staleness map: `weight:{range}` is fetched by both Overview and Health, and a
 * shared map could only ever describe one of them correctly.
 */
data class FetchResult<T>(val value: T, val staleFetchedAt: Long?)

/**
 * Network-first reads of the Trends aggregates, with the `payload_cache` table
 * as the offline fallback.
 *
 * The fallback is narrow on purpose: only a transport failure or a 5xx falls
 * back to a cached copy. A 4xx means the request itself was wrong — a tracker
 * that no longer exists, say — and serving a stale answer to it would hide a
 * real problem behind an old chart.
 */
class TrendsRepository(
    private val api: TrendsApi,
    private val cacheDao: PayloadCacheDao,
    private val debugLog: DebugLog,
    private val session: ServerSessionGate,
    private val json: Json = WellnessJson,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun overview(): FetchResult<OverviewDto> =
        fetchCached(KEY_OVERVIEW, OverviewDto.serializer()) { api.overview() }

    suspend fun weight(start: DateString?, end: DateString, range: String): FetchResult<WeightDto> =
        fetchCached(weightKey(range), WeightDto.serializer()) { api.weight(start, end) }

    suspend fun strengthExercises(
        start: DateString?,
        end: DateString,
        range: String,
    ): FetchResult<ExercisesDto> =
        fetchCached(exercisesKey(range), ExercisesDto.serializer()) { api.strengthExercises(start, end) }

    suspend fun strengthExercise(
        slug: String,
        start: DateString?,
        end: DateString,
        range: String,
    ): FetchResult<ExerciseDetailDto> =
        fetchCached(exerciseKey(slug, range), ExerciseDetailDto.serializer()) {
            api.strengthExercise(slug, start, end)
        }

    suspend fun strengthVolume(start: DateString?, end: DateString, range: String): FetchResult<VolumeDto> =
        fetchCached(volumeKey(range), VolumeDto.serializer()) { api.strengthVolume(start, end) }

    suspend fun cardio(start: DateString?, end: DateString, range: String): FetchResult<CardioDto> =
        fetchCached(cardioKey(range), CardioDto.serializer()) { api.cardio(start, end) }

    suspend fun journalTrackers(): FetchResult<TrackersDto> =
        fetchCached(KEY_TRACKERS, TrackersDto.serializer()) { api.journalTrackers() }

    suspend fun journalTracker(
        id: String,
        start: DateString?,
        end: DateString,
        range: String,
    ): FetchResult<TrackerDetailDto> =
        fetchCached(trackerKey(id, range), TrackerDetailDto.serializer()) {
            api.journalTracker(id, start, end)
        }

    suspend fun healthRecovery(start: DateString?, end: DateString, range: String): FetchResult<RecoveryDto> =
        fetchCached(recoveryKey(range), RecoveryDto.serializer()) { api.healthRecovery(start, end) }

    suspend fun healthComposition(end: DateString): FetchResult<CompositionDto> =
        fetchCached(KEY_COMPOSITION, CompositionDto.serializer()) { api.healthComposition(end) }

    suspend fun healthLabs(end: DateString): FetchResult<LabsDto> =
        fetchCached(KEY_LABS, LabsDto.serializer()) { api.healthLabs(end) }

    /**
     * Fetch, decode, cache — in that order, and the order is the contract.
     *
     * Decoding before the write means a body we cannot read never replaces a
     * cached copy we can. A failed write, on the other hand, is not allowed to
     * fail the call: the user asked for data, the network gave it, and a sick
     * cache is a problem for the next launch, not for this chart.
     */
    private suspend fun <T> fetchCached(
        cacheKey: String,
        deserializer: DeserializationStrategy<T>,
        fetch: suspend () -> String,
    ): FetchResult<T> {
        val body: String
        try {
            body = fetch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return serveCached(cacheKey, deserializer, error)
        }

        val value = json.decodeFromString(deserializer, body)

        try {
            // The lease spans the upsert, not merely a check in front of it: the
            // upsert suspends, so a write admitted before the switch closed
            // would otherwise land after the wipe and seed the cache with the
            // previous server's chart data.
            session.withWriteLease { cacheDao.upsert(PayloadCacheEntity(MODULE, cacheKey, body, clock())) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog.log(TAG, "cache write failed for $cacheKey (${error.javaClass.simpleName})")
        }
        return FetchResult(value, null)
    }

    /**
     * The offline path. Anything that is not a transport failure or a 5xx is
     * rethrown untouched, and so is the original error whenever the cache
     * cannot help — the user needs to hear about the network, not about the
     * cache row that happened to be missing behind it.
     */
    private suspend fun <T> serveCached(
        cacheKey: String,
        deserializer: DeserializationStrategy<T>,
        error: Throwable,
    ): FetchResult<T> {
        if (!isNetworkError(error) && error !is ServerResponseException) throw error

        val cached: PayloadCacheEntity? = try {
            cacheDao.find(MODULE, cacheKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (readFailure: Throwable) {
            debugLog.log(TAG, "cache read failed for $cacheKey (${readFailure.javaClass.simpleName})")
            throw error
        }
        if (cached == null) throw error

        val value = try {
            json.decodeFromString(deserializer, cached.payloadJson)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (decodeFailure: Throwable) {
            // The row stays: a payload this build cannot read may be exactly
            // what the next one can, and deleting it buys nothing today.
            debugLog.log(TAG, "cached payload for $cacheKey failed to decode")
            throw error
        }
        return FetchResult(value, cached.fetchedAt)
    }

    companion object {
        const val MODULE = "trends"
        const val TAG = "trends"

        const val KEY_OVERVIEW = "overview"
        const val KEY_TRACKERS = "journal/trackers"
        const val KEY_COMPOSITION = "health/composition"
        const val KEY_LABS = "health/labs"

        /** Shared by Overview and Health — each holds its own [FetchResult]. */
        fun weightKey(range: String) = "weight:$range"

        fun exercisesKey(range: String) = "strength/exercises:$range"

        /** The slug stays **raw** here even though the URL encodes it (PWA parity). */
        fun exerciseKey(slug: String, range: String) = "strength/$slug:$range"

        fun volumeKey(range: String) = "volume:$range"

        fun cardioKey(range: String) = "cardio:$range"

        fun trackerKey(id: String, range: String) = "journal/$id:$range"

        fun recoveryKey(range: String) = "health/recovery:$range"
    }
}

/**
 * What to put on screen when a fetch failed.
 *
 * Classification is by exception type, never by parsing a body: the server's
 * 422 `detail` is polymorphic and decoding it would be one more thing that can
 * fail while already handling a failure.
 */
fun describeFetchError(error: Throwable): String = when {
    error is ResponseException -> "HTTP ${error.response.status.value}"
    isNetworkError(error) -> "Offline — check connection"
    error is SerializationException -> "Unexpected server response"
    else -> "Unexpected error"
}
