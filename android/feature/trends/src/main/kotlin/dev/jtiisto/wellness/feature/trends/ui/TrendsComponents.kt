package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.trends.chart.LegendEntry
import dev.jtiisto.wellness.feature.trends.chart.PickerOption
import dev.jtiisto.wellness.feature.trends.chart.RANGES
import dev.jtiisto.wellness.feature.trends.chart.TREND_SCREENS
import dev.jtiisto.wellness.feature.trends.chart.staleBadgeText

/**
 * The Trends tab's shared furniture: cards, the pill rows, the stale badge and
 * the picker sheet.
 *
 * The layout wrappers are `inline` for the same reason Compose's own `Box` and
 * `Row` are — a non-inline wrapper taking a capturing content lambda compiles
 * to a class the coverage tool cannot see past, and the whole chart layer would
 * count as untested code that no test can reach.
 */

/** A titled card. [unit] is the quiet qualifier that follows the title. */
@Composable
inline fun TrendsCard(
    title: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
    noinline trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(WellnessTheme.palette.card)
            .border(WellnessSpace.hairline, WellnessTheme.palette.line, WellnessShape.card)
            .padding(WellnessSpace.card),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = WellnessTheme.type.title, color = WellnessTheme.palette.textPrimary)
                if (unit != null) {
                    Text(unit, style = WellnessTheme.type.secondary, color = WellnessTheme.palette.textFaint)
                }
            }
            trailing?.invoke()
        }
        content()
    }
}

/** A row of selectable pills — the range selector and the screen switcher. */
@Composable
fun PillRow(
    options: List<PickerOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
    ) {
        for (option in options) {
            Pill(
                label = option.label,
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
            )
        }
    }
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Box(
        modifier = modifier
            .clip(WellnessShape.pill)
            .background(if (selected) accent.softFill else palette.band)
            .border(
                WellnessSpace.hairline,
                if (selected) accent.border else palette.line,
                WellnessShape.pill,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = WellnessTheme.type.label,
            color = if (selected) accent.text else palette.textSecondary,
        )
    }
}

/**
 * "cached · 2h ago", when anything on screen is being served from the cache.
 *
 * Read at composition rather than on a timer, exactly as the PWA reads it: the
 * age is a fact about the fetch, and a badge that ticked on its own would imply
 * something is still happening.
 */
@Composable
fun StaleBadge(stamps: List<Long>, modifier: Modifier = Modifier) {
    val text = staleBadgeText(stamps, System.currentTimeMillis()) ?: return
    val palette = WellnessTheme.palette
    Text(
        text = text,
        style = WellnessTheme.type.micro,
        color = palette.textFaint,
        modifier = modifier
            .clip(WellnessShape.pill)
            .background(palette.band)
            .padding(horizontal = WellnessSpace.sm, vertical = 3.dp)
            .semantics { contentDescription = "Offline — showing cached data" },
    )
}

/** The screen switcher, above everything and never hidden. */
@Composable
fun ScreenSwitcher(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    PillRow(
        options = TREND_SCREENS.map { PickerOption(it.id, it.label) },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

/**
 * The range selector and the stale badge.
 *
 * Rendered by every screen in every state, loading and error included: an
 * offline failure on an uncached range must not take away the way back to a
 * range that *is* cached.
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillRow(
            options = RANGES.map { PickerOption(it.id, it.label) },
            selected = range,
            onSelect = onRange,
        )
        StaleBadge(staleStamps)
    }
}

/**
 * A pill that opens a bottom sheet of options.
 *
 * [onOpen] fires before the sheet appears so the screen can drop its chart
 * pins: a focusable tooltip popup and a modal sheet would otherwise both want
 * the system back gesture, and the user would have to press it twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PillSelect(
    title: String,
    options: List<PickerOption>,
    value: String?,
    onChange: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val current = options.firstOrNull { it.value == value }

    Pill(
        label = current?.label ?: title,
        selected = false,
        onClick = {
            onOpen()
            open = true
        },
        modifier = modifier,
    )

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = WellnessTheme.palette.card,
        ) {
            Column(modifier = Modifier.padding(bottom = WellnessSpace.lg)) {
                Text(
                    text = title,
                    style = WellnessTheme.type.micro,
                    color = WellnessTheme.palette.textFaint,
                    modifier = Modifier.padding(horizontal = WellnessSpace.card, vertical = WellnessSpace.sm),
                )
                for (option in options) {
                    val selected = option.value == value
                    Text(
                        text = option.label,
                        style = WellnessTheme.type.body,
                        color = if (selected) {
                            WellnessTheme.accent.text
                        } else {
                            WellnessTheme.palette.textPrimary
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChange(option.value)
                                open = false
                            }
                            .padding(horizontal = WellnessSpace.card, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Swatches naming what each series is. */
@Composable
fun LegendRow(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val colors = rememberPlotColors()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.md),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
    ) {
        for (entry in entries) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.of(entry.tone)),
                )
                Text(
                    text = entry.label,
                    style = WellnessTheme.type.micro,
                    color = WellnessTheme.palette.textSecondary,
                )
            }
        }
    }
}

/** The card-sized "nothing to draw" line. */
@Composable
fun ChartEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WellnessTheme.type.secondary,
        color = WellnessTheme.palette.textFaint,
        modifier = modifier.padding(vertical = WellnessSpace.md),
    )
}

/**
 * A failed screen, with a way out.
 *
 * The PWA relies on switching range or screen to re-trigger a fetch, which on a
 * phone is a dead end the first time someone lands here offline.
 */
@Composable
fun ScreenError(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Text(text = text, style = WellnessTheme.type.body, color = WellnessTheme.palette.error)
        TextButton(onClick = onRetry, colors = WellnessDefaults.accentTextButtonColors()) {
            Text("Retry")
        }
    }
}

@Composable
fun ScreenLoading(modifier: Modifier = Modifier) {
    ChartEmpty(text = "Loading…", modifier = modifier)
}
