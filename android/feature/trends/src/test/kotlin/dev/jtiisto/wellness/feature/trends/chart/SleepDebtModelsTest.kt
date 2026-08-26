package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.data.trends.SleepDebtDay
import dev.jtiisto.wellness.core.data.trends.SleepTonight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The sleep-need panel's geometry.
 *
 * Two things here are worth more than the coordinates: the y floor shared with
 * `SleepCard` — without it the worst week of the year draws exactly like the
 * best — and the split at a reset, which is the difference between "a night's
 * sleep cleared the debt" and "the watch recorded nothing, so the ledger
 * restarted". Both are invisible in a diff and obvious on a device, which is
 * precisely the kind of rule that has to be pinned here.
 *
 * All values are invented; dates follow the far-future fixture convention.
 */
class SleepDebtModelsTest {

    @Test
    @DisplayName("an empty window draws no panel at all")
    fun emptyDaysGiveNoSection() {
        assertNull(sleepDebtSection(emptyList(), tonight()))
    }

    // ---- need vs slept -----------------------------------------------------

    @Test
    @DisplayName("bars sit on the index closure's own x, half a slot in from each edge")
    fun barsUseTheIndexClosure() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))

        // Five daily slots across [40, 350] with a half-slot pad at each end
        // gives 62 logical units per day and centres at 71, 133, 195, 257, 319;
        // the 10-wide bar is drawn from half its width left of that.
        val xs = section.needPlot.rects.map { it.x }
        assertEquals(listOf(66.0, 128.0, 190.0, 252.0, 314.0), xs)
        assertEquals(listOf(10.0), section.needPlot.rects.map { it.w }.distinct())
    }

    @Test
    @DisplayName("the axis never drops below nine hours, so a short week keeps looking short")
    fun yFloorIsNineHours() {
        val section = requireNotNull(
            sleepDebtSection(
                listOf(
                    night("2030-01-21", slept = 300.0, need = 480.0),
                    night("2030-01-22", slept = 360.0, need = 480.0),
                ),
                null,
            ),
        )

        // The plot runs 148 units over a domain of [0, 9 * 1.05], so a six-hour
        // night fills 94 of them. Refitted to its own six-hour maximum it would
        // have drawn at 141 — a five-hour night looking like a full one.
        val tallest = section.needPlot.rects.maxByOrNull { it.h }!!
        assertEquals(6.0 / (9.0 * 1.05) * 148.0, tallest.h, 0.05)
    }

    @Test
    @DisplayName("a night past the floor lifts the axis, and the need line rides the same scale")
    fun yFloorGivesWayToARealMaximum() {
        val section = requireNotNull(
            sleepDebtSection(
                listOf(
                    night("2030-01-21", slept = 600.0, need = 480.0),
                    night("2030-01-22", slept = 420.0, need = 480.0),
                ),
                null,
            ),
        )

        val tallest = section.needPlot.rects.maxByOrNull { it.h }!!
        assertEquals(10.0 / (10.0 * 1.05) * 148.0, tallest.h, 0.05)
    }

    @Test
    @DisplayName("the need is a polyline in the second tone — a target, not a bar")
    fun needIsASecondarySeries() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))

        val line = section.needPlot.lines.single()
        assertEquals(PlotTone.SECONDARY, line.tone)
        assertEquals(5, line.points.size)
        // No 8h guide: the need line IS the guide on this chart, and a second
        // horizontal reference would invite reading one against the other.
        assertTrue(section.needPlot.guides.isEmpty())
    }

    @Test
    @DisplayName("a single night draws its bar and no line — one point is not a series")
    fun oneNightDrawsNoLine() {
        val section = requireNotNull(
            sleepDebtSection(listOf(night("2030-01-21")), tonight()),
        )

        assertEquals(1, section.needPlot.rects.size)
        assertTrue(section.needPlot.lines.isEmpty())
        assertNull(section.debtPlot, "a debt of one point is a dot, not a history")
    }

    // ---- debt --------------------------------------------------------------

    @Test
    @DisplayName("the debt line breaks at a reset rather than sloping down into it")
    fun debtSplitsAtAGap() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))
        val debt = requireNotNull(section.debtPlot)

        // Two runs: the two nights before the reset, and the three from it on.
        // Drawn as one line, the segment falling into the gap day would claim a
        // night's sleep paid the balance off.
        assertEquals(2, debt.lines.size)
        assertEquals(listOf(2, 3), debt.lines.map { it.points.size })
        assertEquals(listOf(PlotTone.SCAN, PlotTone.SCAN), debt.lines.map { it.tone })
    }

    @Test
    @DisplayName("each reset carries an open ring, and nothing else does")
    fun gapDaysGetAWarnRing() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))
        val debt = requireNotNull(section.debtPlot)

        val ring = debt.dots.single()
        assertEquals(PlotTone.WARN, ring.tone)
        // The third night, at the third slot centre.
        assertEquals(195.0, ring.x, 0.001)
    }

    @Test
    @DisplayName("a reset on the FIRST night starts its run rather than opening an empty one")
    fun leadingGapStartsTheOnlyRun() {
        val section = requireNotNull(
            sleepDebtSection(
                listOf(
                    night("2030-01-21", debt = 0.0, gap = true),
                    night("2030-01-22", debt = 30.0),
                    night("2030-01-23", debt = 45.0),
                ),
                null,
            ),
        )
        val debt = requireNotNull(section.debtPlot)

        assertEquals(1, debt.lines.size)
        assertEquals(3, debt.lines.single().points.size)
        assertEquals(1, debt.dots.size)
    }

    @Test
    @DisplayName("a debt-free stretch keeps a one-hour axis instead of being scaled to fill the plot")
    fun debtAxisFloorsAtAnHour() {
        val section = requireNotNull(
            sleepDebtSection(
                listOf(night("2030-01-21", debt = 0.0), night("2030-01-22", debt = 0.0)),
                null,
            ),
        )
        val debt = requireNotNull(section.debtPlot)

        // A flat zero line sits on the baseline, not halfway up a plot rescaled
        // to nothing: the best fortnight of the year must not draw like the
        // worst. 120 tall less a 22 bottom margin puts the baseline at 98.
        assertEquals(listOf(98.0), debt.lines.single().points.map { it.y }.distinct())
    }

    // ---- anchors, legends, head --------------------------------------------

    @Test
    @DisplayName("one anchor per wake date, shared by both charts, so a scrub reads the same day")
    fun anchorsAreSharedAndOnePerDate() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))

        assertEquals(
            listOf("2030-01-21", "2030-01-22", "2030-01-23", "2030-01-24", "2030-01-25"),
            section.needPlot.anchors.map { it.key },
        )
        assertEquals(section.needPlot.anchors, requireNotNull(section.debtPlot).anchors)
        assertEquals("01-21", section.needPlot.anchors.first().label)
    }

    @Test
    @DisplayName("a tooltip row is a duration in h:mm — 0.18 h is not an answer anyone acts on")
    fun anchorRowsAreDurations() {
        val section = requireNotNull(
            sleepDebtSection(
                listOf(
                    night("2030-01-21", need = 459.0, slept = 437.5, debt = 11.0),
                    night("2030-01-22"),
                ),
                null,
            ),
        )

        assertEquals(
            listOf("slept" to "7:18", "need" to "7:39", "debt" to "0:11"),
            section.needPlot.anchors.first().rows.map { it.label to it.value },
        )
    }

    @Test
    @DisplayName("a reset night says so in its tooltip, under the three durations")
    fun gapAnchorCarriesTheResetRow() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))

        val gapAnchor = section.needPlot.anchors[2]
        assertEquals("2030-01-23", gapAnchor.key)
        assertEquals(
            listOf("slept", "need", "debt", "reset"),
            gapAnchor.rows.map { it.label },
        )
        assertEquals("missing night", gapAnchor.rows.last().value)
        // Every other night says only its three numbers.
        assertEquals(3, section.needPlot.anchors[3].rows.size)
    }

    @Test
    @DisplayName("both charts carry a legend — a plate with no key identifies nothing")
    fun bothChartsAreKeyed() {
        val section = requireNotNull(sleepDebtSection(fiveNights(), null))

        assertEquals(listOf("slept", "need"), section.needLegend.map { it.label })
        assertEquals(listOf(PlotTone.PRIMARY, PlotTone.SECONDARY), section.needLegend.map { it.tone })
        assertEquals(listOf("debt", "reset"), section.debtLegend.map { it.label })
        assertEquals(listOf(PlotTone.SCAN, PlotTone.WARN), section.debtLegend.map { it.tone })
    }

    @Test
    @DisplayName("the section head trails tonight's need, and says nothing when there is none")
    fun latestComesFromTonight() {
        assertEquals("need 8:15", requireNotNull(sleepDebtSection(fiveNights(), tonight())).latest)
        assertNull(requireNotNull(sleepDebtSection(fiveNights(), null)).latest)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun fiveNights(): List<SleepDebtDay> = listOf(
        night("2030-01-21", debt = 0.0),
        night("2030-01-22", debt = 42.5),
        night("2030-01-23", debt = 0.0, gap = true),
        night("2030-01-24", debt = 18.0),
        night("2030-01-25", debt = 12.5),
    )

    private fun night(
        date: String,
        need: Double = 480.0,
        slept: Double = 420.0,
        debt: Double = 0.0,
        strain: Double = 6.5,
        gap: Boolean = false,
    ) = SleepDebtDay(
        date = date,
        needMin = need,
        sleptMin = slept,
        debtMin = debt,
        strainEst = strain,
        gap = gap,
    )

    private fun tonight() = SleepTonight(
        date = "2030-01-26",
        needMin = 495.0,
        debtMin = 41.5,
        strainEst = 8.0,
        strainPartial = true,
    )
}
