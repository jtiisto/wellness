package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The logical → pixel transform, the anchor merge, and the two shared chart
 * builders.
 *
 * The transform is the one place a device dimension enters the port, and the
 * rule it has to hold is narrow: **positions and extents scale together**. A
 * bar whose centre moved but whose width did not would still look like a chart,
 * which is exactly why it needs a test rather than an eye.
 */
class PlotModelTest {

    // ---- transform ---------------------------------------------------------

    @Test
    @DisplayName("the scale factor is the width in pixels over the authored 360")
    fun scaleFactor() {
        assertEquals(0.5f, LogicalScale(180f).factor)
        assertEquals(1f, LogicalScale(360f).factor)
        assertEquals(2f, LogicalScale(720f).factor)
    }

    @Test
    @DisplayName("height scales by the same factor, so the aspect ratio is preserved")
    fun heightScales() {
        assertEquals(100f, LogicalScale(360f).heightPx(100.0))
        assertEquals(50f, LogicalScale(180f).heightPx(100.0))
        assertEquals(400f, LogicalScale(720f).heightPx(200.0))
    }

    /**
     * Geometry scales; ink does not. This covers the first half — the second is
     * [radiiAreInkNotGeometry], which the type system enforces rather than the
     * arithmetic.
     */
    @Test
    @DisplayName("a bar's centre AND its width scale together")
    fun barExtentsScaleWithPositions() {
        val model = requireNotNull(
            barChartModel(
                weeks = (0 until 4).map { StackedWeek("2026-07-0${it + 1}", mapOf("a" to 10.0)) },
                keys = listOf("a"),
                tones = mapOf("a" to PlotTone.PRIMARY),
            ),
        )
        val bar = model.rects.first()

        for (widthPx in listOf(180f, 360f, 720f)) {
            val scale = LogicalScale(widthPx)
            val factor = widthPx / 360f
            assertEquals(bar.x.toFloat() * factor, scale.px(bar.x), "left edge at $widthPx")
            assertEquals(bar.w.toFloat() * factor, scale.px(bar.w), "width at $widthPx")
            assertEquals(bar.h.toFloat() * factor, scale.px(bar.h), "height at $widthPx")
            // The centre follows from the two, which is the property that matters.
            assertEquals(
                scale.px(bar.x) + scale.px(bar.w) / 2f,
                scale.px(bar.x + bar.w / 2),
                0.001f,
                "centre at $widthPx",
            )
        }
    }

    @Test
    @DisplayName("radii are ink: dp values the width factor cannot reach")
    fun radiiAreInkNotGeometry() {
        val model = requireNotNull(
            lineChartModel(
                primary = listOf(XYPoint(0.0, 1.0), XYPoint(1.0, 2.0)),
                alt = listOf(XYPoint(0.0, 3.0), XYPoint(1.0, 4.0)),
            ),
        )

        // Dp, not Double — and `LogicalScale.px` takes a Double, so a radius
        // put through the transform does not compile. That is a stronger
        // guarantee than any assertion could be about a Canvas this project has
        // no rig to instantiate: on a 720px chart the dots would have doubled.
        assertEquals(3.dp, model.dots.first { it.tone == PlotTone.PRIMARY }.radius)
        assertEquals(2.5.dp, model.dots.first { it.tone == PlotTone.ALT }.radius)

        val bars = requireNotNull(
            barChartModel(
                weeks = listOf(StackedWeek("2026-07-06", mapOf("a" to 1.0))),
                keys = listOf("a"),
                tones = mapOf("a" to PlotTone.PRIMARY),
            ),
        )
        assertEquals(1.5.dp, bars.rects.single().radius)
    }

    @Test
    @DisplayName("an anchor whose contributions are all unlabelled falls back to its key")
    fun anchorLabelFallsBackToTheKey() {
        val merged = mergeScrubAnchors(
            listOf(AnchorContribution("2026-07-05", 100.0, "", listOf(TooltipRow("kg", "80")))),
        )

        // Better a date than a crash: every chart today labels its anchors, and
        // the first one that forgets should still scrub.
        assertEquals("2026-07-05", merged.single().label)
    }

    @Test
    @DisplayName("every path vertex scales, so a line keeps its shape at any width")
    fun pathVerticesScale() {
        val model = requireNotNull(
            lineChartModel(
                primary = listOf(XYPoint(0.0, 10.0), XYPoint(5.0, 20.0), XYPoint(10.0, 15.0)),
            ),
        )
        val vertices = model.lines.single().points

        val at360 = vertices.map { LogicalScale(360f).px(it.x) to LogicalScale(360f).px(it.y) }
        val at720 = vertices.map { LogicalScale(720f).px(it.x) to LogicalScale(720f).px(it.y) }
        assertEquals(at360.map { it.first * 2 to it.second * 2 }, at720)
    }

