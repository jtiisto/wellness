package dev.jtiisto.wellness.core.data

import kotlinx.serialization.json.Json

/**
 * The single Json configuration for wire traffic and stored payloads.
 *
 * - `ignoreUnknownKeys`: the server adds fields without a client release.
 * - `explicitNulls` off + `encodeDefaults` off: optional wire fields are
 *   *omitted*, never null — uploading a null would persist it server-side.
 */
val WellnessJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}
