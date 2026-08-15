package dev.jtiisto.wellness.core.data.network

/**
 * Where the Wellness server lives.
 *
 * [baseUrl] includes the `/wellness` path prefix that the server strips in
 * middleware. Endpoint paths *append* to that prefix — see [endpoint].
 */
data class ServerConfig(val baseUrl: String) {

    /** [baseUrl] with any trailing slash removed, so [endpoint] joins cleanly. */
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    /**
     * Absolute URL for [path].
     *
     * Always build request URLs through this. Handing Ktor a leading-slash path
     * resolves it against the host root and silently drops `/wellness`, which
     * turns every call into a 404 that looks like a server problem.
     */
    fun endpoint(path: String): String = normalizedBaseUrl + "/" + path.trimStart('/')
}
