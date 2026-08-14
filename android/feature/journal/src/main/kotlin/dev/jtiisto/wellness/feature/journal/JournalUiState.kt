package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.CategoryBadge
import dev.jtiisto.wellness.core.data.journal.DayDot
import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.TargetProgress
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.categorySummary
import dev.jtiisto.wellness.core.data.journal.coerceNumericValue
import dev.jtiisto.wellness.core.data.journal.dayStatus
import dev.jtiisto.wellness.core.data.journal.defaultValueOrNull
import dev.jtiisto.wellness.core.data.journal.entryKey
import dev.jtiisto.wellness.core.data.journal.formatCategorySummary
import dev.jtiisto.wellness.core.data.journal.formatJournalNumber
import dev.jtiisto.wellness.core.data.journal.formatLastUpdated
import dev.jtiisto.wellness.core.data.journal.formatTargetProgress
import dev.jtiisto.wellness.core.data.journal.getLastNDays
import dev.jtiisto.wellness.core.data.journal.groupByCategory
import dev.jtiisto.wellness.core.data.journal.isDayEditable
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import dev.jtiisto.wellness.core.data.journal.localDataWindowStart
import dev.jtiisto.wellness.core.data.journal.recentDayStates
import dev.jtiisto.wellness.core.data.journal.shouldShowTracker
import dev.jtiisto.wellness.core.data.journal.trackerType
import dev.jtiisto.wellness.core.data.journal.unitOrNull
import dev.jtiisto.wellness.core.data.journal.isAccumulator
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Everything the journal day view renders, derived in one pure pass.
 *
 * The composables downstream are deliberately dumb: they read fields off this
 * and draw. Every rule with a decision in it — what is visible, what is
 * editable, which value a field shows when there is no entry — happens in
 * [buildJournalUiState], where it can be tested without a Compose rig.
 */
data class JournalUiState(
    val selectedDate: DateString = "",
    val dateStrip: List<DateCellState> = emptyList(),
    val groups: List<CategoryGroupState> = emptyList(),
    val emptyState: JournalEmptyState? = null,
    val syncStatus: SyncStatus = SyncStatus.GRAY,
)

/** The two "nothing to show" cases, which need different words. */
enum class JournalEmptyState { NO_TRACKERS, NONE_SCHEDULED }

/** One day of the strip. [enabled] false means the lock badge shows. */
data class DateCellState(
    val date: DateString,
    val dayName: String,
    val dayNum: Int,
    val isToday: Boolean,
    val isSelected: Boolean,
    val enabled: Boolean,
)

data class CategoryGroupState(
    val name: String,
    val expanded: Boolean,
    val summary: CategoryBadge?,
    val trackers: List<TrackerRowState>,
)

/**
 * One tracker row, fully resolved.
 *
 * [displayedNumber] is the value the widgets act *against* — the stored value,
 * else the tracker's default, else 50 for an evaluation. It is what a numeric
 * commit compares to (so echoing the displayed default back is a no-op rather
 * than a phantom entry) and what an accumulator adds to.
 *
 * [committed] is the checkbox being genuinely `true`, which is what drives the
 * ghosted styling; [checked] is the checkbox's own state and may be false.
 */
data class TrackerRowState(
    val id: String,
    val name: String,
    val type: TrackerType,
    val editable: Boolean,
    val checked: Boolean,
    val committed: Boolean,
    val valueText: String,
    val displayedNumber: Double?,
    val sliderValue: Float,
    val unit: String?,
    val isAccumulator: Boolean,
    val targetProgress: TargetProgress?,
    val lastUpdatedCaption: String?,
    val dots: List<DayDot>,
    val avoidPolarity: Boolean,
)

/** Evaluation sliders start mid-scale when nothing has been logged. */
const val EVALUATION_DEFAULT = 50.0

/**
 * Derive the whole day view.
 *
 * [selectedDate] is clamped into the strip: a screen left open across midnight
 * would otherwise point at a day the strip no longer offers.
 */
