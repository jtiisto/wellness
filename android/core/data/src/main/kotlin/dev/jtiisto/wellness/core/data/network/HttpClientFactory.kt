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
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

const val CONNECT_TIMEOUT_MS = 10_000L
const val REQUEST_TIMEOUT_MS = 30_000L

/** Tag every HTTP line in the debug log carries. */
const val HTTP_LOG_TAG = "http"

/**
 * The app's HTTP client: OkHttp, JSON content negotiation, and request logging
 * into the debug log.
 *
 * [config] is deliberately *not* installed as a `defaultRequest` base URL.
 * Ktor resolves a leading-slash path against the host root, which would drop
 * the `/wellness` prefix; every call therefore builds its URL through
 * [ServerConfig.endpoint] instead.
 */
fun buildHttpClient(
    @Suppress("UNUSED_PARAMETER") config: ServerConfig,
    json: Json,
    debugLog: DebugLog,
): HttpClient = HttpClient(OkHttp) { applyWellnessDefaults(json, debugLog) }

/** Engine-injected variant, so tests exercise this exact configuration. */
internal fun buildHttpClient(
    engine: HttpClientEngine,
    @Suppress("UNUSED_PARAMETER") config: ServerConfig,
    json: Json,
    debugLog: DebugLog,
): HttpClient = HttpClient(engine) { applyWellnessDefaults(json, debugLog) }

private fun HttpClientConfig<*>.applyWellnessDefaults(json: Json, debugLog: DebugLog) {
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

    defaultRequest { accept(ContentType.Application.Json) }
}
