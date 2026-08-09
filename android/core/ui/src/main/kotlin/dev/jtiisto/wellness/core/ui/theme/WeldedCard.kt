package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a slice sits in a welded card.
 *
 * A category and its trackers are separate list items — they have to be, to
 * animate independently — but they must read as one object with a band header
 * welded to a stack of rows. Each slice draws only the edges it owns, so the
 * seams between them are single hairlines rather than doubled ones.
 */
enum class WeldPosition {
    /** A collapsed group: a standalone pill, closed on every edge. */
    SOLO,

    /** The band header: rounded on top, hairlines down the sides and along the seam. */
    TOP,

    /** A row with a row above and below it. */
    MIDDLE,

    /** The last row: rounded bottom, open at the top. */
    BOTTOM,
}

/**
 * Fill a welded slice and draw its hairlines.
 *
 * The edges are drawn over the content rather than behind it: a hairline
 * hidden under a row's own background is not a hairline.
 */
fun Modifier.welded(
    position: WeldPosition,
    fill: Color,
    line: Color,
    radius: Dp = 8.dp,
): Modifier = this
    .clip(position.shape(radius))
    .background(fill)
    .drawWithContent {
        drawContent()
        drawWeldEdges(position, line, radius)
    }

private fun WeldPosition.shape(radius: Dp): Shape = when (this) {
    WeldPosition.SOLO -> RoundedCornerShape(radius)
    WeldPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius)
    WeldPosition.MIDDLE -> RectangleShape
    WeldPosition.BOTTOM -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
}

private fun DrawScope.drawWeldEdges(position: WeldPosition, color: Color, radius: Dp) {
    val stroke = 1.dp.toPx()
    val half = stroke / 2f
    val r = radius.toPx()
    val w = size.width
    val h = size.height
    val roundTop = position == WeldPosition.SOLO || position == WeldPosition.TOP
    val roundBottom = position == WeldPosition.SOLO || position == WeldPosition.BOTTOM
    // Only a standalone pill closes along the top. The band header does not:
    // its rounded corners come from the clip, and its tone is what separates it
    // from the canvas — an outline there would draw a lid on an open card.
    val closeTop = position == WeldPosition.SOLO
    val corner = Size(2 * r, 2 * r)

    val sideTop = if (roundTop) r else 0f
    val sideBottom = if (roundBottom) h - r else h
    drawLine(color, Offset(half, sideTop), Offset(half, sideBottom), stroke)
    drawLine(color, Offset(w - half, sideTop), Offset(w - half, sideBottom), stroke)

    if (closeTop) {
        drawLine(color, Offset(r, half), Offset(w - r, half), stroke)
        drawArc(color, 180f, 90f, false, Offset(half, half), corner, style = Stroke(stroke))
        drawArc(color, 270f, 90f, false, Offset(w - half - 2 * r, half), corner, style = Stroke(stroke))
    }

    // Every position closes at the bottom: for TOP and MIDDLE that line is the
    // seam against the slice below, which is what stitches the card together.
    val bottomInset = if (roundBottom) r else 0f
    drawLine(color, Offset(bottomInset, h - half), Offset(w - bottomInset, h - half), stroke)
    if (roundBottom) {
        drawArc(color, 90f, 90f, false, Offset(half, h - half - 2 * r), corner, style = Stroke(stroke))
        drawArc(color, 0f, 90f, false, Offset(w - half - 2 * r, h - half - 2 * r), corner, style = Stroke(stroke))
    }
}
