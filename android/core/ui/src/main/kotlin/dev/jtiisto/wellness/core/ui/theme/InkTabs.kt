package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One tab: the id the caller switches on, and the word the reader sees. */
@Immutable
data class InkTab(val id: String, val label: String)

/**
 * The strip: mono caps over a hairline, the tab in force in ink with a 2dp rule
 * under it.
 *
 * A pill would be a second surface on a page that has one, so a selected tab is
 * simply the word someone has ruled under — the same statement the nav bar's
 * underline and the range segments make.
 *
 * Scrollable rather than squeezed: tracked mono caps are wide, and a tab that
 * ellipsised would lose the only word it has.
 */
@Composable
fun InkTabRow(
    tabs: List<InkTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LogbookTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawLine(
                    color = palette.rule,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        for (tab in tabs) {
            val active = tab.id == selectedId
            Box(
                modifier = Modifier
                    .heightIn(min = LogbookSpace.touchTarget)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(tab.id) },
                    )
                    .padding(horizontal = TAB_PADDING)
                    .drawBehind {
                        if (!active) return@drawBehind
                        val stroke = TAB_UNDERLINE.toPx()
                        drawLine(
                            color = palette.ink,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label.uppercase(),
                    style = LogbookTheme.type.eyebrow,
                    color = if (active) palette.ink else palette.inkFaint,
                )
            }
        }
    }
}

private val TAB_GAP = 14.dp

/** Zero would put the underline exactly under the word; a little air reads as a rule. */
private val TAB_PADDING = 2.dp
private val TAB_UNDERLINE = 2.dp
