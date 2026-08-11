package dev.jtiisto.wellness.core.ble.model

/**
 * Where a strap connection is. [RECONNECTING] is distinct from [CONNECTING]
 * because it is the state a backoff is running under — the UI colours them
 * differently and the capture service arms its inactivity timeout on it.
 */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}
