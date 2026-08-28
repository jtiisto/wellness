package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.feature.trends.CardioViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.AEROBIC_EMPTY_TEXT
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.aerobicProxyModel
import dev.jtiisto.wellness.feature.trends.chart.zone2CardModel
import dev.jtiisto.wellness.feature.trends.staleStamps
import org.koin.androidx.compose.koinViewModel

/** Cardio: weekly Zone 2 minutes, and the steady-session heart-rate proxy. */
@Composable
fun CardioTrendsScreen(onRange: (String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: CardioViewModel = koinViewModel()
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
                staleStamps = staleStamps(state.cardio),
                onRange = onRange,
            )

            SyncBanner(syncBanner)

            when (val slice = state.cardio) {
                is Slice.Error -> ScreenError(slice.text, viewModel::retry)
                Slice.Loading -> ScreenLoading()
                is Slice.Ready -> {
                    val zone2 = remember(slice.value) { zone2CardModel(slice.value) }
                    LogbookSection(title = "Weekly Zone 2", sub = "min") {
                        if (zone2.plot == null) {
                            ChartEmpty("No data in range")
                        } else {
                            LegendRow(zone2.legend, chart = ChartInk.CARDIO_ZONE2)
                            PlotCanvas(
                                model = zone2.plot,
                                identity = listOf("cardio", state.range),
                                chart = ChartInk.CARDIO_ZONE2,
                            )
                        }
                    }

                    val proxy = remember(slice.value) { aerobicProxyModel(slice.value.steadySessions) }
                    LogbookSection(title = "Steady-session HR", sub = "avg bpm · dot = duration") {
                        if (proxy == null) {
                            ChartEmpty(AEROBIC_EMPTY_TEXT)
                        } else {
                            PlotCanvas(
                                model = proxy,
                                identity = listOf("aerobic", state.range),
                                chart = ChartInk.AEROBIC,
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.height(SCREEN_BOTTOM))
        }
    }
}

private val SECTION_GAP = 26.dp
private val SCREEN_BOTTOM = 40.dp
