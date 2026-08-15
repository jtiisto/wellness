package dev.jtiisto.wellness.core.ble

/**
 * Diagnostics seam for this module.
 *
 * The app's ring-buffer `DebugLog` lives in `:core:data`, which this module
 * deliberately does not depend on, so BLE code writes through this instead and
 * whoever wires it up bridges the two. The default is a no-op, which is what
 * unit tests run against.
 *
 * **Privacy**: the debug log is shareable, so the same rule applies here as
 * there — never log request or response bodies, or any journal or coach
 * content. What passes through this module is beat counts, connection states
 * and local persistence errors, none of which identify anything.
 */
fun interface BleLog {
    fun log(message: String)
}
