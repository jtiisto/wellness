package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.InkTab
import dev.jtiisto.wellness.core.ui.theme.InkTabRow
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSheetHandle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.FLAG_GLYPH
import dev.jtiisto.wellness.feature.trends.chart.LegendEntry
import dev.jtiisto.wellness.feature.trends.chart.LegendSwatch
import dev.jtiisto.wellness.feature.trends.chart.PickerOption
import dev.jtiisto.wellness.feature.trends.chart.RANGES
import dev.jtiisto.wellness.feature.trends.chart.TREND_SCREENS
import dev.jtiisto.wellness.feature.trends.chart.legendSwatch
import dev.jtiisto.wellness.feature.trends.chart.staleBadgeText

/**
 * The Trends tab's furniture, on paper: sections, the tab strip, the range
 * segments, the legend line and the picker.
 *
 * The cards are gone. A card is a second surface, and Logbook has one — so a
 * chart is introduced the way every other block in the app now is: a
 * display-caps head with an ink rule under it, a mono qualifier on the same
 * baseline, and one quiet mono line beneath naming what is drawn.
 *
 * The layout wrappers are `inline` for the same reason Compose's own `Box` and
 * `Row` are — a non-inline wrapper taking a capturing content lambda compiles
 * to a class the coverage tool cannot see past, and the whole chart layer would
 * count as untested code that no test can reach.
 */

// ---- Tabs and ranges -----------------------------------------------------------

/**
 * The five sub-screens, in the system's one tab strip.
 *
 * Trends drew its own copy of the strip for a round — it was the first surface
 * to need one — and the copy retired when Round 4 hoisted [InkTabRow] into
 * `core/ui` for Analysis and Tools. The only thing that came back with the
 * shared one is 2dp of air on each side of the active tab's rule: the words sit
 * exactly where they did, because the strip's own 2dp padding replaces 4dp of
 * the gap between them.
 */
@Composable
fun ScreenTabs(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val tabs = remember { TREND_SCREENS.map { InkTab(id = it.id, label = it.label) } }
    InkTabRow(tabs = tabs, selectedId = selected, onSelect = onSelect, modifier = modifier)
}

/**
 * The range selector and the cached note.
 *
 * Rendered by every screen in every state, loading and error included: an
 * offline failure on an uncached range must not take away the way back to a
 * range that *is* cached. The note keeps the same slot in all of them, so a
 * screen that goes stale does not reflow.
 */
@Composable
fun RangeToolbar(
    range: String,
    staleStamps: List<Long>,
    onRange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
            for (spec in RANGES) {
                Segment(
                    label = spec.label,
                    active = spec.id == range,
                    onClick = { onRange(spec.id) },
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        StaleCaption(staleStamps)
    }
}

/**
 * One mono segment: ink and underlined when it is the one in force, faint when
 * it is not. No pill, no fill — a selected segment is a word someone has
 * ruled under.
 */
@Composable
fun Segment(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Box(
        modifier = modifier
            .heightIn(min = LogbookSpace.touchTarget)
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = LogbookSpace.grid),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow.copy(
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (active) palette.ink else palette.inkFaint,
            modifier = Modifier.drawBehind {
                if (!active) return@drawBehind
                val stroke = SEGMENT_UNDERLINE.toPx()
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, size.height + stroke * 2),
                    end = Offset(size.width, size.height + stroke * 2),
                    strokeWidth = stroke,
                )
            },
        )
    }
}

/**
 * The same segment as a switch — the progression card's RPE overlay.
 *
 * Underlined when it is on, for the reason the range segments are: the state is
 * *which word is in force*, and that is one language whether the words are four
 * or one.
 */
@Composable
fun ToggleSegment(label: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Box(
        modifier = modifier
            .heightIn(min = LogbookSpace.touchTarget)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = LogbookSpace.grid),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow.copy(
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (on) palette.ink else palette.inkFaint,
            modifier = Modifier.drawBehind {
                if (!on) return@drawBehind
                val stroke = SEGMENT_UNDERLINE.toPx()
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, size.height + stroke * 2),
                    end = Offset(size.width, size.height + stroke * 2),
                    strokeWidth = stroke,
                )
            },
        )
    }
}

/**
 * "cached · 2h ago", when anything on screen is being served from the cache.
 *
 * Read at composition rather than on a timer, exactly as the PWA reads it: the
 * age is a fact about the fetch, and a badge that ticked on its own would imply
 * something is still happening. A caption rather than a badge — a chip would be
 * a second surface for a footnote.
 */
