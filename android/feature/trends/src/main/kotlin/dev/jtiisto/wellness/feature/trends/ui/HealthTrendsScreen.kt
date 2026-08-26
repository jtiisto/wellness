package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.trends.RecoveryDay
import dev.jtiisto.wellness.core.data.trends.sleepTonightModel
import dev.jtiisto.wellness.core.ui.SleepTonightCard
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.HealthViewModel
import dev.jtiisto.wellness.feature.trends.Slice
import dev.jtiisto.wellness.feature.trends.chart.BoneRow
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.FLAG_GLYPH
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
import dev.jtiisto.wellness.feature.trends.chart.latestIsFlagged
import dev.jtiisto.wellness.feature.trends.chart.rhrCardModel
import dev.jtiisto.wellness.feature.trends.chart.sleepCardModel
import dev.jtiisto.wellness.feature.trends.chart.sleepDebtSection
import dev.jtiisto.wellness.feature.trends.staleFetchedAt
import dev.jtiisto.wellness.feature.trends.staleStamps
import dev.jtiisto.wellness.feature.trends.valueOrNull
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

/** How much taller than its strip a mini chart's gesture area reaches. */
private val STRIP_TOUCH_PADDING = 8.dp

private val HRV_LEGEND = listOf(
    LegendEntry("daily", PlotTone.VALUE),
    LegendEntry("7d mean", PlotTone.PRIMARY),
    LegendEntry("baseline", PlotTone.BAND),
    LegendEntry("below floor", PlotTone.WARN),
)

private val MEANS_LEGEND = listOf(
    LegendEntry("daily", PlotTone.VALUE),
    LegendEntry("7d mean", PlotTone.PRIMARY),
    LegendEntry("28d mean", PlotTone.ALT),
)

private val SLEEP_LEGEND = listOf(
    LegendEntry("hours", PlotTone.PRIMARY),
    LegendEntry("score", PlotTone.SECONDARY),
    LegendEntry("8h guide", PlotTone.MUTED),
)

/**
 * Health: tonight's sleep need, recovery, body composition and labs.
 *
 * Recovery is the only slice whose failure is the screen's failure. The sleep
 * ledger, weight, DEXA scans and lab reports come from sources a given install
 * may simply not have, so their sections are absent rather than broken — but
 * any of them serving a cached copy still counts toward the stale note, which
 * is one thing this screen does better than the PWA.
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
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        RangeToolbar(
            range = state.range,
            staleStamps = staleStamps(
                state.recovery,
                state.sleep,
                state.weight,
                state.composition,
                state.labs,
            ),
            onRange = onRange,
        )

        // Directly under the toolbar and above the charts: it is the one thing
        // on this screen that is about *tonight* rather than about a window,
        // and the range segments over it say what the rest of the page covers.
        // Computed at every composition like the stale caption is — never
        // remembered, so the cached-copy age and the today comparison can't
        // freeze on a stale clock while the slice object stays identical.
        val tonight = sleepTonightModel(
            dto = state.sleep.valueOrNull,
            staleFetchedAt = state.sleep.staleFetchedAt,
            now = System.currentTimeMillis(),
            today = LocalDate.now().toString(),
        )
        tonight?.let { SleepTonightCard(it) }

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

        val sleep = state.sleep.valueOrNull
        if (sleep != null && sleep.available && sleep.days.isNotEmpty()) {
            val section = remember(sleep) { sleepDebtSection(sleep.days, sleep.tonight) }
            if (section != null) {
                LogbookSection(
                    title = "Sleep need",
                    sub = "h · need vs slept",
                    trailing = {
                        section.latest?.let {
                            Text(
                                text = it,
                                style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                                color = LogbookTheme.palette.ink,
                                modifier = Modifier.padding(start = LogbookSpace.grid * 2),
                            )
                        }
                    },
                ) {
                    LegendRow(section.needLegend, chart = ChartInk.SLEEP_NEED)
                    PlotCanvas(
                        model = section.needPlot,
                        identity = listOf("sleepNeed", state.range, pinEpoch),
                        chart = ChartInk.SLEEP_NEED,
                    )
                    section.debtPlot?.let { plot ->
                        // The debt rides its own canvas rather than a second
                        // axis on the first: it is a different quantity in the
                        // same unit, and stacking them would invite reading a
                        // debt line as a shorter night.
                        LegendRow(section.debtLegend)
                        PlotCanvas(
                            model = plot,
                            identity = listOf("sleepDebt", state.range, pinEpoch),
                        )
                    }
                }
            }
        }

        val weight = state.weight.valueOrNull
        val scans = state.composition.valueOrNull?.takeIf { it.available }?.scans.orEmpty()
        if (weight != null && weight.available && weight.series.isNotEmpty()) {
            val card = remember(weight, scans) { bodyCardModel(weight.series, scans) }
            if (card != null) {
                LogbookSection(title = "Body", sub = "kg") {
                    LegendRow(card.legend, chart = ChartInk.BODY)
                    PlotCanvas(
                        model = card.plot,
                        identity = listOf("body", state.range, pinEpoch),
                        chart = ChartInk.BODY,
                    )
                }
            }
        }

        if (scans.isNotEmpty()) {
            val card = remember(scans) { compositionCardModel(scans) }
            if (card != null) {
                LogbookSection(title = "Composition", sub = "DEXA · all scans") {
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
                LogbookSection(title = "Labs", sub = "ref band from latest") {
                    PickerField(
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

        Box(modifier = Modifier.height(SCREEN_BOTTOM))
    }
}

/**
 * The three recovery sections.
 *
 * Each renders its head unconditionally and puts its empty-state line inside: a
 * night the watch was off is a fact about that night, not a reason to hide the
 * whole section.
 */
