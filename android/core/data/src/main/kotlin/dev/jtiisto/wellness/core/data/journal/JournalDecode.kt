package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Room row → DTO, in the one direction more than one caller needs.
 *
 * These two were private to [JournalSyncStore] until the home-screen widget's
 * [JournalDayPeek] became their second consumer: the widget renders from the
 * DAO directly, because the store itself is unconstructible before the server
 * has resolved (its `JournalApi` wants a `ServerConfig`), and a launcher may
 * well be the thing that created this process. Promoting them was the cheap
 * half of that; the expensive alternative was a forked entity→DTO seam, which
 * is precisely the kind of duplicate that drifts silently and is only noticed
 * when two surfaces disagree about the same row.
 *
 * The *encode* direction stays in the store, and stays private: writing a row
 * means deciding its dirty flags and its generation, which is sync's business
 * and nobody else's.
 */

/**
 * A tracker row's stored config, back as a [TrackerDto].
 *
 * Throws when `dataJson` is not a JSON object or carries no `id` — the row was
 * written by this same module and cannot legitimately be either. Callers with
 * no error surface (a widget render) catch and skip; the sync path lets it
 * fail, because a config it cannot read is not a config it may upload.
 */
fun decodeTracker(row: JournalTrackerEntity, json: Json): TrackerDto =
    TrackerDtoSerializer.fromJson(json.parseToJsonElement(row.dataJson).jsonObject, json)

/**
 * One day's entry row as the display rules see it.
 *
 * `valueJson` is parsed rather than typed: the column holds the server's exact
 * literal — a number, a note string, or the JSON `null` that means "the server
 * sent a null" — and [EntryDto.value] preserves all three, absence included.
 */
fun JournalEntryEntity.toDto(json: Json): EntryDto = EntryDto(
    value = valueJson?.let(json::parseToJsonElement),
    completed = completed,
    lastModifiedAt = lastModifiedAt,
)