@Composable
fun StaleCaption(stamps: List<Long>, modifier: Modifier = Modifier) {
    val text = staleBadgeText(stamps, System.currentTimeMillis()) ?: return
    Text(
        text = text.uppercase(),
        style = LogbookTheme.type.eyebrow,
        color = LogbookTheme.palette.inkSoft,
        modifier = modifier.semantics { contentDescription = "Offline — showing cached data" },
    )
}

// ---- Legend --------------------------------------------------------------------

/**
 * One quiet mono line naming what is drawn above it.
 *
 * Load-bearing wherever a plate appears, per the design system's legend rule —
 * a plate is assigned positionally per chart, so a coloured mark says nothing
 * without the word beside it. The swatch is the mark's own shape ([LegendSwatch]),
 * so the key and the chart cannot drift apart, and the whole line reads as one
 * node: seven unattached words are not a key.
 */
@Composable
fun LegendRow(
    entries: List<LegendEntry>,
    modifier: Modifier = Modifier,
    chart: ChartInk? = null,
) {
    if (entries.isEmpty()) return
    val spoken = entries.joinToString { it.label }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Key: $spoken" },
        horizontalArrangement = Arrangement.spacedBy(LEGEND_GAP),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        for (entry in entries) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid + 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendMark(entry, chart)
                Text(
                    text = entry.label.uppercase(),
                    style = LogbookTheme.type.eyebrow,
                    color = LogbookTheme.palette.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun LegendMark(entry: LegendEntry, chart: ChartInk?) {
    val swatch = legendSwatch(entry.tone, chart)
    if (swatch == LegendSwatch.NONE) return
    val colors = rememberPlotColors()
    val palette = LogbookTheme.palette
    val color = colors.of(entry.tone, chart)
    val isLine = swatch == LegendSwatch.LINE ||
        swatch == LegendSwatch.DASH ||
        swatch == LegendSwatch.FAINT_DASH
    Canvas(
        modifier = if (isLine) {
            Modifier.size(width = LEGEND_LINE_WIDTH, height = LEGEND_MARK)
        } else {
            Modifier.size(LEGEND_MARK)
        },
    ) {
        when (swatch) {
            LegendSwatch.DOT -> drawCircle(color = color, radius = size.minDimension / 2f)

            LegendSwatch.OPEN_DOT -> {
                val stroke = LEGEND_STROKE.toPx()
                drawCircle(color = palette.paper, radius = size.minDimension / 2f)
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2f - stroke / 2f,
                    style = Stroke(width = stroke),
                )
            }

            LegendSwatch.LINE, LegendSwatch.DASH, LegendSwatch.FAINT_DASH -> drawLine(
                color = color,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = LogbookSpace.sectionUnderline.toPx(),
                cap = StrokeCap.Butt,
                pathEffect = when (swatch) {
                    LegendSwatch.DASH -> PathEffect.dashPathEffect(DASH_ON_OFF)
                    LegendSwatch.FAINT_DASH -> PathEffect.dashPathEffect(FAINT_DASH_ON_OFF)
                    else -> null
                },
            )

            LegendSwatch.SQUARE -> drawRoundRect(
                color = color,
                size = Size(size.minDimension, size.minDimension),
                cornerRadius = CornerRadius(SWATCH_CORNER.toPx()),
            )

            // A band is a wash between two hairlines, and its key says so —
            // the wash alone is the same grey as a missed cell.
            LegendSwatch.BAND -> {
                drawRect(color = color, size = Size(size.width, size.height))
                val stroke = LogbookSpace.hairline.toPx()
                for (edge in listOf(stroke / 2f, size.height - stroke / 2f)) {
                    drawLine(
                        color = colors.bandEdge,
                        start = Offset(0f, edge),
                        end = Offset(size.width, edge),
                        strokeWidth = stroke,
                    )
                }
            }

            LegendSwatch.NONE -> Unit
        }
    }
}

// ---- Pickers, empties, errors --------------------------------------------------

/**
 * The exercise / tracker / panel picker: a form field that opens a sheet.
 *
 * A field rather than a button because that is what it is — a named choice with
 * a current value — and because the value is a name that can run long, which a
 * button's single caps line cannot hold.
 *
 * [onOpen] fires before the sheet appears so the screen can drop its chart
 * pins: a focusable tooltip popup and a modal sheet would otherwise both want
 * the system back gesture, and the user would have to press it twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerField(
    title: String,
    options: List<PickerOption>,
    value: String?,
    onChange: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val palette = LogbookTheme.palette
    val current = options.firstOrNull { it.value == value }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget)
            .clickable(
                role = Role.Button,
                onClickLabel = "Choose ${title.lowercase()}",
                onClick = {
                    onOpen()
                    open = true
                },
            )
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawLine(
                    color = palette.ruleStrong,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(bottom = LogbookSpace.grid + 2.dp)
            .semantics { contentDescription = "$title: ${current?.label ?: "none"}" },
    ) {
        Text(
            text = title.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = current?.label ?: "—",
                style = LogbookTheme.type.name,
                color = palette.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp),
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                // The picker's own affordance — a control glyph keeps 3:1.
                tint = palette.inkSoft,
                modifier = Modifier.size(CHEVRON_SIZE),
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = palette.paper,
            contentColor = palette.ink,
            shape = LogbookShapes.square,
            dragHandle = { LogbookSheetHandle() },
        ) {
            Column(
                modifier = Modifier
                    // The labs panel list is taller than the sheet: without
                    // this the options past the fold simply clip, unreachable
                    // (found on device with ~25 panels — every other picker's
                    // list was short enough that the missing scroll never
                    // showed). The sheet's drag interop is unaffected: the
                    // handle still moves the sheet, the list scrolls itself.
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = SHEET_BOTTOM),
            ) {
                Text(
                    text = title.uppercase(),
                    style = LogbookTheme.type.section,
                    color = palette.ink,
                    modifier = Modifier.padding(
                        horizontal = SHEET_PADDING,
                        vertical = LogbookSpace.grid * 2,
                    ),
                )
                for (option in options) {
                    val selected = option.value == value
                    Text(
                        text = option.label,
                        style = LogbookTheme.type.name.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) palette.ink else palette.inkSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LogbookSpace.touchTarget)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onChange(option.value)
                                    open = false
                                },
                            )
                            .drawBehind {
                                val stroke = LogbookSpace.hairline.toPx()
                                drawLine(
                                    color = palette.rule,
                                    start = Offset(0f, size.height - stroke / 2f),
                                    end = Offset(size.width, size.height - stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            }
                            .padding(horizontal = SHEET_PADDING, vertical = LogbookSpace.grid * 3),
                    )
                }
            }
        }
    }
}

/** The section-sized "nothing to draw" line, in the voice reserved for absence. */
@Composable
fun ChartEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
        color = LogbookTheme.palette.inkSoft,
        modifier = modifier.padding(vertical = LogbookSpace.grid * 2),
    )
}

