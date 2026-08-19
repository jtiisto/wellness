package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A section head: display-caps label over a 1.5dp ink rule, with an optional
 * mono qualifier and an optional trailing control on the same baseline.
 *
 * Logbook groups by drawing, not by nesting — this is the whole of "a block
 * starts here". [sub] measures first and the title wraps, because the qualifier
 * is the bounded half of the pair (a count, a unit, a window) while the title is
 * the free-running one.
 *
 * `inline`, and so is [LogbookSection]: a capturing `@Composable` lambda handed
 * to a *non*-inline wrapper compiles to a class the coverage tool's
 * `@Composable` filter cannot see past, and every screen built out of these
 * would count as untested code no test can reach.
 *
 * Trends still draws its own copy of this head (Round 3, device-accepted, and
 * frozen until the retirement phase); journal deliberately keeps a different
 * one, where the rollup cluster measures first and the *category name* wraps.
 * New Logbook surfaces use this one.
 */
@Composable
inline fun LogbookSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val palette = LogbookTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = LogbookSpace.sectionUnderline.toPx()
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(bottom = SECTION_HEAD_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title.uppercase(),
            style = LogbookTheme.type.section,
            color = palette.ink,
            modifier = Modifier.weight(1f),
        )
        sub?.let {
            Text(
                text = it.uppercase(),
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
                modifier = Modifier.padding(start = LogbookSpace.grid * 2),
            )
        }
        trailing()
    }
}

/** [LogbookSectionHead] with its content under it — the shape every block takes. */
@Composable
inline fun LogbookSection(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        LogbookSectionHead(title = title, sub = sub, trailing = trailing)
        content()
    }
}

/**
 * A hairline along the bottom edge — the only divider the system has.
 *
 * Drawn rather than laid out, so a row's separator costs it no height and rows
 * keep the rhythm their padding sets.
 */
fun Modifier.bottomRule(color: Color, thickness: Dp = LogbookSpace.hairline): Modifier =
    drawBehind {
        val stroke = thickness.toPx()
        drawLine(
            color = color,
            start = Offset(0f, size.height - stroke / 2f),
            end = Offset(size.width, size.height - stroke / 2f),
            strokeWidth = stroke,
        )
    }

/** The air between a section label and the rule under it. */
@PublishedApi
internal val SECTION_HEAD_GAP = 5.dp
