package dev.jtiisto.wellness.core.data.network

/**
 * An opaque server timestamp (ISO-8601, microsecond precision, `Z` suffix).
 *
 * Compared **lexically**, never parsed — the server's ordering is the only
 * ordering that matters. Local clock values (`ts`, `fetchedAt`) are epoch-millis
 * `Long`s and must not be conflated with these.
 */
typealias SyncStamp = String