@Composable
fun ScreenLoading(modifier: Modifier = Modifier) {
    ChartEmpty(text = "Loading…", modifier = modifier)
}

/**
 * A failed screen, with a way out.
 *
 * The PWA relies on switching range or screen to re-trigger a fetch, which on a
 * phone is a dead end the first time someone lands here offline. Stated in ink
 * behind a mono `!`: Logbook has no error colour, and a failure that turned the
 * page red would be the loudest thing in the app.
 */
@Composable
fun ScreenError(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = FLAG_GLYPH,
                style = LogbookTheme.type.data.copy(fontWeight = FontWeight.Medium),
                color = palette.ink,
                modifier = Modifier
                    .width(FLAG_COLUMN)
                    .clearAndSetSemantics { },
            )
            Text(text = text, style = LogbookTheme.type.body, color = palette.ink)
        }
        InkOutlineButton(label = "Retry", onClick = onRetry)
    }
}

private val SEGMENT_GAP = 10.dp
private val SEGMENT_UNDERLINE = 1.5.dp

private val LEGEND_GAP = 14.dp
private val LEGEND_MARK = 7.dp
private val LEGEND_LINE_WIDTH = 14.dp
private val LEGEND_STROKE = 1.4.dp
private val SWATCH_CORNER = 1.dp
private val DASH_ON_OFF = floatArrayOf(4f, 3f)
private val FAINT_DASH_ON_OFF = floatArrayOf(2f, 3f)

private val CHEVRON_SIZE = 16.dp
private val SHEET_PADDING = 20.dp
private val SHEET_BOTTOM = 24.dp

/** The `!` gutter, so the sentence beside it starts where a plain one would. */
private val FLAG_COLUMN = 14.dp