    @Test
    @DisplayName("anchors reach the scrub state in pixels, in order, one per position")
    fun anchorsAreScaled() {
        val anchors = listOf(
            ScrubAnchor("2026-07-01", 40.0, "07-01", emptyList()),
            ScrubAnchor("2026-07-02", 180.0, "07-02", emptyList()),
            ScrubAnchor("2026-07-03", 350.0, "07-03", emptyList()),
        )

        assertTrue(LogicalScale(360f).anchorsPx(anchors).contentEquals(floatArrayOf(40f, 180f, 350f)))
        assertTrue(LogicalScale(720f).anchorsPx(anchors).contentEquals(floatArrayOf(80f, 360f, 700f)))
        assertTrue(LogicalScale(180f).anchorsPx(anchors).contentEquals(floatArrayOf(20f, 90f, 175f)))
    }

    // ---- anchor merge --------------------------------------------------------

    @Test
    @DisplayName("contributions on the same key become ONE anchor carrying every row")
    fun anchorsMergeByKey() {
        val merged = mergeScrubAnchors(
            listOf(
                AnchorContribution("2026-07-05", 100.0, "07-05", listOf(TooltipRow("kg", "80"))),
                AnchorContribution("2026-07-05", 100.0, "07-05", listOf(TooltipRow("DEXA", "79.4 kg"))),
            ),
        )

        // Two anchors on one x would strand the second: the scrub state resolves
        // a tie to the lowest index and the DEXA row would be unreachable.
        assertEquals(1, merged.size)
        assertEquals(listOf("kg", "DEXA"), merged.single().rows.map { it.label })
        assertEquals(100.0, merged.single().x)
    }

    @Test
    @DisplayName("anchors come back in lexical key order, which for dates is chronological")
    fun anchorsSortByKey() {
        val merged = mergeScrubAnchors(
            listOf(
                AnchorContribution("2026-07-10", 300.0, "07-10", emptyList()),
                AnchorContribution("2026-06-30", 40.0, "06-30", emptyList()),
                AnchorContribution("2026-07-02", 90.0, "07-02", emptyList()),
            ),
        )

        assertEquals(listOf("2026-06-30", "2026-07-02", "2026-07-10"), merged.map { it.key })
        assertEquals(listOf(40.0, 90.0, 300.0), merged.map { it.x })
    }

    @Test
    @DisplayName("every merged anchor has a distinct key")
    fun anchorKeysAreUnique() {
        val merged = mergeScrubAnchors(
            List(6) { AnchorContribution("2026-07-0${it % 3 + 1}", it * 10.0, "d", emptyList()) },
        )

        assertEquals(3, merged.size)
        assertEquals(merged.size, merged.map { it.key }.toSet().size)
    }

    @Test
    @DisplayName("an anchor takes the first label offered that says anything")
    fun anchorLabelFallsThrough() {
        val merged = mergeScrubAnchors(
            listOf(
                AnchorContribution("2026-07-05", 100.0, "", emptyList()),
                AnchorContribution("2026-07-05", 100.0, "07-05", emptyList()),
            ),
        )

        assertEquals("07-05", merged.single().label)
    }

    @Test
    @DisplayName("no contributions, no anchors")
    fun anchorsEmpty() {
        assertTrue(mergeScrubAnchors(emptyList()).isEmpty())
    }

    // ---- the JS `||` fall-through ---------------------------------------------

    @Test
    @DisplayName("firstNonZero falls through zeros and lands on the last candidate")
    fun firstNonZeroFallThrough() {
        assertEquals(4.0, firstNonZero(4.0, 2.0, 1.0))
        assertEquals(2.0, firstNonZero(0.0, 2.0, 1.0))
        assertEquals(1.0, firstNonZero(0.0, 0.0, 1.0))
        // A flat series at zero has nothing to borrow from, which is the case
        // the final literal exists for.
        assertEquals(0.5, firstNonZero(0.0, 0.5))
    }

    // ---- line chart ------------------------------------------------------------

    @Test
    @DisplayName("nothing to plot, no chart — the caller shows its empty text instead")
    fun lineChartEmpty() {
        assertNull(lineChartModel(primary = emptyList()))
    }

    @Test
    @DisplayName("the x domain spans the points AND the outer ticks")
    fun lineChartDomainIncludesTicks() {
        val ticks = listOf(DateTick(0.0, "07-01"), DateTick(30.0, "07-31"))
        val model = requireNotNull(
            lineChartModel(
                primary = listOf(XYPoint(10.0, 5.0), XYPoint(20.0, 8.0)),
                xTicks = ticks,
            ),
        )

        // A tick outside the plot it labels would sit off the axis; the domain
        // widens to hold both ends.
        val tickLabels = model.labels.filter { it.align == LabelAlign.CENTER }
        assertEquals(listOf("07-01", "07-31"), tickLabels.map { it.text })
        assertEquals(LINE_LEFT, tickLabels.first().x, 0.001)
        assertEquals(LOGICAL_WIDTH - LINE_RIGHT, tickLabels.last().x, 0.001)
    }

