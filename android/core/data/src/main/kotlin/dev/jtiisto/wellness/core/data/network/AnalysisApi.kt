package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.analysis.SubmitResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A non-2xx from the Analysis API, with the server's own words attached.
 *
 * Callers classify on [status] and never on a message. Two of this module's
 * responses are load-bearing — the submit 409 that means "one is already
 * running" and the report 404 that means "this row is gone" — and both would be
 * matched by substring somewhere if the status were not carried explicitly.
 *
 * [detail] is the server's product copy, shown to the user verbatim when it is
 * there. It is null whenever the body was not a JSON object with a string
 * `detail`: FastAPI's 422 puts a *list* in that key, and half-decoding a
 * validation error into a sentence is worse than saying nothing.
 */
class AnalysisHttpException(
    val status: Int,
    val detail: String?,
    cause: Throwable? = null,
) : RuntimeException(detail ?: "HTTP $status", cause) {

    /** A 5xx: the request was fine, the server was not. Eligible for a cached fallback. */
    val isServerError: Boolean get() = status in 500..599
}

/** The submit body. `location` is omitted entirely when the caller has none. */
@Serializable
private data class SubmitRequest(
    @SerialName("query_id") val queryId: String,
    val location: String? = null,
)

/**
 * The six Analysis endpoints.
 *
 * The four read paths return **raw body text**, as Trends does: the repository
 * caches exactly the bytes that arrived, so a field the server adds later can
 * never invalidate a payload already on disk. Submit is the exception — its
 * response is two fields that are consumed immediately and never stored.
 *
 * There is no `client_id` anywhere in this module. The journal and coach sync
 * endpoints take one; these do not, and adding it would be a query parameter the
 * server silently ignores today and might validate tomorrow.
 */
class AnalysisApi(
    private val client: HttpClient,
    private val config: ServerConfig,
    private val json: Json,
) {

    suspend fun queries(): String = guarded { get(QUERIES_PATH) }

    suspend fun reports(): String = guarded { get(REPORTS_PATH) }

    suspend fun pending(): String = guarded { get(PENDING_PATH) }

    suspend fun report(id: Long): String = guarded { get("$REPORTS_PATH/$id") }

    /** 201 on success; 409 when one is already running; 404 for an unknown query id. */
    suspend fun submit(queryId: String, location: String?): SubmitResponseDto = guarded {
        client.post(config.endpoint(REPORTS_PATH)) {
            contentType(ContentType.Application.Json)
            setBody(SubmitRequest(queryId, location?.takeIf { it.isNotBlank() }))
        }.body()
    }

    /** 409 while the report is still running — the server refuses, and says why. */
    suspend fun delete(id: Long) {
        guarded { client.delete(config.endpoint("$REPORTS_PATH/$id")).bodyAsText() }
    }

    private suspend fun get(path: String): String =
        client.get(config.endpoint(path)) {
            // Mirrors the PWA's `cache: 'no-store'`. A poll served from an HTTP
            // cache would watch a finished report stay pending forever.
            header(HttpHeaders.CacheControl, "no-store")
            header(HttpHeaders.Pragma, "no-cache")
        }.bodyAsText()

    /**
     * Turn Ktor's status exceptions into [AnalysisHttpException].
     *
     * Everything else — timeouts, connect failures, decode failures — passes
     * through untouched so `isNetworkError` and `SerializationException` still
     * mean what they mean upstream.
     */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (response: ResponseException) {
        throw AnalysisHttpException(
            status = response.response.status.value,
            detail = extractDetail(response),
            cause = response,
        )
    }

    /**
     * The `detail` string, or null.
     *
     * Reading the body of a failed response can itself fail, and a failure
     * inside failure handling would replace a 409 the caller knows how to
     * recover from with an exception it does not.
     */
    private suspend fun extractDetail(response: ResponseException): String? = try {
        val body = response.response.bodyAsText()
        val element = json.parseToJsonElement(body)
        ((element as? JsonObject)?.get(DETAIL_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val BASE_PATH = "/api/analysis"
        const val QUERIES_PATH = "$BASE_PATH/queries"
        const val REPORTS_PATH = "$BASE_PATH/reports"
        const val PENDING_PATH = "$REPORTS_PATH/pending"

        private const val DETAIL_KEY = "detail"
    }
}
