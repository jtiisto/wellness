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
import dev.jtiisto.wellness.core.data.trends.RecoveryDay
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.trends.HealthViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.BoneRow
import dev.jtiisto.wellness.feature.trends.chart.LabRowModel
import dev.jtiisto.wellness.feature.trends.chart.LegendEntry
import dev.jtiisto.wellness.feature.trends.chart.MiniLabModel
import dev.jtiisto.wellness.feature.trends.chart.MiniMetricModel
import dev.jtiisto.wellness.feature.trends.chart.NO_HRV_TEXT
import dev.jtiisto.wellness.feature.trends.chart.NO_RHR_TEXT
import dev.jtiisto.wellness.feature.trends.chart.NO_SLEEP_TEXT
import dev.jtiisto.wellness.feature.trends.chart.PlotTone
import dev.jtiisto.wellness.feature.trends.chart.bodyCardModel
import dev.jtiisto.wellness.feature.trends.chart.compositionCardModel
import dev.jtiisto.wellness.feature.trends.chart.hrvCardModel
import dev.jtiisto.wellness.feature.trends.chart.labsSectionModel
import dev.jtiisto.wellness.feature.trends.chart.rhrCardModel
import dev.jtiisto.wellness.feature.trends.chart.sleepCardModel
import dev.jtiisto.wellness.feature.trends.staleStamps
import dev.jtiisto.wellness.feature.trends.valueOrNull
import org.koin.androidx.compose.koinViewModel

/** How much taller than its strip a mini chart's gesture area reaches. */
private val STRIP_TOUCH_PADDING = 8.dp

private val MEANS_LEGEND = listOf(
    LegendEntry("7d mean", PlotTone.PRIMARY),
    LegendEntry("28d mean", PlotTone.ALT),
)

private val SLEEP_LEGEND = listOf(
    LegendEntry("hours", PlotTone.PRIMARY),
    LegendEntry("score", PlotTone.SECONDARY),
)

/**
 * Health: recovery, body composition and labs.
 *
 * Recovery is the only slice whose failure is the screen's failure. Weight,
 * DEXA scans and lab reports come from sources a given install may simply not
 * have, so their cards are absent rather than broken — but any of them serving
 * a cached copy still counts toward the stale badge, which is one thing this
 * screen does better than the PWA.
 */
@Composable
fun HealthTrendsScreen(onRange: (String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: HealthViewModel = koinViewModel()
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
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.md),
    ) {
        RangeToolbar(
            range = state.range,
            staleStamps = staleStamps(state.recovery, state.weight, state.composition, state.labs),
            onRange = onRange,
        )

        when (val slice = state.recovery) {
            is Slice.Error -> ScreenError(slice.text, viewModel::retry)
            Slice.Loading -> ScreenLoading()
            is Slice.Ready ->
                if (!slice.value.available) {
                    ChartEmpty("Garmin data unavailable")
                } else {
                    RecoveryCards(slice.value.days, state.range, pinEpoch)
                }
        }

        val weight = state.weight.valueOrNull
        val scans = state.composition.valueOrNull?.takeIf { it.available }?.scans.orEmpty()
        if (weight != null && weight.available && weight.series.isNotEmpty()) {
            val card = remember(weight, scans) { bodyCardModel(weight.series, scans) }
            if (card != null) {
                TrendsCard(title = "Body weight + DEXA", unit = "kg · 7d mean · scan total mass") {
                    PlotCanvas(model = card.plot, identity = listOf("body", state.range, pinEpoch))
                    LegendRow(card.legend)
                }
            }
        }

        if (scans.isNotEmpty()) {
            val card = remember(scans) { compositionCardModel(scans) }
            if (card != null) {
                TrendsCard(title = "Composition", unit = "DEXA · all scans") {
                    for (metric in card.metrics) MiniMetric(metric, pinEpoch)
                    PlotCanvas(
                        model = card.axis,
                        identity = listOf("compositionAxis", scans.size),
                        scrubEnabled = false,
                    )
                    for (row in card.bone) BoneTableRow(row)
                }
            }
        }

        val labs = state.labs.valueOrNull
        if (labs != null && labs.available && labs.panels.isNotEmpty()) {
            val section = remember(labs, state.labPanel) { labsSectionModel(labs.panels, state.labPanel) }
            if (section != null) {
                TrendsCard(title = "Labs", unit = "all reports") {
                    PillSelect(
                        title = "Panel",
                        options = section.panelOptions,
                        value = section.selectedPanel,
                        onChange = viewModel::selectLabPanel,
                        onOpen = { pinEpoch++ },
                    )
                    for (chart in section.charts) MiniLab(chart, section.selectedPanel, pinEpoch)
                    for (row in section.rows) LabTableRow(row)
                }
            }
        }

        Box(modifier = Modifier.height(WellnessSpace.lg))
    }
}

