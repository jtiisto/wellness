package dev.jtiisto.wellness.ui.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.sync.DebugLogLogic
import dev.jtiisto.wellness.core.ui.chart.ChartScrubTooltip
import dev.jtiisto.wellness.core.ui.chart.chartScrub
import dev.jtiisto.wellness.core.ui.chart.rememberChartScrubState
import dev.jtiisto.wellness.core.ui.chart.rememberChartTheme
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun ToolsScreen(viewModel: ToolsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ToolsContent(state = state, onPing = viewModel::pingServer)
}

@Composable
private fun ToolsContent(state: ToolsUiState, onPing: () -> Unit) {
    val palette = WellnessTheme.palette
    Column(modifier = Modifier.fillMaxSize()) {
        ServerStatusCard(
            state = state,
            onPing = onPing,
            modifier = Modifier.padding(horizontal = WellnessSpace.md, vertical = 12.dp),
        )

        ChartScrubDemoCard(modifier = Modifier.padding(horizontal = WellnessSpace.md))

        PlaceholderRow("Force sync")
        PlaceholderRow("Export all data")

        HorizontalDivider(color = palette.line, modifier = Modifier.padding(top = WellnessSpace.sm))
        Text(
            text = "Debug log",
            style = WellnessTheme.type.title,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = WellnessSpace.md, vertical = 12.dp),
        )
        DebugLogList(entries = state.log, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ServerStatusCard(state: ToolsUiState, onPing: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    ToolsCard(modifier = modifier) {
        Text("Server status", style = WellnessTheme.type.title, color = palette.textPrimary)
        Text(
            text = state.baseUrl,
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
            fontFamily = FontFamily.Monospace,
        )
        Button(
            onClick = onPing,
            enabled = state.ping != ServerPing.InFlight,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentButtonColors(),
        ) {
            Text("Ping journal sync status", style = WellnessTheme.type.label)
        }
        when (val ping = state.ping) {
            ServerPing.Idle -> Unit
            ServerPing.InFlight -> CircularProgressIndicator(
                color = WellnessTheme.accent.fill,
                trackColor = palette.line,
            )
            is ServerPing.Reached -> Text(
                text = "lastModified: ${ping.lastModified ?: "(none)"}",
                style = WellnessTheme.type.secondary,
                color = palette.textPrimary,
                fontFamily = FontFamily.Monospace,
            )
            is ServerPing.Failed -> Text(
                text = ping.message,
                style = WellnessTheme.type.secondary,
                color = palette.error,
            )
        }
    }
}

/**
 * The chart foundation, wired to a synthetic series.
 *
 * Phase 6 builds Trends on `ChartScrubState`; this card is where the contract —
 * drag to scrub, tap to pin, tap out to dismiss — is proved on a device before
 * there is a real chart to prove it on. The numbers are invented and go
 * nowhere: nothing here is stored, synced, or read from the journal.
 */
@Composable
private fun ChartScrubDemoCard(modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val chart = rememberChartTheme()
    val scrub = rememberChartScrubState()
    var anchors by remember { mutableStateOf(FloatArray(0)) }
    var plotHeight by remember { mutableStateOf(0f) }

    // Path-trim draw-in, once, on entry.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val drawn by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = WellnessMotion.draw(),
        label = "chart-draw-in",
    )

    ToolsCard(modifier = modifier) {
        Text("Chart scrub demo", style = WellnessTheme.type.title, color = palette.textPrimary)
        Text(
            text = "Drag across the line to scrub; tap a point to pin it.",
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .onSizeChanged { size ->
                    plotHeight = size.height.toFloat()
                    val step = if (DEMO_SERIES.size > 1) {
                        size.width.toFloat() / (DEMO_SERIES.size - 1)
                    } else {
                        0f
                    }
                    anchors = FloatArray(DEMO_SERIES.size) { index -> index * step }
                    scrub.updateAnchors(anchors)
                }
                .chartScrub(scrub),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (anchors.isEmpty()) return@Canvas
                val minValue = DEMO_SERIES.min()
                val maxValue = DEMO_SERIES.max()
                val span = (maxValue - minValue).takeIf { it > 0f } ?: 1f
                val inset = 8.dp.toPx()
                fun yFor(value: Float): Float =
                    size.height - inset - (value - minValue) / span * (size.height - 2 * inset)

                drawLine(
                    color = chart.grid,
                    start = Offset(0f, size.height - inset),
                    end = Offset(size.width, size.height - inset),
                    strokeWidth = chart.gridWidth.toPx(),
                )

                // Trim: whole segments first, then the fraction of the next one.
                val segments = (DEMO_SERIES.size - 1) * drawn
                val path = Path().apply {
                    moveTo(anchors[0], yFor(DEMO_SERIES[0]))
                    for (index in 1 until DEMO_SERIES.size) {
                        val remaining = segments - (index - 1)
                        if (remaining <= 0f) break
                        val fraction = remaining.coerceAtMost(1f)
                        val fromX = anchors[index - 1]
                        val fromY = yFor(DEMO_SERIES[index - 1])
                        lineTo(
                            fromX + (anchors[index] - fromX) * fraction,
                            fromY + (yFor(DEMO_SERIES[index]) - fromY) * fraction,
                        )
                    }
                }
                drawPath(
                    path = path,
                    color = chart.line,
                    style = Stroke(width = chart.lineWidth.toPx(), cap = StrokeCap.Round),
                )

                // The endpoint pops once the line has arrived.
                val pop = ((drawn - 0.9f) / 0.1f).coerceIn(0f, 1f)
                if (pop > 0f) {
                    drawCircle(
                        color = chart.point,
                        radius = chart.pointRadius.toPx() * pop,
                        center = Offset(anchors.last(), yFor(DEMO_SERIES.last())),
                    )
                }

                scrub.displayIndex?.let { index ->
                    val x = anchors[index]
                    drawLine(
                        color = chart.guide,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = chart.guideWidth.toPx(),
                    )
                    drawCircle(
                        color = chart.point,
                        radius = chart.pointRadius.toPx() * 1.5f,
                        center = Offset(x, yFor(DEMO_SERIES[index])),
                    )
                }
            }

            scrub.displayIndex?.let { index ->
                ChartScrubTooltip(
                    state = scrub,
                    label = DEMO_LABELS[index],
                    values = listOf("Score" to DEMO_SERIES[index].roundToInt().toString()),
                    // The point itself; the tooltip centres on it and keeps
                    // itself inside the window at either edge of the chart.
                    anchor = IntOffset(anchors[index].roundToInt(), -(plotHeight / 2f).roundToInt()),
                )
            }
        }
    }
}

