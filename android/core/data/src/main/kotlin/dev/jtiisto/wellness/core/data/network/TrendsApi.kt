package dev.jtiisto.wellness.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart

/**
 * The twelve read-only Trends aggregate endpoints.
 *
 * Every call returns the **raw body text** rather than a decoded DTO: the
 * repository caches exactly what came off the wire, so a later server field
 * addition can never invalidate a stored payload. Decoding happens once, in
 * the repository, against the same string that gets written to the cache.
 *
 * `start` is omitted entirely for the All range — an empty `start=` is a
 * different request, and the server reads its absence as "no lower bound".
 * `end` is always sent: without it the server would fall back to *its* local
 * today, which is not necessarily the device's.
 */
class TrendsApi(
    private val client: HttpClient,
    private val config: ServerConfig,
) {

    suspend fun overview(): String = fetch(OVERVIEW_PATH)

    suspend fun weight(start: DateString?, end: DateString): String =
        fetch(WEIGHT_PATH, start, end)

    suspend fun strengthExercises(start: DateString?, end: DateString): String =
        fetch(EXERCISES_PATH, start, end)

    /** [slug] is URL-encoded here; the cache key keeps it raw (PWA parity). */
    suspend fun strengthExercise(slug: String, start: DateString?, end: DateString): String =
        fetch("$EXERCISE_PATH/${slug.encodeURLPathPart()}", start, end)

    suspend fun strengthVolume(start: DateString?, end: DateString): String =
        fetch(VOLUME_PATH, start, end)

    suspend fun cardio(start: DateString?, end: DateString): String =
        fetch(CARDIO_PATH, start, end)

    suspend fun journalTrackers(): String = fetch(TRACKERS_PATH)

    suspend fun journalTracker(id: String, start: DateString?, end: DateString): String =
        fetch("$TRACKER_PATH/${id.encodeURLPathPart()}", start, end)

    suspend fun healthRecovery(start: DateString?, end: DateString): String =
        fetch(RECOVERY_PATH, start, end)

    /**
     * Range-bounded like recovery, but only in what comes *back*: the server's
     * ledger always replays from the start of history, so `start` clips the
     * rows rather than the arithmetic behind them.
     */
    suspend fun healthSleep(start: DateString?, end: DateString): String =
        fetch(SLEEP_PATH, start, end)

    /** Range-immune: every scan up to [end], however far back. */
    suspend fun healthComposition(end: DateString): String = fetch(COMPOSITION_PATH, null, end)

    /** Range-immune, same reason as composition: reports are months apart. */
    suspend fun healthLabs(end: DateString): String = fetch(LABS_PATH, null, end)

    /**
     * The no-cache headers mirror the PWA's `cache: 'no-store'`. No HTTP cache
     * is installed today, and none should be: a response served from one would
     * defeat the payload cache's staleness stamp, which is the only thing
     * telling the user their charts are old.
     */
    private suspend fun fetch(path: String, start: DateString? = null, end: DateString? = null): String =
        client.get(config.endpoint(path)) {
            if (start != null) parameter("start", start)
            if (end != null) parameter("end", end)
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.bodyAsText()

    companion object {
        const val BASE_PATH = "/api/trends"
        const val OVERVIEW_PATH = "$BASE_PATH/overview"
        const val WEIGHT_PATH = "$BASE_PATH/weight"
        const val EXERCISES_PATH = "$BASE_PATH/strength/exercises"
        const val EXERCISE_PATH = "$BASE_PATH/strength/exercise"
        const val VOLUME_PATH = "$BASE_PATH/strength/volume"
        const val CARDIO_PATH = "$BASE_PATH/cardio"
        const val TRACKERS_PATH = "$BASE_PATH/journal/trackers"
        const val TRACKER_PATH = "$BASE_PATH/journal/tracker"
        const val RECOVERY_PATH = "$BASE_PATH/health/recovery"
        const val SLEEP_PATH = "$BASE_PATH/health/sleep"
        const val COMPOSITION_PATH = "$BASE_PATH/health/composition"
        const val LABS_PATH = "$BASE_PATH/health/labs"
    }
}
