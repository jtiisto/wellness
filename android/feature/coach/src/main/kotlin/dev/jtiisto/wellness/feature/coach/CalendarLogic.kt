package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.data.coach.getWorkoutStatus
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month-grid calendar, derived. A port of `CalendarPicker.js` minus its DOM.
 *
 * The grid is always six rows of seven — 42 cells, padded from the neighbouring
 * months — so the popup never changes height as you page through the year.
 * Weeks start on Sunday (PWA parity); only the *names* are localized.
 */

/** The whole popup's state. [canGoPrev] false means the floor has been reached. */
data class CalendarState(
    val viewMonth: YearMonth = YearMonth.of(1970, 1),
    val monthCaption: String = "",
    val weekdayLabels: List<String> = emptyList(),
    val cells: List<CalendarCell> = emptyList(),
    val canGoPrev: Boolean = true,
    val earliestDate: DateString? = null,
)

/** One day cell. [inViewMonth] false is the greyed padding from a neighbour month. */
data class CalendarCell(
    val date: DateString,
    val dayOfMonth: Int,
    val inViewMonth: Boolean,
    val isSelected: Boolean,
    val isToday: Boolean,
    val status: WorkoutStatus?,
    val enabled: Boolean,
)

/** Cells per grid: six weeks, so a 31-day month starting on Saturday still fits. */
private const val GRID_CELLS = 42

fun buildCalendarState(
    viewMonth: YearMonth,
    selectedDate: DateString,
    today: DateString,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    earliestDate: DateString?,
    locale: Locale = Locale.getDefault(),
): CalendarState {
    val firstOfMonth = viewMonth.atDay(1)
    // DayOfWeek numbers Monday 1..Sunday 7; `% 7` turns that into the
    // Sunday-first column index the grid is laid out in.
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value % 7).toLong())

    val cells = (0 until GRID_CELLS).map { offset ->
        val day = gridStart.plusDays(offset.toLong())
        val date = day.toString()
        CalendarCell(
            date = date,
            dayOfMonth = day.dayOfMonth,
            inViewMonth = YearMonth.from(day) == viewMonth,
            isSelected = date == selectedDate,
            isToday = date == today,
            status = getWorkoutStatus(date, plans, logs, today),
            enabled = canSelectDate(date, earliestDate),
        )
    }

    return CalendarState(
        viewMonth = viewMonth,
        monthCaption = viewMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
        weekdayLabels = (0L until 7L).map {
            DayOfWeek.SUNDAY.plus(it).getDisplayName(TextStyle.SHORT, locale)
        },
        cells = cells,
        canGoPrev = previousMonthOrNull(viewMonth, earliestDate) != null,
        earliestDate = earliestDate,
    )
}

/**
 * Selection is refused below the server's window start. There is no data down
 * there — the prune has removed it — and a write to such a day could not be
 * uploaded either.
 */
fun canSelectDate(date: DateString, earliestDate: DateString?): Boolean =
    earliestDate == null || date >= earliestDate

/**
 * The previous month, or null when paging back is refused.
 *
 * The test is on the target month's LAST day, not its first: a floor that falls
 * mid-month still leaves days worth reaching, so that month must stay
 * navigable. There is deliberately no upper bound — future plans are worth
 * browsing to.
 */
fun previousMonthOrNull(viewMonth: YearMonth, earliestDate: DateString?): YearMonth? {
    val target = viewMonth.minusMonths(1)
    if (earliestDate != null && target.atEndOfMonth().toString() < earliestDate) return null
    return target
}

/** The month a date belongs to — what the view re-homes to when the date changes. */
fun monthOf(date: DateString): YearMonth = YearMonth.from(LocalDate.parse(date))
