package dev.jtiisto.wellness.core.network

data class ServerConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://100.64.0.1:9000/wellness"
    }
}
