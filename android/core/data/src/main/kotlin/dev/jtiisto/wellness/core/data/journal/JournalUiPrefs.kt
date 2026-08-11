package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalMetaEntity
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The journal view's own state — which categories are expanded, and when each
 * value was last touched. Device-local by definition: neither is anything the
 * server knows or should be told.
 *
 * Both live in `journal_meta` under a `ui.` prefix. That table is the sync
 * watermark's home, but the upload builder reads only the tracker and entry
 * tables, so nothing here can ever reach a request body — pinned by test,
 * because the cost of being wrong is leaking local UI state into the protocol.
 *
 * @param session the server-switch fence. Device-local is not the same as
 *   server-independent: both values are keyed by tracker id, `journal_meta` is
 *   wiped with the rest of the module, and a stamp written after the wipe would
 *   annotate a tracker the new server has never sent.
 */
class JournalUiPrefs(
    private val dao: JournalDao,
    private val session: ServerSessionGate,
    private val json: Json = WellnessJson,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /**
     * Read-modify-write on a single JSON blob, so two taps in flight at once
     * must not interleave. The DAO cannot serialize this for us: the merge
     * happens in Kotlin, between the read and the write.
     */
    private val writeMutex = Mutex()

    /** Categories the user has expanded. Everything starts collapsed. */
    val expandedCategories: Flow<Set<String>> =
        dao.observeMeta(KEY_EXPANDED_CATEGORIES).map(::decodeCategories)

    /**
     * `"YYYY-MM-DD|trackerId"` → the UTC instant its value was last written.
     * The accumulator's "did I already log that?" cue.
     */
    val valueUpdatedTimes: Flow<Map<String, String>> =
        dao.observeMeta(KEY_VALUE_UPDATED_TIMES).map(::decodeStamps)

    /**
     * Expand or collapse a category, by name. A name left behind by a rename is
     * simply never matched again — harmless, and cheaper than reconciling.
     */
    suspend fun toggleCategoryExpanded(category: String) {
        writeMutex.withLock {
            fenced {
                val current = decodeCategories(dao.getMeta(KEY_EXPANDED_CATEGORIES))
                val next = if (category in current) current - category else current + category
                dao.upsertMeta(
                    JournalMetaEntity(
                        key = KEY_EXPANDED_CATEGORIES,
                        value = JsonArray(next.sorted().map(::JsonPrimitive)).toString(),
                    ),
                )
            }
        }
    }

    /**
     * Stamp an entry's value as just-updated, and drop the stamps that have
     * aged out.
     *
     * The PWA never prunes this map, which grows without bound. Retention here
     * matches the entries themselves — a stamp survives while its date is still
     * inside the local data window — so the map can never outlive the data it
     * annotates.
     */
    suspend fun markValueUpdated(date: DateString, trackerId: String) {
        writeMutex.withLock {
            fenced {
                val current = decodeStamps(dao.getMeta(KEY_VALUE_UPDATED_TIMES))
                val windowStart = localDataWindowStart(today())
                val next = current
                    .filterKeys { entryKeyDate(it) >= windowStart }
                    .plus(entryKey(date, trackerId) to now().toString())
                dao.upsertMeta(
                    JournalMetaEntity(
                        key = KEY_VALUE_UPDATED_TIMES,
                        value = JsonObject(next.mapValues { (_, stamp) -> JsonPrimitive(stamp) }).toString(),
                    ),
                )
            }
        }
    }

    /**
     * Run a merge under the write lease, and let a refusal pass.
     *
     * The lease spans the **read** as well as the write, unlike the
     * self-contained marks elsewhere: both of these are read-modify-writes on a
     * single JSON blob, so a lease taken after the read would admit a merge
     * whose base is a row the wipe has since deleted, and write it back into the
     * emptied table.
     *
     * A refusal is swallowed. Both callers are tap handlers on a UI scope, and
     * the switch that refused the write is wiping the row it would have written
     * — taking the journal tab down on the way out is the only difference
     * raising it would make.
     */
    private suspend fun fenced(write: suspend () -> Unit) {
        try {
            session.withWriteLease(write)
        } catch (_: ServerSessionClosedException) {
            Unit
        }
    }

    private fun decodeCategories(raw: String?): Set<String> =
        (parseOrNull(raw) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

    private fun decodeStamps(raw: String?): Map<String, String> =
        (parseOrNull(raw) as? JsonObject)
            ?.mapNotNull { (key, element) -> (element as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            ?.toMap()
            .orEmpty()

    /** A corrupted blob is UI state, not user data: reset rather than crash the tab. */
    private fun parseOrNull(raw: String?) = raw?.let {
        runCatching { json.parseToJsonElement(it) }.getOrNull()
    }

    companion object {
        const val KEY_EXPANDED_CATEGORIES = "ui.expandedCategories"
        const val KEY_VALUE_UPDATED_TIMES = "ui.valueUpdatedTimes"
    }
}

/**
 * A "last updated" stamp as the row's caption: the time alone when it falls on
 * the day being viewed, otherwise a short date too.
 *
 * The stamp is a UTC instant and [selectedDate] a *local* calendar date, so the
 * comparison has to happen after converting into [zone] — near midnight the two
 * disagree about which day it is, and a naive prefix match would caption
 * today's edit with yesterday's date. Null for an unparseable stamp: a
 * corrupted caption must not take the row down with it.
 *
 * Both halves come from the locale rather than a hard-coded pattern, so a
 * 24-hour locale reads "15:42" instead of an Anglophone "3:42 PM".
 */
fun formatLastUpdated(
    isoInstant: String?,
    selectedDate: DateString,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    if (isoInstant.isNullOrBlank()) return null
    val moment = runCatching { Instant.parse(isoInstant).atZone(zone) }.getOrNull() ?: return null
    val time = moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    if (moment.toLocalDate().toString() == selectedDate) return time.normalizeSpaces()
    val date = moment.format(DateTimeFormatter.ofPattern(monthDayPattern(locale), locale))
    return "$date, $time".normalizeSpaces()
}

/**
 * The locale's medium date pattern with the year taken out.
 *
 * Stamps are pruned to a seven-day window, so the year is always the current
 * one and printing it is pure noise — but the month/day *order* is not ours to
 * assume (7/3 and 3.7. are both correct somewhere). Dropping the year field
 * from the localized pattern keeps the ordering the locale's while shortening
 * the caption. If the strip leaves something that no longer looks like a date,
 * the full medium pattern is used instead.
 */
private fun monthDayPattern(locale: Locale): String {
    val medium = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        FormatStyle.MEDIUM,
        null,
        IsoChronology.INSTANCE,
        locale,
    )
    val withoutYear = medium
        .replace(YEAR_FIELD, " ")
        .trim()
        .trim(',', '.', '/', '-', ' ')
    return if ('M' in withoutYear && 'd' in withoutYear) withoutYear else medium
}

/** A year field plus whatever separator it brought with it. */
private val YEAR_FIELD = Regex("""[^\p{Alpha}]*y+[^\p{Alpha}]*""")

/**
 * CLDR separates the time from its AM/PM marker with a narrow no-break space,
 * which renders inconsistently in a caption-sized line. Plain spaces read the
 * same and keep the string comparable.
 */
private fun String.normalizeSpaces(): String = replace(' ', ' ').replace(' ', ' ')
