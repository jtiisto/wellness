package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.data.trends.ExerciseDetailDto
import dev.jtiisto.wellness.core.data.trends.ExerciseSummary
import dev.jtiisto.wellness.core.data.trends.VolumeWeek

/** The Strength screen's three cards, as data. */

/** A legend swatch and what it stands for. */
data class LegendEntry(val label: String, val tone: PlotTone)

// ---- Progression -----------------------------------------------------------

data class ProgressionCardModel(
    val title: String,
    val subtitle: String,
    /** Null when the exercise has no sessions in range. */
    val plot: PlotModel?,
    val legend: List<LegendEntry>,
)

/**
 * Top-set weight and estimated 1RM over time, with RPE optionally riding a
 * fixed right-hand 5–10 axis.
 *
 * Assisted equipment gets its own subtitle: the plotted number is bodyweight
 * *minus* the assistance, so a rising line means less help, and calling that
 * "top set" without saying so reads backwards. The wording is a bounded
 * QUALIFIER, not a sentence — the section head's contract measures the sub
 * first and lets the title wrap, and the earlier "effective load (bw −
 * assist)" phrasing was wide enough to crush "Assisted Pull-Up" into a
 * one-letter-per-line column (device report, 2026-08-27).
 */
fun progressionCardModel(detail: ExerciseDetailDto, showRpe: Boolean): ProgressionCardModel {
    val assisted = detail.exercise.equipment == "assisted"
    val subtitle = if (assisted) {
        "bw − assist · e1RM (${detail.unit})"
    } else {
        "top set · e1RM (${detail.unit})"
    }
    val sessions = detail.sessions
    if (sessions.isEmpty()) {
        return ProgressionCardModel(detail.exercise.name, subtitle, null, emptyList())
    }

    val origin = sessions.first().date
    val x = { date: String -> dayIndex(date, origin).toDouble() }

    val topSet = sessions.map { XYPoint(x(it.date), it.topSet.weight, muted = it.offPlan) }
    val e1rm = sessions.map { XYPoint(x(it.date), it.e1rm, muted = it.offPlan) }
    val rpe = if (showRpe) {
        sessions.filter { it.topSetRpe != null }.map { XYPoint(x(it.date), it.topSetRpe!!) }
    } else {
        emptyList()
    }
    val ticks = dateTicks(sessions, { it.date }, origin)

    val plot = lineChartModel(
        primary = topSet,
        alt = e1rm,
        secondary = rpe,
        xTicks = ticks,
        anchorsOf = { xScale, _ ->
            mergeScrubAnchors(
                sessions.map { session ->
                    AnchorContribution(
                        key = session.date,
                        x = xScale(x(session.date)),
                        label = monthDay(session.date),
                        rows = buildList {
                            add(
                                TooltipRow(
                                    "top set",
                                    "${jsNumberString(session.topSet.weight)}×${session.topSet.reps}",
                                ),
                            )
                            add(TooltipRow("e1RM", jsNumberString(session.e1rm)))
                            session.topSetRpe?.let { add(TooltipRow("RPE", jsNumberString(it))) }
                            if (session.offPlan) add(TooltipRow("", "off-plan"))
                        },
                    )
                },
            )
        },
    )

    val legend = buildList {
        add(LegendEntry("top set", PlotTone.PRIMARY))
        add(LegendEntry("e1RM", PlotTone.ALT))
        if (showRpe) add(LegendEntry("RPE", PlotTone.SECONDARY))
        add(LegendEntry("○ off-plan", PlotTone.MUTED))
    }

    return ProgressionCardModel(detail.exercise.name, subtitle, plot, legend)
}

// ---- Weekly volume ---------------------------------------------------------

data class VolumeCardModel(val plot: PlotModel?, val legend: List<LegendEntry>)

private val STACK_TONES = listOf(PlotTone.STACK_0, PlotTone.STACK_1, PlotTone.STACK_2)

fun volumeCardModel(weeks: List<VolumeWeek>): VolumeCardModel {
    val stacks = foldVolumeStacks(weeks)
    val topSlugs = stacks.keys.filter { it != OTHER_KEY }
    val tones = topSlugs.mapIndexed { index, slug ->
        slug to STACK_TONES.getOrElse(index) { PlotTone.STACK_OTHER }
    }.toMap() + (OTHER_KEY to PlotTone.STACK_OTHER)

    val plot = barChartModel(
        weeks = stacks.weeks,
        keys = stacks.keys,
        tones = tones,
        partial = weeks.map { it.partial },
        yFormat = ::volumeYLabel,
        anchorsOf = { xScale, _ ->
            mergeScrubAnchors(
                stacks.weeks.mapIndexed { index, week ->
                    AnchorContribution(
                        key = week.weekStart,
                        x = xScale(index),
                        label = monthDay(week.weekStart),
                        rows = buildList {
                            for (key in stacks.keys) {
                                val value = week.values[key] ?: 0.0
                                if (value <= 0) continue
                                val name = if (key == OTHER_KEY) "other" else stacks.names[key] ?: key
                                add(TooltipRow(name, volumeYLabel(value)))
                            }
                            add(TooltipRow("total", volumeYLabel(weeks[index].tonnageKg)))
                        },
                    )
                },
            )
        },
    )

    val legend = topSlugs.mapIndexed { index, slug ->
        LegendEntry(stacks.names[slug] ?: slug, STACK_TONES.getOrElse(index) { PlotTone.STACK_OTHER })
    } + if (stacks.hasOther) listOf(LegendEntry("other", PlotTone.STACK_OTHER)) else emptyList()

    return VolumeCardModel(plot = plot, legend = legend)
}

// ---- PR board --------------------------------------------------------------

data class PrRowModel(
    val slug: String,
    val name: String,
    val plateau: Boolean,
    /** Null when the server sent no all-time block — the row shows its name alone. */
    val best: String?,
    val detail: String?,
)

fun prBoardRows(exercises: List<ExerciseSummary>): List<PrRowModel> = exercises.map { exercise ->
    val best = exercise.allTime?.bestE1rm
    PrRowModel(
        slug = exercise.slug,
        name = exercise.name,
        plateau = exercise.plateau,
        best = best?.let { "${jsNumberString(it.value)} ${exercise.unit}" },
        detail = best?.let {
            val assist = it.assistance?.let { value -> " (assist ${jsNumberString(value)})" }.orEmpty()
            "${jsNumberString(it.weight)}×${it.reps}$assist · ${it.date}"
        },
    )
}
