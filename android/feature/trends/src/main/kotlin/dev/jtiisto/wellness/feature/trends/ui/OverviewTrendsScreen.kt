package dev.jtiisto.wellness.feature.trends.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WeekMarkGlyph
import dev.jtiisto.wellness.feature.trends.OverviewViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.FocusRowModel
import dev.jtiisto.wellness.feature.trends.chart.LegendEntry
import dev.jtiisto.wellness.feature.trends.chart.PlotTone
import dev.jtiisto.wellness.feature.trends.chart.PrTileModel
import dev.jtiisto.wellness.feature.trends.chart.StatTileModel
import dev.jtiisto.wellness.feature.trends.chart.describeFocusRibbon
import dev.jtiisto.wellness.feature.trends.chart.focusMark
import dev.jtiisto.wellness.feature.trends.chart.focusRowModels
import dev.jtiisto.wellness.feature.trends.chart.overviewStatTiles
import dev.jtiisto.wellness.feature.trends.chart.prBadgeText
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

    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val syncBanner by viewModel.syncBanner.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            RangeToolbar(
                range = state.range,
                staleStamps = staleStamps(state.overview, state.weight),
                onRange = onRange,
            )

            SyncBanner(syncBanner)

            when (val overview = state.overview) {
                is Slice.Error -> ScreenError(overview.text, viewModel::retry)
                Slice.Loading -> ScreenLoading()
                is Slice.Ready -> {
                    val tiles = remember(overview.value) { overviewStatTiles(overview.value) }
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        for (tile in tiles) StatTile(tile, onClick = { onNavigate(tile.target) })
                    }
                    remember(overview.value) { prTileModel(overview.value.prs) }?.let { PrTile(it) }
                    val focus = remember(overview.value) { focusRowModels(overview.value) }
                    if (focus.isNotEmpty()) FocusSection(focus)
                }
            }

            val weight = (state.weight as? Slice.Ready)?.value
            if (weight != null && weight.available && weight.series.isNotEmpty()) {
                val card = remember(weight) { weightCardModel(weight.series) }
                if (card != null) {
                    LogbookSection(title = "Body weight", sub = "kg", trailing = {
                        Text(
                            text = card.latest,
                            style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                            color = LogbookTheme.palette.ink,
                            modifier = Modifier.padding(start = LogbookSpace.grid * 2),
                        )
                    }) {
                        LegendRow(WEIGHT_LEGEND)
                        PlotCanvas(model = card.plot, identity = listOf("weight", state.range))
                    }
                }
            }

            Box(modifier = Modifier.height(SCREEN_BOTTOM))
        }
    }
}

private val WEIGHT_LEGEND = listOf(
    LegendEntry("daily", PlotTone.VALUE),
    LegendEntry("7d mean", PlotTone.PRIMARY),
    LegendEntry("28d mean", PlotTone.ALT),
)

/**
 * A headline tile: label, number, how it compares, and the shape of the weeks
 * behind it.
 *
 * No fill and no border — the whole page is one surface, so what separates the
 * two tiles is the gap between them. The number is the only thing set large,
 * and it is mono, because it is a number.
 */
@Composable
private fun RowScope.StatTile(tile: StatTileModel, onClick: () -> Unit) {
    val palette = LogbookTheme.palette
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${tile.label}, ${tile.headline} ${tile.unit}. ${tile.soFarLine}"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tile.label.uppercase(), style = LogbookTheme.type.eyebrow, color = palette.inkSoft)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = tile.headline,
                style = LogbookTheme.type.data.copy(
                    fontSize = STAT_SIZE,
                    lineHeight = STAT_LEADING,
                    fontWeight = FontWeight.Medium,
                ),
                color = palette.ink,
            )
            Text(
                text = tile.unit,
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
            )
        }
        // The delta rides inside the model's own line and carries its sign
        // there: direction needs no colour and no arrow.
        tile.avgLine?.let { Text(it, style = LogbookTheme.type.meta, color = palette.ink) }
        Text(tile.soFarLine, style = LogbookTheme.type.meta, color = palette.inkSoft)
        Sparkline(points = tile.sparkline, modifier = Modifier.height(SPARKLINE_HEIGHT))
    }
}

@Composable
private fun PrTile(model: PrTileModel) {
    LogbookSection(title = prBadgeText(model.badge)) {
        model.latest?.let {
            Text(it, style = LogbookTheme.type.meta, color = LogbookTheme.palette.inkSoft)
        }
    }
}

/**
 * The weakest trackers, each with its fortnight drawn in the journal's marks.
 *
 * The same nine-shape grammar, not a chart-shaped copy of it: a day that met its
 * target is the same filled dot here as it is on the journal row the reader
 * tapped through from. The run is one spoken node — fourteen marks are one
 * glance, and a reader stopping on each would hear geometry with no sentence
 * around it.
 */
@Composable
private fun FocusSection(rows: List<FocusRowModel>) {
    val palette = LogbookTheme.palette
    LogbookSection(title = "Adherence focus", sub = "weakest · 14d") {
        for (row in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid + 2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.name, style = LogbookTheme.type.name, color = palette.ink)
                    Text(row.rate, style = LogbookTheme.type.meta, color = palette.inkSoft)
                    if (row.dropping) {
                        // A fact about the trend, said in the label voice —
                        // there is no failure here to tint, and the arrow the
                        // Graphite chip carried was doing the colour's job.
                        Text(
                            text = "DROPPING",
                            style = LogbookTheme.type.eyebrow,
                            color = palette.inkSoft,
                        )
                    }
                }
                val spoken = describeFocusRibbon(row.ribbon.map { it.status })
                FlowRow(
                    modifier = if (spoken == null) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { contentDescription = spoken }
                    },
                    horizontalArrangement = Arrangement.spacedBy(RIBBON_GAP),
                    verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
                ) {
                    for (day in row.ribbon) WeekMarkGlyph(mark = focusMark(day.status))
                }
            }
        }
    }
}

private val SECTION_GAP = 26.dp
private val SCREEN_BOTTOM = 40.dp
private val TILE_GAP = 20.dp
private val RIBBON_GAP = 5.dp
private val SPARKLINE_HEIGHT = 26.dp

private val STAT_SIZE = 24.sp
private val STAT_LEADING = 26.sp
