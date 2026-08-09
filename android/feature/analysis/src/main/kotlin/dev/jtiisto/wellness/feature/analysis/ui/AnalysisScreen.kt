package dev.jtiisto.wellness.feature.analysis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.analysis.AnalysisView
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * The Analysis tab.
 *
 * Four sub-views behind one switcher, all of them store state rather than nav
 * routes — the poll has to outlive every one of them, and a back stack that
 * could pop a report out from under a running query would be exactly the bug
 * this design exists to avoid.
 */
@Composable
fun AnalysisScreen(snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val viewModel: AnalysisViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.initialize() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    // Back walks the sub-views, never the poll: leaving the progress screen is
    // not abandoning the query, and only Cancel says otherwise. On the query
    // grid the gesture falls through to the shell, as it does on every tab.
    BackHandler(enabled = state.view != AnalysisView.QUERIES) { viewModel.openQueries() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = WellnessSpace.card),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        AnalysisHeader(
            view = state.view,
            onRun = viewModel::openQueries,
            onHistory = viewModel::openHistory,
        )

        val content = Modifier.weight(1f)
        when {
            state.isLoading -> LoadingState(content)

            state.view == AnalysisView.PROGRESS -> ProgressView(
                active = state.active,
                unknownStalled = state.unknownStalled,
                onCancel = viewModel::cancelActive,
                onRecheck = viewModel::recheck,
                modifier = content,
            )

            // The reading slot, never the polling one: a query finishing in the
            // background must not swap the page out from under the reader.
            state.view == AnalysisView.REPORT -> ReportViewScreen(
                report = state.viewing,
                queries = state.queries,
                zone = viewModel.zone,
                onTryAgain = viewModel::onTryAgain,
                modifier = content,
            )

            state.view == AnalysisView.HISTORY -> HistoryList(
                history = state.history,
                zone = viewModel.zone,
                onOpen = viewModel::openReport,
                onDelete = viewModel::askDelete,
                modifier = content,
            )

            else -> Column(modifier = content.verticalScroll(rememberScrollState())) {
                QueryList(
                    queries = state.queries,
                    expandedQueryId = ui.expandedQueryId,
                    locations = ui.locations,
                    submitInFlight = state.submitInFlight,
                    queriesError = state.queriesError,
                    onTap = viewModel::onQueryTap,
                    onRun = viewModel::onRun,
                    onLocationChange = viewModel::onLocationChange,
                )
            }
        }
    }

    if (ui.confirmDeleteId != null) {
        DeleteReportDialog(
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/**
 * Title plus the two-way switch the PWA had.
 *
 * "Run" covers the query grid, the progress screen and a report: they are one
 * flow, and the tab should not appear to change under the user when a query
 * finishes.
 */
@Composable
private fun AnalysisHeader(view: AnalysisView, onRun: () -> Unit, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
        Text(
            text = "Analysis",
            style = WellnessTheme.type.headline,
            color = WellnessTheme.palette.textPrimary,
            modifier = Modifier.padding(top = WellnessSpace.md),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs)) {
            SwitchTab(label = "Run", selected = view != AnalysisView.HISTORY, onClick = onRun)
            SwitchTab(label = "Past Reports", selected = view == AnalysisView.HISTORY, onClick = onHistory)
        }
    }
}

@Composable
private fun SwitchTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Box(
        modifier = Modifier
            .clip(WellnessShape.pill)
            .background(if (selected) accent.softFill else palette.band)
            .border(
                WellnessSpace.hairline,
                if (selected) accent.border else palette.line,
                WellnessShape.pill,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = WellnessSpace.md, vertical = TAB_PADDING),
    ) {
        Text(
            text = label,
            style = WellnessTheme.type.label,
            color = if (selected) accent.text else palette.textSecondary,
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = WellnessTheme.accent.fill,
            trackColor = WellnessTheme.palette.line,
            modifier = Modifier.size(SPINNER_SIZE),
        )
    }
}

private val TAB_PADDING = 8.dp
private val SPINNER_SIZE = 36.dp
