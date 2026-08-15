package dev.jtiisto.wellness.feature.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.journal.RingRole
import dev.jtiisto.wellness.core.data.journal.describeCategoryRollup
import dev.jtiisto.wellness.core.data.journal.ringSlices
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import kotlin.math.sqrt

/**
 * The category band's signal ring: ring = things to do, hoop = things to hold,
 * diamond = things to notice.
 *
 * The classes degrade by subtraction — a category with no avoidances draws no
 * centre, one with no habits gives the centre the space instead — and the
 * footprint stays constant however many trackers the category holds. An
 * all-good day says nothing at all: the calm cluster is the statement.
 *
 * Every decision it draws was already made in [CategoryRollup] and [ringSlices];
 * this only picks tokens and puts them on a canvas.
 */
@Composable
fun CategoryRollupIndicator(rollup: CategoryRollup, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val slices = ringSlices(rollup.habitsMet, rollup.habitsPartial, rollup.habitsNotYet)
    val metColor = palette.success
    // The accent as a thin stroke takes the on-light variant on a light theme.
    val partialColor = WellnessTheme.accent.text
    val trackColor = palette.textPrimary.copy(alpha = NOT_YET_ALPHA)
    // Restraint is never success-coloured: holding an avoidance is an absence,
    // not an achievement. A break is warning, never error — a slip is not a
    // failure state.
    val heldColor = palette.textFaint
    val brokenColor = palette.warning

    Row(
        // One description for the cluster; the count glyph would otherwise be
        // read out on its own, stripped of what it counts.
        modifier = modifier.clearAndSetSemantics { contentDescription = describeCategoryRollup(rollup) },
        horizontalArrangement = Arrangement.spacedBy(CLUSTER_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slices.isNotEmpty()) {
            Canvas(Modifier.size(RING_SIZE)) {
                val stroke = RING_STROKE.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                slices.forEach { slice ->
                    drawArc(
                        color = when (slice.role) {
                            RingRole.MET -> metColor
                            RingRole.PARTIAL -> partialColor
                            RingRole.NOT_YET -> trackColor
                        },
                        startAngle = slice.startDeg,
                        sweepAngle = slice.sweepDeg,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        // Butt caps: a round cap would spill into the 6° gaps
                        // and weld the slices back into one arc.
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                if (rollup.avoidances > 0) {
                    drawAvoidanceMark(AVOIDANCE_SIZE, rollup.avoidancesBroken > 0, heldColor, brokenColor)
                }
            }
        } else if (rollup.avoidances > 0) {
            // No ring to sit inside, so the mark stands alone a size up.
            Canvas(Modifier.size(AVOIDANCE_SOLO_SIZE)) {
                drawAvoidanceMark(AVOIDANCE_SOLO_SIZE, rollup.avoidancesBroken > 0, heldColor, brokenColor)
            }
        }

        if (rollup.observationsExpected > 0) {
            val alone = slices.isEmpty() && rollup.avoidances == 0
            Row(
                horizontalArrangement = Arrangement.spacedBy(OBSERVATION_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(DIAMOND_SIZE)) {
                    drawDiamond(filled = rollup.observationsNoted > 0, color = palette.textFaint)
                }
                Text(
                    // The denominator appears only when observations are the
                    // whole cluster — beside a ring it would read as a second
                    // score competing with the real one.
                    text = if (alone) {
                        "${rollup.observationsNoted} of ${rollup.observationsExpected}"
                    } else {
                        "${rollup.observationsNoted}"
                    },
                    style = WellnessTheme.type.micro,
                    color = palette.textFaint,
                )
            }
        }
    }
}

private fun DrawScope.drawAvoidanceMark(diameter: Dp, broken: Boolean, held: Color, brokenColor: Color) {
    val radius = diameter.toPx() / 2f
    if (broken) {
        drawCircle(brokenColor, radius, center)
    } else {
        val stroke = AVOIDANCE_STROKE.toPx()
        drawCircle(held, radius - stroke / 2f, center, style = Stroke(stroke))
    }
}

private fun DrawScope.drawDiamond(filled: Boolean, color: Color) {
    // The footprint is measured point to point, so the square inside it is its
    // diagonal's worth smaller — and an outline's stroke straddles that square,
    // so it comes off the side too. Both states then occupy the same 7dp.
    val stroke = DIAMOND_STROKE.toPx()
    val side = size.minDimension / sqrt(2f) - if (filled) 0f else stroke
    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    rotate(DIAMOND_ROTATION) {
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = Size(side, side),
            cornerRadius = CornerRadius(DIAMOND_CORNER.toPx()),
            style = if (filled) Fill else Stroke(stroke),
        )
    }
}

/** 18% of ink: present enough to close the circle, quiet enough not to be a verdict. */
private const val NOT_YET_ALPHA = 0.18f
private const val DIAMOND_ROTATION = 45f

private val CLUSTER_GAP = 6.dp
private val RING_SIZE = 18.dp
private val RING_STROKE = 2.5.dp
private val AVOIDANCE_SIZE = 6.dp
private val AVOIDANCE_SOLO_SIZE = 8.dp
private val AVOIDANCE_STROKE = 1.5.dp
private val DIAMOND_SIZE = 7.dp
private val DIAMOND_CORNER = 1.dp
private val DIAMOND_STROKE = 1.dp
private val OBSERVATION_GAP = 3.dp
