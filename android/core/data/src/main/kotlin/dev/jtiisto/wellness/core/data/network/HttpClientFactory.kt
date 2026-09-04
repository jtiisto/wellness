package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.ZonedDateTime

const val CONNECT_TIMEOUT_MS = 10_000L
const val REQUEST_TIMEOUT_MS = 30_000L

/** Tag every HTTP line in the debug log carries. */
const val HTTP_LOG_TAG = "http"

/** IANA zone id of the phone, e.g. `Europe/Helsinki`. */
internal const val CLIENT_ZONE_HEADER = "X-Client-Zone"

/** That zone's UTC offset in whole minutes at send time, sign included. */
internal const val CLIENT_OFFSET_MIN_HEADER = "X-Client-Offset-Min"

private const val SECONDS_PER_MINUTE = 60

/**
 * The app's HTTP client: OkHttp, JSON content negotiation, and request logging
 * into the debug log.
 *
 * [config] is deliberately *not* installed as a `defaultRequest` base URL.
 * Ktor resolves a leading-slash path against the host root, which would drop
 * the `/wellness` prefix; every call therefore builds its URL through
 * [ServerConfig.endpoint] instead.
 *
 * Every request carries [CLIENT_ZONE_HEADER] and [CLIENT_OFFSET_MIN_HEADER],
 * the device clock the server buckets the watch's days by.
 */
fun buildHttpClient(
    @Suppress("UNUSED_PARAMETER") config: ServerConfig,
    json: Json,
    debugLog: DebugLog,
): HttpClient = HttpClient(OkHttp) { applyWellnessDefaults(json, debugLog) }

/**
 * Engine-injected variant, so tests exercise this exact configuration.
 *
 * [zone] and [now] exist for the same reason: the device-clock headers are the
 * one part of this configuration whose value depends on where and when the
 * phone is, and a test cannot move the JVM. Production takes the defaults.
 */
internal fun buildHttpClient(
    engine: HttpClientEngine,
    @Suppress("UNUSED_PARAMETER") config: ServerConfig,
    json: Json,
    debugLog: DebugLog,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    now: (ZoneId) -> ZonedDateTime = { ZonedDateTime.now(it) },
): HttpClient = HttpClient(engine) { applyWellnessDefaults(json, debugLog, zone, now) }

private fun HttpClientConfig<*>.applyWellnessDefaults(
    json: Json,
    debugLog: DebugLog,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    now: (ZoneId) -> ZonedDateTime = { ZonedDateTime.now(it) },
) {
    expectSuccess = true

    install(ContentNegotiation) { json(json) }

    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }

    install(Logging) {
        // INFO logs the method, URL and response status and nothing else.
        // Never raise this: request and response bodies are journal and coach
        // data, and the debug log is meant to be shareable.
        level = LogLevel.INFO
        logger = object : Logger {
            override fun log(message: String) = debugLog.log(HTTP_LOG_TAG, message)
        }
    }

    // Ktor evaluates this block once per request — `DefaultRequest.Plugin`
    // intercepts `HttpRequestPipeline.Phases.Before` and invokes the stored
    // block against a fresh builder each time — which is what makes the clock
    // headers below honest.
    defaultRequest {
        accept(ContentType.Application.Json)

        // The phone travels with the watch, so it is the only thing that knows
        // where the watch is: the server buckets Garmin's data by the device's
        // day and keeps a change-point timeline of what it is told. Both values
        // are read here, inside the per-request block, so a DST change or a
        // flight is reported by the very next call rather than by whatever was
        // true when the client was constructed. Nothing of this is logged: the
        // HTTP logger stays at INFO.
        val here = zone()
        val offsetMinutes = now(here).offset.totalSeconds / SECONDS_PER_MINUTE
        header(CLIENT_ZONE_HEADER, here.id)
        header(CLIENT_OFFSET_MIN_HEADER, offsetMinutes.toString())
    }
}