/**
 * The three recovery cards.
 *
 * Each renders its card unconditionally and puts its empty-state line inside:
 * a night the watch was off is a fact about that night, not a reason to hide
 * the whole card.
 */
@Composable
private fun RecoveryCards(days: List<RecoveryDay>, range: String, pinEpoch: Int) {
    TrendsCard(title = "HRV", unit = "ms · last night · 7d mean · Garmin baseline") {
        val model = remember(days) { hrvCardModel(days) }
        if (model == null) {
            ChartEmpty(NO_HRV_TEXT)
        } else {
            PlotCanvas(model = model, identity = listOf("hrv", range, pinEpoch))
        }
    }
    TrendsCard(title = "Resting HR", unit = "bpm · 7d & 28d means") {
        val model = remember(days) { rhrCardModel(days) }
        if (model == null) {
            ChartEmpty(NO_RHR_TEXT)
        } else {
            PlotCanvas(model = model, identity = listOf("rhr", range, pinEpoch))
            LegendRow(MEANS_LEGEND)
        }
    }
    TrendsCard(title = "Sleep", unit = "hours · score (right) · 8h guide") {
        val model = remember(days) { sleepCardModel(days) }
        if (model == null) {
            ChartEmpty(NO_SLEEP_TEXT)
        } else {
            PlotCanvas(model = model, identity = listOf("sleep", range, pinEpoch))
            LegendRow(SLEEP_LEGEND)
        }
    }
}

@Composable
private fun MiniMetric(metric: MiniMetricModel, pinEpoch: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = metric.label,
            style = WellnessTheme.type.micro,
            color = WellnessTheme.palette.textSecondary,
            modifier = Modifier.weight(0.28f),
        )
        Box(modifier = Modifier.weight(0.52f)) {
            PlotCanvas(
                model = metric.plot,
                identity = listOf("mini", metric.label, pinEpoch),
                touchPadding = STRIP_TOUCH_PADDING,
            )
        }
        Text(
            text = metric.latest,
            style = WellnessTheme.type.label,
            color = WellnessTheme.palette.textPrimary,
            modifier = Modifier.weight(0.20f),
        )
    }
}

@Composable
private fun MiniLab(chart: MiniLabModel, panel: String, pinEpoch: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chart.name, style = WellnessTheme.type.micro, color = WellnessTheme.palette.textSecondary)
            Text(chart.latest, style = WellnessTheme.type.label, color = WellnessTheme.palette.textPrimary)
        }
        PlotCanvas(
            model = chart.plot,
            identity = listOf("lab", panel, chart.name, pinEpoch),
            touchPadding = STRIP_TOUCH_PADDING,
        )
    }
}

@Composable
private fun BoneTableRow(row: BoneRow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Bone (total)", style = WellnessTheme.type.body, color = WellnessTheme.palette.textPrimary)
            Text(row.date, style = WellnessTheme.type.micro, color = WellnessTheme.palette.textFaint)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.bmd, style = WellnessTheme.type.label, color = WellnessTheme.palette.textPrimary)
            Text(row.tScore, style = WellnessTheme.type.micro, color = WellnessTheme.palette.textFaint)
        }
    }
}

@Composable
private fun LabTableRow(row: LabRowModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = WellnessTheme.type.body, color = WellnessTheme.palette.textPrimary)
            Text(row.subLabel, style = WellnessTheme.type.micro, color = WellnessTheme.palette.textFaint)
        }
        Text(
            text = row.value,
            style = WellnessTheme.type.label,
            color = if (row.flagged) WellnessTheme.palette.warning else WellnessTheme.palette.textPrimary,
        )
    }
}
