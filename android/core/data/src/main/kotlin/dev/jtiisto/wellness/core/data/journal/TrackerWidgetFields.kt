package dev.jtiisto.wellness.core.data.journal

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The widget-shaped view of a tracker's `meta_json` passthrough.
 *
 * `unit`, `defaultValue` and `accumulator` are server-owned extras rather than
 * typed DTO fields (see [TrackerDto.extras]), so every row that renders them
 * reads through these accessors — one place where a malformed or explicitly
 * null value is turned into "absent" instead of crashing a row.
 */

/** The four entry widgets. An unknown or missing type renders as [SIMPLE]. */
enum class TrackerType {
    SIMPLE,
    QUANTIFIABLE,
    EVALUATION,
    NOTE,
    ;

    /** The value stored on the wire. */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(type: String?): TrackerType =
            entries.firstOrNull { it.wire == type } ?: SIMPLE
    }
}

/** The tracker's type as an enum. */
fun TrackerDto.trackerType(): TrackerType = TrackerType.fromWire(type)

/** The unit suffix ("g", "min"), or null when unset or blank. */
fun TrackerDto.unitOrNull(): String? =
    (extras["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/**
 * The value a checkbox writes when there is none yet. Numeric primitives and
 * numeric strings both count; an explicit JSON null, a boolean, or anything
 * unparseable reads as absent.
 */
fun TrackerDto.defaultValueOrNull(): Double? = coerceNumericValue(extras["defaultValue"])

/** Whether the row gets the running-total "+" button. A JSON `true` only. */
fun TrackerDto.isAccumulator(): Boolean {
    val primitive = extras["accumulator"] as? JsonPrimitive ?: return false
    // `booleanOrNull` would happily read the *string* "true"; the PWA compares
    // with `=== true`, so only a real JSON boolean counts.
    return !primitive.isString && primitive.booleanOrNull == true
}
