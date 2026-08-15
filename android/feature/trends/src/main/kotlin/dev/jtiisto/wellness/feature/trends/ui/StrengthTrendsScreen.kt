package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.StrengthViewModel
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.md),
    ) {
        val exercises = state.exercises.valueOrNull
        RangeToolbar(
            range = state.range,
            staleStamps = staleStamps(state.exercises, state.volume, state.detail),
            onRange = onRange,
        )

        if (exercises != null && exercises.isNotEmpty()) {
            val options = remember(exercises) {
                pickerLabels(exercises.map { NamedItem(it.slug, it.name) })
            }
            PillSelect(
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
            TrendsCard(title = card.title, unit = card.subtitle, trailing = {
                Pill(label = "RPE", selected = state.showRpe, onClick = viewModel::toggleRpe)
            }) {
                if (card.plot == null) {
                    ChartEmpty("No sessions in range")
                } else {
                    PlotCanvas(
                        model = card.plot,
                        identity = listOf("progression", state.range, state.selected, pinEpoch),
                    )
                    LegendRow(card.legend)
                }
            }
        }

        (state.volume as? Slice.Ready)?.value?.let { volume ->
            val card = remember(volume) { volumeCardModel(volume.weeks) }
            TrendsCard(title = "Weekly volume", unit = "kg") {
                if (card.plot == null) {
                    ChartEmpty("No data in range")
                } else {
                    PlotCanvas(model = card.plot, identity = listOf("volume", state.range, pinEpoch))
                    LegendRow(card.legend)
                }
            }
        }

        if (exercises != null && exercises.isNotEmpty()) {
            val rows = remember(exercises) { prBoardRows(exercises) }
            TrendsCard(title = "Records") {
                for (row in rows) PrRow(row)
            }
        }

        Box(modifier = Modifier.height(WellnessSpace.lg))
    }
}

@Composable
private fun PrRow(row: PrRowModel) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, style = WellnessTheme.type.body, color = palette.textPrimary)
                if (row.plateau) {
                    Text("plateau", style = WellnessTheme.type.micro, color = palette.warning)
                }
            }
            Text(row.slug, style = WellnessTheme.type.micro, color = palette.textFaint)
        }
        if (row.best != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(row.best, style = WellnessTheme.type.label, color = palette.textPrimary)
                row.detail?.let {
                    Text(it, style = WellnessTheme.type.micro, color = palette.textFaint)
                }
            }
        }
    }
    Box(modifier = Modifier.height(4.dp))
}
