package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.trends.OverviewViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.FocusRowModel
import dev.jtiisto.wellness.feature.trends.chart.LegendEntry
import dev.jtiisto.wellness.feature.trends.chart.PlotTone
import dev.jtiisto.wellness.feature.trends.chart.PrTileModel
import dev.jtiisto.wellness.feature.trends.chart.StatTileModel
import dev.jtiisto.wellness.feature.trends.chart.focusRowModels
import dev.jtiisto.wellness.feature.trends.chart.overviewStatTiles
import dev.jtiisto.wellness.feature.trends.chart.prTileModel
import dev.jtiisto.wellness.feature.trends.chart.weightCardModel
import dev.jtiisto.wellness.feature.trends.staleStamps
import org.koin.androidx.compose.koinViewModel

/**
 * Overview: two headline tiles, records, adherence focus, and body weight.
 *
 * The tiles are the screen — if `/overview` fails, the screen failed. Weight is
 * a passenger: its card simply is not there when it could not be fetched, and
 * the tiles above it are unaffected.
 */
@Composable
fun OverviewTrendsScreen(
    onRange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: OverviewViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.onActive()
        onDispose { viewModel.onInactive() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.md),
    ) {
        RangeToolbar(
            range = state.range,
            staleStamps = staleStamps(state.overview, state.weight),
            onRange = onRange,
        )

        when (val overview = state.overview) {
            is Slice.Error -> ScreenError(overview.text, viewModel::retry)
            Slice.Loading -> ScreenLoading()
            is Slice.Ready -> {
                val tiles = remember(overview.value) { overviewStatTiles(overview.value) }
                Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
                    for (tile in tiles) StatTile(tile, onClick = { onNavigate(tile.target) })
                }
                remember(overview.value) { prTileModel(overview.value.prs) }?.let { PrTile(it) }
                val focus = remember(overview.value) { focusRowModels(overview.value) }
                if (focus.isNotEmpty()) FocusCard(focus)
            }
        }

        val weight = (state.weight as? Slice.Ready)?.value
        if (weight != null && weight.available && weight.series.isNotEmpty()) {
            val card = remember(weight) { weightCardModel(weight.series) }
            if (card != null) {
                TrendsCard(title = "Body weight", unit = "kg · 7d & 28d mean", trailing = {
                    Text(card.latest, style = WellnessTheme.type.label, color = WellnessTheme.palette.textSecondary)
                }) {
                    PlotCanvas(model = card.plot, identity = listOf("weight", state.range))
                    LegendRow(WEIGHT_LEGEND)
                }
            }
        }

        Box(modifier = Modifier.height(WellnessSpace.lg))
    }
}

private val WEIGHT_LEGEND = listOf(
    LegendEntry("7d mean", PlotTone.PRIMARY),
    LegendEntry("28d mean", PlotTone.ALT),
)

@Composable
private fun RowScope.StatTile(tile: StatTileModel, onClick: () -> Unit) {
    val palette = WellnessTheme.palette
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(WellnessSpace.hairline, palette.line, WellnessShape.card)
            .clickable(onClick = onClick)
            .padding(WellnessSpace.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tile.label, style = WellnessTheme.type.micro, color = palette.textFaint)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tile.headline, style = WellnessTheme.type.stat, color = palette.textPrimary)
            Text(
                text = tile.unit,
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
            )
        }
        tile.avgLine?.let { Text(it, style = WellnessTheme.type.micro, color = palette.textSecondary) }
        Text(tile.soFarLine, style = WellnessTheme.type.micro, color = palette.textFaint)
        Sparkline(points = tile.sparkline, modifier = Modifier.height(26.dp))
    }
}

@Composable
private fun PrTile(model: PrTileModel) {
    TrendsCard(title = model.badge) {
        model.latest?.let {
            Text(it, style = WellnessTheme.type.secondary, color = WellnessTheme.palette.textSecondary)
        }
    }
}

@Composable
private fun FocusCard(rows: List<FocusRowModel>) {
    TrendsCard(title = "Adherence focus", unit = "weakest, rolling 14d") {
        for (row in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        style = WellnessTheme.type.body,
                        color = WellnessTheme.palette.textPrimary,
                    )
                    Text(
                        row.rate,
                        style = WellnessTheme.type.micro,
                        color = WellnessTheme.palette.textFaint,
                    )
                    if (row.dropping) {
                        Text(
                            text = "↓ dropping",
                            style = WellnessTheme.type.micro,
                            color = WellnessTheme.palette.warning,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (day in row.ribbon) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dayStatusColor(day.status)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A day dot's tone. `off` is not a failure — the tracker was not scheduled —
 * so it takes the faint ink rather than the missed one.
 */
@Composable
private fun dayStatusColor(status: String): Color {
    val palette = WellnessTheme.palette
    return when (status) {
        "met" -> palette.success
        "partial" -> palette.warning
        "missed" -> palette.line
        else -> palette.textFaint.copy(alpha = 0.3f)
    }
}
