package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.data.trends.AdherenceWeek
import dev.jtiisto.wellness.core.data.trends.Best
import dev.jtiisto.wellness.core.data.trends.BestE1rm
import dev.jtiisto.wellness.core.data.trends.BestWeight
import dev.jtiisto.wellness.core.data.trends.ExerciseSummary
import dev.jtiisto.wellness.core.data.trends.FocusRow
import dev.jtiisto.wellness.core.data.trends.HrvBand
import dev.jtiisto.wellness.core.data.trends.LabObs
import dev.jtiisto.wellness.core.data.trends.LabPanel
import dev.jtiisto.wellness.core.data.trends.LabTest
import dev.jtiisto.wellness.core.data.trends.OverviewDto
import dev.jtiisto.wellness.core.data.trends.PrLatest
import dev.jtiisto.wellness.core.data.trends.PrSummary
import dev.jtiisto.wellness.core.data.trends.RecoveryDay
import dev.jtiisto.wellness.core.data.trends.RibbonDay
import dev.jtiisto.wellness.core.data.trends.Scan
import dev.jtiisto.wellness.core.data.trends.SlugTonnage
import dev.jtiisto.wellness.core.data.trends.Streaks
import dev.jtiisto.wellness.core.data.trends.TargetSegment
import dev.jtiisto.wellness.core.data.trends.TrackerDetailDto
import dev.jtiisto.wellness.core.data.trends.TrackerSummary
import dev.jtiisto.wellness.core.data.trends.TrackerValue
import dev.jtiisto.wellness.core.data.trends.TonnageTile
import dev.jtiisto.wellness.core.data.trends.TonnageWeek
import dev.jtiisto.wellness.core.data.trends.UsageWeek
import dev.jtiisto.wellness.core.data.trends.VolumeWeek
import dev.jtiisto.wellness.core.data.trends.Zone2Tile
import dev.jtiisto.wellness.core.data.trends.Zone2Week
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Terse builders for the DTOs the card models take.
 *
 * Every value here is invented; the `fixture-` prefix is the same rule the
 * golden fixtures follow, so nothing in a failure message can be mistaken for
 * something real.
 */

fun tracker(
    id: String = "fixture-tracker",
    name: String? = "Fixture Tracker",
    type: String? = "quantifiable",
    unit: String? = null,
    actionable: Boolean = true,
) = TrackerSummary(
    id = id,
    name = name,
    type = type,
    unit = unit,
    polarity = "positive",
    actionable = actionable,
    hasTarget = true,
    firstEntry = "2026-07-01",
    lastEntry = "2026-07-31",
)

fun trackerDetail(
    summary: TrackerSummary = tracker(),
    values: List<TrackerValue> = emptyList(),
    segments: List<TargetSegment> = emptyList(),
    adherence: List<AdherenceWeek> = emptyList(),
    usage: List<UsageWeek>? = null,
    streaks: Streaks = Streaks(current = 2, best = 9),
) = TrackerDetailDto(
    tracker = summary,
    values = values,
    targetSegments = segments,
    weeklyAdherence = adherence,
    streaks = streaks,
    weeklyUsage = usage,
)

fun value(date: String, raw: JsonElement?, completed: Int? = null) =
    TrackerValue(date = date, value = raw, completed = completed)

fun number(date: String, value: Double) = value(date, JsonPrimitive(value))

fun text(date: String, value: String) = value(date, JsonPrimitive(value))

fun segment(start: String, end: String, min: Double?, max: Double?) =
    TargetSegment(start = start, end = end, min = min, max = max)

fun adherenceWeek(
    weekStart: String,
    scheduled: Int = 7,
    met: Int = 4,
    partialDays: Int = 1,
    missed: Int = 2,
    rate: Double? = 0.5,
    paused: Boolean = false,
    partial: Boolean = false,
    metricKind: String = "adherence",
) = AdherenceWeek(
    weekStart = weekStart,
    partial = partial,
    paused = paused,
    scheduledDays = scheduled,
    met = met,
    partialDays = partialDays,
    missed = missed,
    rate = rate,
    metricKind = metricKind,
)

fun volumeWeek(
    weekStart: String,
    partial: Boolean = false,
    byExercise: List<SlugTonnage> = emptyList(),
) = VolumeWeek(
    weekStart = weekStart,
    partial = partial,
    tonnageKg = byExercise.sumOf { it.tonnageKg },
    hardSets = byExercise.sumOf { it.hardSets },
    byExercise = byExercise,
)