@Suppress("LongParameterList")
fun buildJournalUiState(
    trackers: List<TrackerDto>,
    entriesByDate: Map<DateString, Map<String, EntryDto>>,
    dirtyTrackerCount: Int,
    selectedDate: DateString,
    today: LocalDate,
    expandedCategories: Set<String>,
    valueUpdatedTimes: Map<String, String> = emptyMap(),
    syncStatus: SyncStatus = SyncStatus.GRAY,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): JournalUiState {
    val todayStr = today.toString()
    val strip = getLastNDays(today)
    val date = if (strip.any { it.date == selectedDate }) selectedDate else todayStr
    val dayLog = entriesByDate[date].orEmpty()
    val editable = isDayEditable(date, todayStr, dirtyTrackerCount)
    val windowStart = localDataWindowStart(today)

    val dateStrip = strip.map { cell ->
        DateCellState(
            date = cell.date,
            dayName = cell.dayName,
            dayNum = cell.dayNum,
            isToday = cell.isToday,
            isSelected = cell.date == date,
            enabled = isDayEditable(cell.date, todayStr, dirtyTrackerCount),
        )
    }

    val visible = trackers.filter { shouldShowTracker(it, date, dayLog) }
    val groups = groupByCategory(visible).map { (category, categoryTrackers) ->
        val expanded = category in expandedCategories
        CategoryGroupState(
            name = category,
            expanded = expanded,
            // Only worth showing while the rows themselves are hidden.
            summary = if (expanded) null else formatCategorySummary(categorySummary(categoryTrackers, date, dayLog)),
            trackers = categoryTrackers.map { tracker ->
                buildTrackerRowState(
                    tracker = tracker,
                    entry = dayLog[tracker.id],
                    entriesByDate = entriesByDate,
                    date = date,
                    editable = editable,
                    windowStart = windowStart,
                    valueUpdatedTimes = valueUpdatedTimes,
                    zone = zone,
                    locale = locale,
                )
            },
        )
    }

    return JournalUiState(
        selectedDate = date,
        dateStrip = dateStrip,
        groups = groups,
        emptyState = when {
            trackers.isEmpty() -> JournalEmptyState.NO_TRACKERS
            visible.isEmpty() -> JournalEmptyState.NONE_SCHEDULED
            else -> null
        },
        syncStatus = syncStatus,
    )
}

@Suppress("LongParameterList")
private fun buildTrackerRowState(
    tracker: TrackerDto,
    entry: EntryDto?,
    entriesByDate: Map<DateString, Map<String, EntryDto>>,
    date: DateString,
    editable: Boolean,
    windowStart: DateString,
    valueUpdatedTimes: Map<String, String>,
    zone: ZoneId,
    locale: Locale,
): TrackerRowState {
    val type = tracker.trackerType()
    val quantifiable = type == TrackerType.QUANTIFIABLE
    val displayed = displayedValue(tracker, entry, type)
    val displayedNumber = coerceNumericValue(displayed)

    return TrackerRowState(
        id = tracker.id,
        name = tracker.name.orEmpty(),
        type = type,
        editable = editable,
        checked = entry?.completed ?: false,
        committed = entry?.completed == true,
        valueText = displayed.asFieldText(),
        displayedNumber = displayedNumber,
        sliderValue = (displayedNumber ?: EVALUATION_DEFAULT).coerceIn(0.0, 100.0).toFloat(),
        unit = tracker.unitOrNull(),
        isAccumulator = quantifiable && tracker.isAccumulator(),
        targetProgress = if (quantifiable) {
            formatTargetProgress(dayStatus(tracker, date, entry), tracker.unitOrNull())
        } else {
            null
        },
        lastUpdatedCaption = if (quantifiable) {
            formatLastUpdated(valueUpdatedTimes[entryKey(date, tracker.id)], date, zone, locale)
        } else {
            null
        },
        // The dot row ends on the SELECTED date, not today, so browsing back
        // shows that day's week. Days older than the local window are muted:
        // their logs are pruned, and absence there is unknown, not missed.
        dots = recentDayStates(tracker, date, entriesByDate, DOT_ROW_DAYS, windowStart),
        avoidPolarity = tracker.polarity == "negative",
    )
}

/** The stored value, else the tracker's default, else the evaluation mid-point. */
private fun displayedValue(tracker: TrackerDto, entry: EntryDto?, type: TrackerType): JsonElement? {
    entry?.value?.takeIf { it !is JsonNull }?.let { return it }
    tracker.defaultValueOrNull()?.let { return journalNumberJson(it) }
    return if (type == TrackerType.EVALUATION) journalNumberJson(EVALUATION_DEFAULT) else null
}

/**
 * A text field shows nothing for "no value" — an em dash belongs on a label.
 *
 * Numeric primitives render through [formatJournalNumber] so an integral value
 * shows as `5`, never `5.0` — the server's REAL column round-trips integers as
 * Python floats, so a delta-delivered value arrives as `5.0` even though every
 * local write integer-collapses via `journalNumberJson`.
 */
private fun JsonElement?.asFieldText(): String = when {
    this == null || this is JsonNull -> ""
    this is JsonPrimitive && !isString -> doubleOrNull?.let(::formatJournalNumber) ?: content
    this is JsonPrimitive -> content
    else -> toString()
}

private const val DOT_ROW_DAYS = 7
