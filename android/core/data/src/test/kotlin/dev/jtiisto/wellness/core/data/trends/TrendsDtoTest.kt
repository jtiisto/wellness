package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Trends wire contract, against the fixtures in `testdata/golden/trends/`.
 *
 * Three things are worth more than the rest here, and each has burned a phase
 * somewhere: omitted keys carry defaults (`weekly_usage`, and the sleep
 * ledger's `as_of`/`tonight`/`gap`/`strain_partial` — the spec's Omitted-keys
 * table is the inventory), `completed` is an integer and not a boolean, and a
 * tracker value can be a *string* left over from the tracker's note era —
 * which a `Double?` property would turn into a failure of the entire payload,
 * not of the one row.
 */
class TrendsDtoTest {

    private val json = WellnessJson

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/trends/$name")) {
            "missing golden fixture golden/trends/$name"
        }.use { it.readBytes().decodeToString() }

    private fun <T> decode(name: String, deserializer: DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, fixture(name))

    // ---- overview ----------------------------------------------------------

    @Test
    @DisplayName("the overview payload decodes: both tiles, both focus kinds, the latest PR")
    fun overviewDecodes() {
        val overview = decode("overview.json", OverviewDto.serializer())

        assertEquals(42.0, overview.zone2.thisWeekMin)
        assertEquals(150.5, overview.zone2.lastWeekMin)
        assertEquals(120.0, overview.zone2.fourWeekAvgMin)
        assertEquals(4, overview.zone2.sparkline.size)
        assertEquals(30.5, overview.zone2.sparkline.first().extraMin)

        assertEquals(11800.5, overview.tonnage.lastWeekKg)
        assertEquals(10250.75, overview.tonnage.sparkline[1].tonnageKg)

        assertEquals(listOf("adherence", "avoidance"), overview.adherenceFocus.map { it.metricKind })
        assertEquals("fixture-tracker-alpha", overview.adherenceFocus.first().trackerId)
        assertTrue(overview.adherenceFocus.first().dropping)
        assertEquals(
            listOf("met", "partial", "missed", "off"),
            overview.adherenceFocus.first().ribbon.map { it.status },
        )

        assertEquals(2, overview.prs.count30d)
        assertEquals("fixture-press", overview.prs.latest?.slug)
        assertEquals(72.5, overview.prs.latest?.e1rm)
        assertEquals(5, overview.prs.latest?.reps)
    }

    @Test
    @DisplayName("an empty overview decodes with every nullable tile field null")
    fun emptyOverviewDecodes() {
        val overview = decode("overview-empty.json", OverviewDto.serializer())

        assertNull(overview.zone2.lastWeekMin)
        assertNull(overview.zone2.fourWeekAvgMin)
        assertNull(overview.tonnage.lastWeekKg)
        assertTrue(overview.adherenceFocus.isEmpty())
        assertEquals(0, overview.prs.count30d)
        assertNull(overview.prs.latest)
    }

    // ---- weight ------------------------------------------------------------

    @Test
    @DisplayName("weight decodes, and its unavailable form is a state rather than an error")
    fun weightDecodes() {
        val weight = decode("weight.json", WeightDto.serializer())
        assertTrue(weight.available)
        assertEquals(4, weight.series.size)
        // Integer and decimal wire forms in one series.
        assertEquals(80.0, weight.series.first().kg)
        assertEquals(79.8, weight.series[1].kg)

        val unavailable = decode("weight-unavailable.json", WeightDto.serializer())
        assertFalse(unavailable.available)
        assertTrue(unavailable.series.isEmpty())
    }

    // ---- strength ----------------------------------------------------------

    @Test
    @DisplayName("in_range is null on the All range and the UI reads that as 'no in-range block'")
    fun exercisesDecodeWithNullInRange() {
        val exercises = decode("strength-exercises.json", ExercisesDto.serializer()).exercises

        assertEquals(3, exercises.size)
        assertNull(exercises[0].inRange)
        assertNotNull(exercises[0].allTime)
        assertNotNull(exercises[1].inRange)
        assertEquals(20.0, exercises[1].allTime?.bestWeight?.assistance)
        assertNull(exercises[0].allTime?.bestWeight?.assistance)
        assertEquals("assisted", exercises[1].equipment)
        assertNull(exercises[2].equipment)
        assertTrue(exercises[1].plateau)
    }

    @Test
    @DisplayName("an exercise detail decodes sessions with and without RPE")
    fun exerciseDetailDecodes() {
        val detail = decode("strength-exercise.json", ExerciseDetailDto.serializer())

        assertEquals("fixture-press", detail.exercise.slug)
        assertEquals("fixture-upper", detail.exercise.category)
        assertEquals(3, detail.sessions.size)
        assertEquals(8.0, detail.sessions[0].topSetRpe)
        assertNull(detail.sessions[1].topSetRpe)
        assertTrue(detail.sessions[1].offPlan)
        assertEquals(60.0, detail.sessions[0].topSet.weight)
        assertEquals(5, detail.sessions[0].topSet.reps)
    }

    @Test
    @DisplayName("weekly volume decodes its per-exercise breakdown and the partial week")
    fun volumeDecodes() {
        val weeks = decode("strength-volume.json", VolumeDto.serializer()).weeks

        assertEquals(3, weeks.size)
        assertEquals(4, weeks[0].byExercise.size)
        assertEquals(4000.0, weeks[0].byExercise.first().tonnageKg)
        assertEquals(22, weeks[0].hardSets)
        assertFalse(weeks[0].partial)
        assertTrue(weeks[2].partial)
    }

    // ---- cardio ------------------------------------------------------------

    @Test
    @DisplayName("cardio decodes avg_hr from both integer and decimal wire forms")
    fun cardioDecodes() {
        val cardio = decode("cardio.json", CardioDto.serializer())

        assertEquals(3, cardio.weeks.size)
        assertEquals(30.5, cardio.weeks[0].zone2ExtraMin)
        assertEquals(listOf(1, 0, 2), cardio.weeks.map { it.intervalSessions })
        // The whole reason avg_hr is a Double: the wire flips freely.
        assertEquals(132.0, cardio.steadySessions[0].avgHr)
        assertEquals(128.5, cardio.steadySessions[1].avgHr)
        assertTrue(cardio.steadySessions[1].offPlan)

        val empty = decode("cardio-empty.json", CardioDto.serializer())
        assertTrue(empty.weeks.isEmpty())
        assertTrue(empty.steadySessions.isEmpty())
    }

    // ---- journal -----------------------------------------------------------

    @Test
    @DisplayName("the tracker list decodes, nullable metadata included")
    fun trackersDecode() {
        val trackers = decode("journal-trackers.json", TrackersDto.serializer()).trackers

        assertEquals(2, trackers.size)
        assertEquals("ml", trackers[0].unit)
        assertNull(trackers[1].unit)
        assertTrue(trackers[0].actionable)
        assertFalse(trackers[1].actionable)
        assertTrue(trackers[0].hasTarget)
    }

    @Test
    @DisplayName("weekly_usage is ABSENT for an actionable tracker and decodes to null")
    fun weeklyUsageAbsent() {
        // The API's only omitted key. A non-default property here would fail
        // the whole payload for every actionable tracker there is.
        assertFalse(fixture("journal-tracker-actionable.json").contains("weekly_usage"))

        val detail = decode("journal-tracker-actionable.json", TrackerDetailDto.serializer())
        assertNull(detail.weeklyUsage)
    }

    @Test
    @DisplayName("weekly_usage is PRESENT for a neutral tracker and decodes to its weeks")
    fun weeklyUsagePresent() {
        val detail = decode("journal-tracker-neutral.json", TrackerDetailDto.serializer())

        assertEquals(3, detail.weeklyUsage?.size)
        assertEquals(listOf(1, 0, 1), detail.weeklyUsage?.map { it.count })
        assertEquals(true, detail.weeklyUsage?.last()?.partial)
    }

    @Test
    @DisplayName("completed is 1 / 0 / null — an integer, never a boolean")
    fun completedIsAnInteger() {
        val values = decode("journal-tracker-actionable.json", TrackerDetailDto.serializer()).values

        assertEquals(listOf(1, 0, null, 1, null), values.map { it.completed })
    }

    @Test
    @DisplayName("a mixed numeric / note-string values array decodes whole")
    fun mixedTrackerValuesDecode() {
        val values = decode("journal-tracker-actionable.json", TrackerDetailDto.serializer()).values

        assertEquals(5, values.size)
        assertEquals(2000.0, (values[0].value as JsonPrimitive).doubleOrNull)
        assertEquals(1750.5, (values[1].value as JsonPrimitive).doubleOrNull)
        assertNull(values[2].value)
        // A number that arrived as a string, and free text — both survive decode
        // because the property is a JsonElement.
        assertEquals("2100", (values[3].value as JsonPrimitive).content)
        assertEquals("felt fine, forgot the bottle", (values[4].value as JsonPrimitive).content)
    }

    @Test
    @DisplayName("target segments keep one-sided bounds, and a paused week keeps a null rate")
    fun targetsAndAdherenceDecode() {
        val detail = decode("journal-tracker-actionable.json", TrackerDetailDto.serializer())

        assertEquals(2, detail.targetSegments.size)
        assertEquals(1800.0, detail.targetSegments[0].min)
        assertEquals(2400.0, detail.targetSegments[0].max)
        assertNull(detail.targetSegments[1].max)

        assertEquals(3, detail.weeklyAdherence.size)
        assertEquals(0.571, detail.weeklyAdherence[0].rate)
        assertTrue(detail.weeklyAdherence[1].paused)
        assertNull(detail.weeklyAdherence[1].rate)
        assertEquals(1.0, detail.weeklyAdherence[2].rate)
        assertTrue(detail.weeklyAdherence[2].partial)
        assertEquals(3, detail.streaks.current)
        assertEquals(11, detail.streaks.best)
    }

    // ---- health ------------------------------------------------------------

    @Test
    @DisplayName("recovery decodes the nested band, a null low_floor, and an all-null day")
    fun recoveryDecodes() {
        val recovery = decode("health-recovery.json", RecoveryDto.serializer())

        assertTrue(recovery.available)
        assertEquals(4, recovery.days.size)
        assertEquals(48.0, recovery.days[0].rhr)
        assertEquals(49.5, recovery.days[1].rhr)
        assertEquals(28.0, recovery.days[0].hrvBand?.low)
        assertEquals(26.0, recovery.days[0].hrvBand?.lowFloor)
        assertNull(recovery.days[3].hrvBand?.lowFloor)
        assertNull(recovery.days[2].hrvBand)
        assertNull(recovery.days[2].hrv)
        assertEquals(81.0, recovery.days[0].sleepScore)
        assertEquals(64.5, recovery.days[1].sleepScore)

        val unavailable = decode("health-recovery-unavailable.json", RecoveryDto.serializer())
        assertFalse(unavailable.available)
        assertTrue(unavailable.days.isEmpty())
    }

    @Test
    @DisplayName("the sleep ledger decodes: as_of, tonight, and a gap night among four without one")
    fun sleepDecodes() {
        val sleep = decode("health-sleep.json", SleepDebtDto.serializer())

        assertTrue(sleep.available)
        assertEquals("2030-01-25", sleep.asOf)
        assertEquals(5, sleep.days.size)

        val tonight = requireNotNull(sleep.tonight)
        assertEquals("2030-01-26", tonight.date)
        assertEquals(495.0, tonight.needMin)
        assertEquals(41.5, tonight.debtMin)
        assertEquals(8.0, tonight.strainEst)
        // Always true on the wire; the default exists to say what an ABSENT key
        // would mean, not to describe what the server sends.
        assertTrue(tonight.strainPartial)

        val first = sleep.days.first()
        assertEquals("2030-01-21", first.date)
        assertEquals(480.0, first.needMin)
        assertEquals(400.5, first.sleptMin)
        assertEquals(0.0, first.debtMin)
        assertEquals(9.4, first.strainEst)
    }

    @Test
    @DisplayName("gap defaults to false where the key is omitted, and is true only where it is present")
    fun sleepGapDefaultsFalse() {
        // The wire omits `gap` on every ordinary night — a non-default property
        // would fail the whole payload on the first one.
        val fixture = fixture("health-sleep.json")
        assertEquals(1, Regex("\"gap\"").findAll(fixture).count())

        val days = decode("health-sleep.json", SleepDebtDto.serializer()).days
        assertEquals(listOf(false, false, true, false, false), days.map { it.gap })
        // The reset night's debt is zero BECAUSE the ledger restarted, which is
        // the whole reason the flag has to survive the decode.
        assertEquals(0.0, days[2].debtMin)
    }

    @Test
    @DisplayName("every sleep measurement decodes from an integer AND a decimal wire form")
    fun sleepNumbersArriveBothWays() {
        val days = decode("health-sleep.json", SleepDebtDto.serializer()).days

        assertEquals(listOf(480.0, 510.5, 468.0, 455.5, 472.0), days.map { it.needMin })
        assertEquals(listOf(400.5, 465.0, 512.5, 430.0, 388.5), days.map { it.sleptMin })
        assertEquals(listOf(0.0, 42.5, 0.0, 0.0, 12.5), days.map { it.debtMin })
        assertEquals(listOf(9.4, 12.0, 6.5, 4.0, 7.5), days.map { it.strainEst })
    }

    @Test
    @DisplayName("a tonight without strain_partial decodes to false — the default is load-bearing")
    fun sleepTonightStrainPartialDefaultsFalse() {
        // No fixture carries this shape (the server always sends true today),
        // so the omitted-key contract is pinned inline: dropping the property
        // default would fail THIS decode, not just re-encode symmetry.
        val tonight = json.decodeFromString(
            SleepTonight.serializer(),
            """{"date":"2030-01-26","need_min":480,"debt_min":0,"strain_est":3.5}""",
        )
        assertFalse(tonight.strainPartial)
    }

    @Test
    @DisplayName("an unavailable ledger has no as_of and no tonight — both keys are simply absent")
    fun sleepUnavailableOmitsEverything() {
        val payload = fixture("health-sleep-unavailable.json")
        assertFalse(payload.contains("as_of"))
        assertFalse(payload.contains("tonight"))

        val sleep = decode("health-sleep-unavailable.json", SleepDebtDto.serializer())
        assertFalse(sleep.available)
        assertNull(sleep.asOf)
        assertNull(sleep.tonight)
        assertTrue(sleep.days.isEmpty())
    }

    @Test
    @DisplayName("composition decodes all eight nullable scan metrics")
    fun compositionDecodes() {
        val composition = decode("health-composition.json", CompositionDto.serializer())

        assertEquals(3, composition.scans.size)
        val first = composition.scans.first()
        assertEquals(58.2, first.leanKg)
        assertEquals(20.4, first.fatKg)
        assertEquals(80.1, first.totalKg)
        assertEquals(25.5, first.bodyFatPct)
        assertEquals(0.5, first.vatKg)
        assertEquals(1.02, first.agRatio)
        assertEquals(1.24, first.bmdTotal)
        assertEquals(0.7, first.tScoreTotal)

        assertNull(composition.scans[1].tScoreTotal)
        assertNull(composition.scans[2].vatKg)
        assertNull(composition.scans[2].bmdTotal)

        assertFalse(decode("health-composition-unavailable.json", CompositionDto.serializer()).available)
    }

    @Test
    @DisplayName("labs decode text-only, prefixed and flagged observations")
    fun labsDecode() {
        val labs = decode("health-labs.json", LabsDto.serializer())

        assertEquals(listOf("Fixture Panel One", "Fixture Panel Two"), labs.panels.map { it.name })
        val tests = labs.panels.first().tests
        assertEquals(3, tests.size)

        assertEquals("ng/mL", tests[0].unit)
        assertEquals("L", tests[0].observations[1].flag)
        assertEquals(30.0, tests[0].observations[0].refLow)

        // A result that is words, not a number.
        assertNull(tests[1].unit)
        assertNull(tests[1].observations.single().value)
        assertEquals("Not Detected", tests[1].observations.single().text)

        assertEquals("<", tests[2].observations[0].prefix)
        assertEquals("<10", tests[2].observations[0].refText)
        assertNull(tests[2].observations[0].refLow)
        assertEquals("H", tests[2].observations[2].flag)

        assertFalse(decode("health-labs-unavailable.json", LabsDto.serializer()).available)
    }

    @Test
    @DisplayName("an unknown server field never fails a decode")
    fun unknownFieldsAreIgnored() {
        val payload = """{"available":true,"series":[{"date":"2026-07-01","kg":80,"trend":"up"}],"note":"new"}"""
        val weight = json.decodeFromString(WeightDto.serializer(), payload)

        assertEquals(1, weight.series.size)
        assertEquals(80.0, weight.series.single().kg)
    }
}