    @Test
    @DisplayName("the secondary series rides a FIXED domain and draws no dots")
    fun lineChartSecondaryIsFixed() {
        val model = requireNotNull(
            lineChartModel(
                primary = listOf(XYPoint(0.0, 60.0), XYPoint(1.0, 65.0)),
                secondary = listOf(XYPoint(0.0, 5.0), XYPoint(1.0, 10.0)),
            ),
        )

        val secondary = model.lines.first { it.tone == PlotTone.SECONDARY }
        // 5 and 10 are the domain's own ends, so they land on the plot's edges
        // whatever the primary series happens to be doing.
        assertEquals(220.0 - 22.0, secondary.points.first().y, 0.001)
        assertEquals(10.0, secondary.points.last().y, 0.001)
        assertTrue(model.dots.none { it.tone == PlotTone.SECONDARY })
        assertTrue(model.labels.any { it.align == LabelAlign.START }, "right-hand axis labels missing")
    }

    @Test
    @DisplayName("a muted point keeps its own flag rather than being looked up by index")
    fun lineChartCarriesMuted() {
        val model = requireNotNull(
            lineChartModel(
                primary = listOf(
                    XYPoint(0.0, 60.0, muted = true),
                    XYPoint(1.0, 65.0),
                    XYPoint(2.0, 70.0, muted = true),
                ),
            ),
        )

        assertEquals(
            listOf(true, false, true),
            model.dots.filter { it.tone == PlotTone.PRIMARY }.map { it.muted },
        )
    }

    @Test
    @DisplayName("a single point draws its dot and no line")
    fun lineChartSinglePoint() {
        val model = requireNotNull(lineChartModel(primary = listOf(XYPoint(0.0, 60.0))))

        assertTrue(model.lines.isEmpty())
        assertEquals(1, model.dots.size)
    }

    // ---- bar chart ---------------------------------------------------------------

    @Test
    @DisplayName("an empty week keeps its slot and draws nothing in it")
    fun barChartZeroWeek() {
        val model = requireNotNull(
            barChartModel(
                weeks = listOf(
                    StackedWeek("2026-07-06", mapOf("a" to 10.0)),
                    StackedWeek("2026-07-13", mapOf("a" to 0.0)),
                    StackedWeek("2026-07-20", mapOf("a" to 5.0)),
                ),
                keys = listOf("a"),
                tones = mapOf("a" to PlotTone.PRIMARY),
            ),
        )

        assertEquals(2, model.rects.size)
        // Three slots, three labels: the empty week is still a week.
        assertEquals(3, model.labels.count { it.align == LabelAlign.CENTER })
    }

    @Test
    @DisplayName("a range with nothing logged still draws an axis rather than collapsing")
    fun barChartAllZero() {
        val model = requireNotNull(
            barChartModel(
                weeks = listOf(StackedWeek("2026-07-06", mapOf("a" to 0.0))),
                keys = listOf("a"),
                tones = mapOf("a" to PlotTone.PRIMARY),
            ),
        )

        assertTrue(model.rects.isEmpty())
        assertTrue(model.gridlines.isNotEmpty(), "the y domain floors at 1 so an axis survives")
    }

    @Test
    @DisplayName("an unfinished week is marked so it is not read against finished ones")
    fun barChartPartialWeek() {
        val model = requireNotNull(
            barChartModel(
                weeks = listOf(
                    StackedWeek("2026-07-06", mapOf("a" to 10.0)),
                    StackedWeek("2026-07-13", mapOf("a" to 3.0)),
                ),
                keys = listOf("a"),
                tones = mapOf("a" to PlotTone.PRIMARY),
                partial = listOf(false, true),
            ),
        )

        assertEquals(listOf(false, true), model.rects.map { it.partial })
    }

    @Test
    @DisplayName("no weeks, no chart")
    fun barChartEmpty() {
        assertNull(barChartModel(emptyList(), listOf("a"), emptyMap()))
    }

    @Test
    @DisplayName("a y-axis tick whose label repeats the one above it keeps its gridline")
    fun yAxisMarksDedup() {
        val yScale = linearScale(0.0, 1.5, 100.0, 0.0)
        val (guides, labels) = yAxisMarks(
            yMin = 0.0,
            yMax = 1.5,
            yScale = yScale,
            x0 = 40.0,
            x1 = 350.0,
            format = { jsNumberString(jsRound(it).toDouble()) },
        )

        assertEquals(4, guides.size)
        assertEquals(3, labels.size, "the duplicate label is dropped, its gridline is not")
        assertNotNull(labels.first())
        assertTrue(labels.all { it.align == LabelAlign.END })
    }

    private companion object {
        val LINE_LEFT = LINE_CHART_MARGINS.left
        val LINE_RIGHT = LINE_CHART_MARGINS.right
    }
}
