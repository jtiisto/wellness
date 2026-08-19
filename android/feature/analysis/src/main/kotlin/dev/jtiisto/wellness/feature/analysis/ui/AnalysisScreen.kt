package dev.jtiisto.wellness.feature.analysis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.analysis.AnalysisView
import dev.jtiisto.wellness.core.ui.theme.InkTab
import dev.jtiisto.wellness.core.ui.theme.InkTabRow
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import dev.jtiisto.wellness.feature.analysis.AnalysisViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * The Analysis tab.
 *
 * Four sub-views behind one switcher, all of them store state rather than nav
 * routes — the poll has to outlive every one of them, and a back stack that
 * could pop a report out from under a running query would be exactly the bug
 * this design exists to avoid.
 *
 * The module's snackbars are **not** collected here. The poll outlives this
 * composition, so its events do too: collected on the screen, a report
 * finishing while the user is in Journal would sit in the channel until they
 * next opened this tab and then announce itself minutes late. `WellnessApp`
 * collects them at the shell, where `SyncErrorEvents` is already collected.
 */
@Composable
fun AnalysisScreen(modifier: Modifier = Modifier) {
    val viewModel: AnalysisViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.initialize() }

    // Back walks the sub-views, never the poll: leaving the progress screen is
    // not abandoning the query, and only Cancel says otherwise. On the query
    // grid the gesture falls through to the shell, as it does on every tab.
    BackHandler(enabled = state.view != AnalysisView.QUERIES) { viewModel.openQueries() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        AnalysisHeader(
            eyebrow = AnalysisUiLogic.headerEyebrow(
                view = state.view,
                queryCount = state.queries.size,
                historyCount = state.history.size,
                queriesError = state.queriesError,
            ),
            view = state.view,
            onRun = viewModel::openQueries,
            onHistory = viewModel::openHistory,
        )

        val content = Modifier.weight(1f)
        when {
            state.isLoading -> LoadingLine(content)

            state.view == AnalysisView.PROGRESS -> LogbookSection(
                title = "Running",
                sub = AnalysisUiLogic.progressSub(state.active),
                modifier = content,
            ) {
                ProgressView(
                    active = state.active,
                    unknownStalled = state.unknownStalled,
                    onCancel = viewModel::cancelActive,
                    onRecheck = viewModel::recheck,
                )
            }

            // The reading slot, never the polling one: a query finishing in the
            // background must not swap the page out from under the reader.
            state.view == AnalysisView.REPORT -> ReportViewScreen(
                report = state.viewing,
                queries = state.queries,
                zone = viewModel.zone,
                onTryAgain = viewModel::onTryAgain,
                modifier = content,
            )

            state.view == AnalysisView.HISTORY -> LogbookSection(
                title = "Past reports",
                sub = AnalysisUiLogic.historySub(state.history.size),
                modifier = content,
            ) {
                HistoryList(
                    history = state.history,
                    zone = viewModel.zone,
                    onOpen = viewModel::openReport,
                    onDelete = viewModel::askDelete,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> LogbookSection(
                title = "Queries",
                sub = AnalysisUiLogic.queriesSub(state.queries.size),
                modifier = content,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
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
    }

    if (ui.confirmDeleteId != null) {
        DeleteReportDialog(
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/**
 * Eyebrow, title, and the two-way switch the PWA had.
 *
 * "Run" covers the query grid, the progress screen and a report: they are one
 * flow, and the tab should not appear to change under the user when a query
 * finishes.
 */
@Composable
private fun AnalysisHeader(
    eyebrow: String,
    view: AnalysisView,
    onRun: () -> Unit,
    onHistory: () -> Unit,
) {
    val tabs = remember { listOf(InkTab(TAB_RUN, "Run"), InkTab(TAB_HISTORY, "Past reports")) }
    Column(modifier = Modifier.padding(top = LogbookSpace.grid * 2)) {
        Text(
            text = eyebrow.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = LogbookTheme.palette.inkSoft,
        )
        Text(
            text = TITLE.uppercase(),
            style = LogbookTheme.type.display,
            color = LogbookTheme.palette.ink,
            modifier = Modifier.padding(top = LogbookSpace.grid, bottom = LogbookSpace.grid * 2),
        )
        InkTabRow(
            tabs = tabs,
            selectedId = if (view == AnalysisView.HISTORY) TAB_HISTORY else TAB_RUN,
            onSelect = { id -> if (id == TAB_HISTORY) onHistory() else onRun() },
        )
    }
}

/**
 * Loading, in the voice reserved for absence.
 *
 * A spinner would be the only moving thing on a page whose premise is that
 * nothing moves — and the wait here is the store settling, not a query running.
 * The one place that genuinely waits on the server draws the clock and the
 * sweep instead ([ProgressView]).
 */
@Composable
private fun LoadingLine(modifier: Modifier = Modifier) {
    EmptyLine(text = "Loading…", modifier = modifier.padding(top = LogbookSpace.grid * 2))
}

private const val TITLE = "Analysis"
private const val TAB_RUN = "run"
private const val TAB_HISTORY = "history"

/** The page's own margin — the screen is the surface, so nothing insets inside it. */
internal val SCREEN_PADDING = 20.dp
private val SECTION_GAP = 22.dp