fun tonnage(slug: String, name: String, kg: Double, sets: Int = 4) =
    SlugTonnage(slug = slug, name = name, tonnageKg = kg, hardSets = sets)

fun recoveryDay(
    date: String,
    rhr: Double? = null,
    hrv: Double? = null,
    band: HrvBand? = null,
    sleepHours: Double? = null,
    sleepScore: Double? = null,
    napHours: Double? = null,
) = RecoveryDay(
    date = date,
    rhr = rhr,
    hrv = hrv,
    hrvBand = band,
    sleepHours = sleepHours,
    sleepScore = sleepScore,
    napHours = napHours,
)

fun band(low: Double, high: Double, floor: Double? = null) =
    HrvBand(low = low, high = high, lowFloor = floor)

fun scan(
    date: String,
    lean: Double? = null,
    fat: Double? = null,
    total: Double? = null,
    bodyFat: Double? = null,
    vat: Double? = null,
    agRatio: Double? = null,
    bmd: Double? = null,
    tScore: Double? = null,
) = Scan(
    date = date,
    leanKg = lean,
    fatKg = fat,
    totalKg = total,
    bodyFatPct = bodyFat,
    vatKg = vat,
    agRatio = agRatio,
    bmdTotal = bmd,
    tScoreTotal = tScore,
)

fun labObs(
    date: String,
    value: Double? = null,
    text: String? = null,
    prefix: String? = null,
    flag: String? = null,
    refLow: Double? = null,
    refHigh: Double? = null,
    refText: String? = null,
) = LabObs(
    date = date,
    value = value,
    text = text,
    prefix = prefix,
    flag = flag,
    refLow = refLow,
    refHigh = refHigh,
    refText = refText,
)

fun labTest(name: String, unit: String? = null, observations: List<LabObs>) =
    LabTest(name = name, unit = unit, observations = observations)

fun labPanel(name: String, tests: List<LabTest>) = LabPanel(name = name, tests = tests)

fun exerciseSummary(
    slug: String,
    name: String,
    assistance: Double? = null,
    plateau: Boolean = false,
    allTime: Boolean = true,
) = ExerciseSummary(
    slug = slug,
    name = name,
    equipment = if (assistance != null) "assisted" else "barbell",
    lastUsed = "2026-07-30",
    sessionCount = 12,
    unit = "kg",
    allTime = if (!allTime) {
        null
    } else {
        Best(
            bestWeight = BestWeight(weight = 70.0, reps = 3, date = "2026-07-30", assistance = assistance),
            bestE1rm = BestE1rm(
                value = 76.5,
                weight = 70.0,
                reps = 3,
                date = "2026-07-30",
                assistance = assistance,
            ),
        )
    },
    inRange = null,
    plateau = plateau,
)

fun prSummary(count: Int, withLatest: Boolean = false) = PrSummary(
    count30d = count,
    latest = if (!withLatest) {
        null
    } else {
        PrLatest(
            slug = "fixture-press",
            name = "Fixture Press",
            date = "2026-07-30",
            e1rm = 72.5,
            weight = 65.0,
            reps = 5,
            unit = "kg",
        )
    },
)

fun overviewDto(
    lastWeek: Double?,
    avg: Double?,
    soFar: Double,
    spark: List<Pair<Double, Double>> = listOf(0.0 to 0.0, 60.0 to 36.0),
) = OverviewDto(
    zone2 = Zone2Tile(
        thisWeekMin = soFar,
        lastWeekMin = lastWeek,
        fourWeekAvgMin = avg,
        sparkline = spark.mapIndexed { index, (planned, extra) ->
            Zone2Week(weekStart = "2026-07-%02d".format(index * 7 + 1), plannedMin = planned, extraMin = extra)
        },
    ),
    tonnage = TonnageTile(
        thisWeekKg = 3200.0,
        lastWeekKg = 11800.0,
        fourWeekAvgKg = 10000.0,
        sparkline = listOf(TonnageWeek(weekStart = "2026-07-01", tonnageKg = 9500.0)),
    ),
    adherenceFocus = listOf(
        FocusRow(
            trackerId = "fixture-tracker-alpha",
            name = "Fixture Tracker Alpha",
            metricKind = "adherence",
            rate = 0.42,
            dropping = true,
            ribbon = listOf(
                RibbonDay(date = "2026-07-29", status = "met"),
                RibbonDay(date = "2026-07-30", status = "missed"),
            ),
        ),
    ),
    prs = prSummary(count = 2, withLatest = true),
)
