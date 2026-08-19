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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.JournalTrendsViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.ConstantValue
import dev.jtiisto.wellness.feature.trends.chart.adherenceCardModel
import dev.jtiisto.wellness.feature.trends.chart.streakLine
import dev.jtiisto.wellness.feature.trends.chart.trackerPickerOptions
import dev.jtiisto.wellness.feature.trends.chart.usageCardModel
import dev.jtiisto.wellness.feature.trends.chart.valueTargetCardModel
import dev.jtiisto.wellness.feature.trends.staleStamps
import dev.jtiisto.wellness.feature.trends.valueOrNull
import org.koin.androidx.compose.koinViewModel

/**
 * Journal: one tracker's values against its target, its weekly adherence, and —
 * for the trackers where frequency is the point — how often it was logged.
 *
 * Which cards appear is the tracker's own doing: only a quantifiable tracker
 * has values to plot, only an actionable one has adherence to keep, and usage
 * appears exactly when the server sent it.
 */
@Composable
fun JournalTrendsScreen(onRange: (String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: JournalTrendsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pinEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(viewModel) {
        viewModel.onActive()
        onDispose { viewModel.onInactive() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        RangeToolbar(
            range = state.range,
            staleStamps = staleStamps(state.trackers, state.detail),
            onRange = onRange,
        )

        val trackers = state.trackers.valueOrNull
        if (trackers != null && trackers.isNotEmpty()) {
            val options = remember(trackers) { trackerPickerOptions(trackers) }
            PickerField(
                title = "Tracker",
                options = options,
                value = state.selected,
                onChange = viewModel::selectTracker,
                onOpen = { pinEpoch++ },
            )
        }

        when (val slice = state.trackers) {
            is Slice.Error -> ScreenError(slice.text, viewModel::retry)
            Slice.Loading -> ScreenLoading()
            is Slice.Ready ->
                if (slice.value.isEmpty()) ChartEmpty("No trackers with entries yet")
        }

        (state.detail as? Slice.Ready)?.value?.let { detail ->
            if (detail.tracker.type == "quantifiable") {
                val card = remember(detail) { valueTargetCardModel(detail) }
                TrendsSection(title = card.title, sub = card.subtitle.takeIf { it.isNotEmpty() }) {
                    when {
                        card.constant != null -> ConstantSeries(card.constant)
                        card.plot != null -> PlotCanvas(
                            model = card.plot,
                            identity = listOf("valueTarget", state.range, state.selected, pinEpoch),
                        )
                        else -> ChartEmpty(card.emptyText.orEmpty())
                    }
                }
            }

            if (detail.tracker.actionable) {
                val card = remember(detail) { adherenceCardModel(detail) }
                // The streaks say themselves in mono — `run 3 · best 14`. The
                // flame retires with the rest of the colour emoji: a run is a
                // number, and a number here is Plex Mono.
                TrendsSection(
                    title = card.title,
                    sub = streakLine(card.currentStreak, card.bestStreak),
                ) {
                    LegendRow(card.legend)
                    PlotCanvas(
                        model = card.plot,
                        identity = listOf("adherence", state.range, state.selected, pinEpoch),
                        touchPadding = LogbookSpace.grid * 2,
                    )
                }
            }

            detail.weeklyUsage?.let { usage ->
                val plot = remember(usage) { usageCardModel(usage) }
                TrendsSection(title = "Usage", sub = "times per week") {
                    if (plot == null) {
                        ChartEmpty("No data in range")
                    } else {
                        PlotCanvas(
                            model = plot,
                            identity = listOf("usage", state.range, state.selected, pinEpoch),
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.height(SCREEN_BOTTOM))
    }
}

/** A series that never moved: the number itself, and what that means. */
@Composable
private fun ConstantSeries(constant: ConstantValue) {
    val palette = LogbookTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = constant.value,
                style = LogbookTheme.type.data.copy(
                    fontSize = STAT_SIZE,
                    lineHeight = STAT_LEADING,
                    fontWeight = FontWeight.Medium,
                ),
                color = palette.ink,
            )
            if (constant.unit.isNotEmpty()) {
                Text(
                    text = constant.unit,
                    style = LogbookTheme.type.meta,
                    color = palette.inkSoft,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }
        }
        Text(text = constant.note, style = LogbookTheme.type.meta, color = palette.inkSoft)
    }
}

private val SECTION_GAP = 26.dp
private val SCREEN_BOTTOM = 40.dp

private val STAT_SIZE = 24.sp
private val STAT_LEADING = 26.sp