@Composable
private fun RecoveryCards(days: List<RecoveryDay>, range: String, pinEpoch: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        LogbookSection(title = "HRV", sub = "ms · overnight") {
            val model = remember(days) { hrvCardModel(days) }
            if (model == null) {
                ChartEmpty(NO_HRV_TEXT)
            } else {
                LegendRow(HRV_LEGEND)
                PlotCanvas(model = model, identity = listOf("hrv", range, pinEpoch))
            }
        }
        LogbookSection(title = "Resting HR", sub = "bpm") {
            val model = remember(days) { rhrCardModel(days) }
            if (model == null) {
                ChartEmpty(NO_RHR_TEXT)
            } else {
                LegendRow(MEANS_LEGEND)
                PlotCanvas(model = model, identity = listOf("rhr", range, pinEpoch))
            }
        }
        LogbookSection(title = "Sleep", sub = "h · score right") {
            val model = remember(days) { sleepCardModel(days) }
            if (model == null) {
                ChartEmpty(NO_SLEEP_TEXT)
            } else {
                LegendRow(SLEEP_LEGEND, chart = ChartInk.SLEEP)
                PlotCanvas(
                    model = model,
                    identity = listOf("sleep", range, pinEpoch),
                    chart = ChartInk.SLEEP,
                )
            }
        }
    }
}

/** A composition strip: name, the shape of it, and where it stands now. */
@Composable
private fun MiniMetric(metric: MiniMetricModel, pinEpoch: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBelow(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = metric.label,
            style = LogbookTheme.type.name,
            color = LogbookTheme.palette.ink,
            modifier = Modifier.weight(0.30f),
        )
        Box(modifier = Modifier.weight(0.50f)) {
            PlotCanvas(
                model = metric.plot,
                identity = listOf("mini", metric.label, pinEpoch),
                touchPadding = STRIP_TOUCH_PADDING,
            )
        }
        Text(
            text = metric.latest,
            style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
            color = LogbookTheme.palette.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.20f),
        )
    }
}

/**
 * A lab strip. The `!` is the whole of "the lab flagged this" — read off the
 * chart's own last dot, so the glyph and the open mark above it cannot disagree.
 */
@Composable
private fun MiniLab(chart: MiniLabModel, panel: String, pinEpoch: Int) {
    val flagged = remember(chart) { latestIsFlagged(chart.plot) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chart.name, style = LogbookTheme.type.name, color = LogbookTheme.palette.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flagged) FlagGlyph()
                Text(
                    text = chart.latest,
                    style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                    color = LogbookTheme.palette.ink,
                )
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBelow(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Bone (total)", style = LogbookTheme.type.name, color = LogbookTheme.palette.ink)
            Text(row.date, style = LogbookTheme.type.meta, color = LogbookTheme.palette.inkSoft)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.bmd,
                style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                color = LogbookTheme.palette.ink,
            )
            Text(row.tScore, style = LogbookTheme.type.meta, color = LogbookTheme.palette.inkSoft)
        }
    }
}

/**
 * One non-chartable test.
 *
 * A flagged row is marked, not tinted: the `!` says the lab called it out, and
 * saying so in red would be this system claiming a verdict the lab did not give.
 */
@Composable
private fun LabTableRow(row: LabRowModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBelow(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.flagged) FlagGlyph()
                Text(row.name, style = LogbookTheme.type.name, color = LogbookTheme.palette.ink)
            }
            Text(row.subLabel, style = LogbookTheme.type.meta, color = LogbookTheme.palette.inkSoft)
        }
        Text(
            text = row.value,
            style = LogbookTheme.type.meta.copy(
                fontWeight = if (row.flagged) FontWeight.Medium else FontWeight.Normal,
            ),
            color = LogbookTheme.palette.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = LogbookSpace.grid * 2),
        )
    }
}

/**
 * The attention mark, spoken once.
 *
 * Its own semantics node rather than a silent glyph: `!` is the only thing on
 * the row saying the lab flagged the result, so a reader who cannot see it has
 * to be told in words.
 */
@Composable
private fun FlagGlyph() {
    Text(
        text = FLAG_GLYPH,
        style = LogbookTheme.type.meta.copy(fontWeight = FontWeight.Medium),
        color = LogbookTheme.palette.ink,
        modifier = Modifier
            .width(FLAG_COLUMN)
            .clearAndSetSemantics { contentDescription = "Flagged" },
    )
}

/** The row rule every table on this screen sits on. */
@Composable
private fun Modifier.hairlineBelow(): Modifier {
    val rule = LogbookTheme.palette.rule
    return this
        .drawBehind {
            val stroke = LogbookSpace.hairline.toPx()
            drawLine(
                color = rule,
                start = Offset(0f, size.height - stroke / 2f),
                end = Offset(size.width, size.height - stroke / 2f),
                strokeWidth = stroke,
            )
        }
        .padding(vertical = ROW_PADDING)
}

private val SECTION_GAP = 26.dp
private val SCREEN_BOTTOM = 40.dp
private val ROW_PADDING = 9.dp
private val FLAG_COLUMN = 12.dp
