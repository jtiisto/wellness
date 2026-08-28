package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.StrengthViewModel
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.NamedItem
import dev.jtiisto.wellness.feature.trends.chart.PrRowModel
import dev.jtiisto.wellness.feature.trends.chart.pickerLabels
import dev.jtiisto.wellness.feature.trends.chart.prBoardRows
import dev.jtiisto.wellness.feature.trends.chart.progressionCardModel
import dev.jtiisto.wellness.feature.trends.chart.volumeCardModel
import dev.jtiisto.wellness.feature.trends.staleStamps
import dev.jtiisto.wellness.feature.trends.valueOrNull
import org.koin.androidx.compose.koinViewModel

/** Strength: an exercise's progression, weekly volume, and the record board. */
@Composable
fun StrengthTrendsScreen(onRange: (String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: StrengthViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Bumped when a picker opens, so every chart on the screen drops its pin
    // rather than leaving a focusable popup behind a modal sheet.
    var pinEpoch by remember { mutableIntStateOf(0) }
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
            val exercises = state.exercises.valueOrNull
            RangeToolbar(
                range = state.range,
                staleStamps = staleStamps(state.exercises, state.volume, state.detail),
                onRange = onRange,
            )

            SyncBanner(syncBanner)

            if (exercises != null && exercises.isNotEmpty()) {
                val options = remember(exercises) {
                    pickerLabels(exercises.map { NamedItem(it.slug, it.name) })
                }
                PickerField(
                    title = "Exercise",
                    options = options,
                    value = state.selected,
                    onChange = viewModel::selectExercise,
                    onOpen = { pinEpoch++ },
                )
            }

            when (val slice = state.exercises) {
                is Slice.Error -> ScreenError(slice.text, viewModel::retry)
                Slice.Loading -> ScreenLoading()
                is Slice.Ready ->
                    if (slice.value.isEmpty()) ChartEmpty("No logged sets yet")
            }

            (state.detail as? Slice.Ready)?.value?.let { detail ->
                val card = remember(detail, state.showRpe) { progressionCardModel(detail, state.showRpe) }
                LogbookSection(title = card.title, sub = card.subtitle) {
                    if (card.plot == null) {
                        ChartEmpty("No sessions in range")
                    } else {
                        // The toggle sits with the legend rather than in the head:
                        // it switches one of the entries beside it, and the head is
                        // already carrying a name that can run long.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegendRow(
                                card.legend,
                                modifier = Modifier.weight(1f),
                                chart = ChartInk.PROGRESSION,
                            )
                            ToggleSegment(
                                label = "RPE",
                                on = state.showRpe,
                                onToggle = viewModel::toggleRpe,
                            )
                        }
                        PlotCanvas(
                            model = card.plot,
                            identity = listOf("progression", state.range, state.selected, pinEpoch),
                            chart = ChartInk.PROGRESSION,
                        )
                    }
                }
            }

            (state.volume as? Slice.Ready)?.value?.let { volume ->
                val card = remember(volume) { volumeCardModel(volume.weeks) }
                LogbookSection(title = "Weekly volume", sub = "kg") {
                    if (card.plot == null) {
                        ChartEmpty("No data in range")
                    } else {
                        LegendRow(card.legend, chart = ChartInk.VOLUME)
                        PlotCanvas(
                            model = card.plot,
                            identity = listOf("volume", state.range, pinEpoch),
                            chart = ChartInk.VOLUME,
                        )
                    }
                }
            }

            if (exercises != null && exercises.isNotEmpty()) {
                val rows = remember(exercises) { prBoardRows(exercises) }
                LogbookSection(title = "Records", sub = "best e1RM") {
                    for (row in rows) PrRow(row)
                }
            }

            Box(modifier = Modifier.height(SCREEN_BOTTOM))
        }
    }
}

/**
 * One record: the lift on the left, the number on the right, a hairline under
 * both. A table row, so the number is mono and End-aligned — the column is read
 * down the page.
 */
@Composable
private fun PrRow(row: PrRowModel) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
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
            .padding(vertical = ROW_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, style = LogbookTheme.type.name, color = palette.ink)
                if (row.plateau) {
                    Text("PLATEAU", style = LogbookTheme.type.eyebrow, color = palette.inkSoft)
                }
            }
            Text(row.slug, style = LogbookTheme.type.meta, color = palette.inkSoft)
        }
        if (row.best != null) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = LogbookSpace.grid * 2),
            ) {
                Text(
                    text = row.best,
                    style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.ink,
                )
                row.detail?.let {
                    Text(it, style = LogbookTheme.type.meta, color = palette.inkSoft)
                }
            }
        }
    }
}

private val SECTION_GAP = 26.dp
private val SCREEN_BOTTOM = 40.dp
private val ROW_PADDING = 9.dp