/** `inline` for the same reason Compose's own layout wrappers are. */
@Composable
private inline fun ToolsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = WellnessTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(1.dp, palette.line, WellnessShape.card)
            .padding(WellnessSpace.md),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        content()
    }
}

/** A Phase 8 affordance, shown greyed out so the tab reads as unfinished. */
@Composable
private fun PlaceholderRow(label: String) {
    val palette = WellnessTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(DISABLED_ALPHA)
            .padding(horizontal = WellnessSpace.md, vertical = WellnessSpace.sm),
    ) {
        Text(label, style = WellnessTheme.type.body, color = palette.textPrimary)
        Text(
            text = "Arrives with the Tools parity phase",
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun DebugLogList(entries: List<DebugLogEntity>, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    if (entries.isEmpty()) {
        Text(
            text = "No entries in the last hour.",
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = WellnessSpace.md),
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = WellnessSpace.md, vertical = WellnessSpace.xs),
    ) {
        items(items = entries, key = DebugLogEntity::id) { entry ->
            Text(
                text = DebugLogLogic.formatDumpLine(entry),
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

/** Invented numbers with no source and no destination — see the card's note. */
private val DEMO_SERIES = floatArrayOf(12f, 18f, 15f, 24f, 21f, 30f, 27f).toList()
private val DEMO_LABELS = listOf("Week 1", "Week 2", "Week 3", "Week 4", "Week 5", "Week 6", "Week 7")
