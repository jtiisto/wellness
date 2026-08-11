package dev.jtiisto.wellness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.wellness.bootWellness
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.di.HrCaptureStateQualifier
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.LocalModuleAccent
import dev.jtiisto.wellness.core.ui.theme.ModuleAccent
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.ui.theme.colors
import dev.jtiisto.wellness.feature.analysis.ui.AnalysisScreen
import dev.jtiisto.wellness.feature.coach.CoachScreen
import dev.jtiisto.wellness.feature.journal.JournalTab
import dev.jtiisto.wellness.feature.trends.ui.TrendsScreen
import dev.jtiisto.wellness.hr.CaptureNotices
import dev.jtiisto.wellness.ui.tools.ToolsScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.getKoin
import org.koin.compose.koinInject

private const val JOURNAL_ROUTE = "journal"
private const val COACH_ROUTE = "coach"
private const val TRENDS_ROUTE = "trends"
private const val ANALYSIS_ROUTE = "analysis"
private const val TOOLS_ROUTE = "tools"

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val accent: ModuleAccent,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(JOURNAL_ROUTE, "Journal", Icons.Filled.Checklist, ModuleAccent.JOURNAL),
    TopLevelDestination(COACH_ROUTE, "Coach", Icons.Filled.FitnessCenter, ModuleAccent.COACH),
    TopLevelDestination(TRENDS_ROUTE, "Trends", Icons.Filled.Insights, ModuleAccent.TRENDS),
    TopLevelDestination(ANALYSIS_ROUTE, "Analysis", Icons.Filled.Analytics, ModuleAccent.ANALYSIS),
    TopLevelDestination(TOOLS_ROUTE, "Tools", Icons.Filled.Settings, ModuleAccent.TOOLS),
)

@Composable
fun WellnessApp() {
    WellnessTheme {
        val koin = getKoin()
        val resolution by koin.get<ServerBootstrap>().state.collectAsStateWithLifecycle()
        // Fail-closed: with no resolved server there is no HTTP client and no
        // scheduler to render a tab against, and falling back to the built-in
        // address would sync the previous server's data to it. See
        // [ServerRecoveryScreen].
        (resolution as? ServerResolution.Failed)?.let { failure ->
            ServerRecoveryScreen(message = failure.message, onRetry = { bootWellness(koin) })
            return@WellnessTheme
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val snackbarHostState = remember { SnackbarHostState() }
        val palette = WellnessTheme.palette

        // Server-side sync failures, shown once each. The events arrive on a
        // channel rather than as state, so a rotation cannot re-raise a
        // snackbar for a sync that failed minutes ago.
        val syncErrors = koinInject<SyncErrorEvents>()
        LaunchedEffect(syncErrors) {
            syncErrors.messages.collect { message ->
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            }
        }

        // Capture faults onto the same channel. The service publishes state, not
        // events, so a fault has to be noticed rather than received — that edge
        // detection is [CaptureNotices], and this is the collector that feeds it.
        // Pulse-bridge's equivalent wrote `state.error` and nothing ever read it.
        val captureState = koin.get<MutableStateFlow<HrCaptureState>>(HrCaptureStateQualifier)
        LaunchedEffect(captureState, syncErrors) {
            val notices = CaptureNotices()
            captureState.collect { state ->
                notices.noticeFor(state)?.let(syncErrors::postMessage)
            }
        }

        Scaffold(
            // The graphite canvas runs full-bleed behind the translucent system
            // bars; the insets below keep the content itself clear of them.
            containerColor = palette.canvas,
            contentColor = palette.textPrimary,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = palette.chrome,
                    contentColor = palette.textSecondary,
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            color = palette.line,
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    },
                ) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        val accent = destination.accent.colors(palette)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = accent.text,
                                selectedTextColor = accent.text,
                                unselectedIconColor = palette.textSecondary,
                                unselectedTextColor = palette.textSecondary,
                                // The M3 pill would put a fifth surface tone in
                                // the chrome; the underline below says the same
                                // thing in the module's own colour.
                                indicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.selectionUnderline(selected, accent.text),
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = JOURNAL_ROUTE,
                modifier = Modifier.padding(innerPadding),
                // Fade-through between tabs: they are siblings, so nothing
                // should slide in from a direction that implies hierarchy.
                enterTransition = { WellnessMotion.enterFadeThrough },
                exitTransition = { WellnessMotion.exitFadeThrough },
                popEnterTransition = { WellnessMotion.enterFadeThrough },
                popExitTransition = { WellnessMotion.exitFadeThrough },
            ) {
                topLevelDestinations.forEach { destination ->
                    composable(destination.route) {
                        // One accent per tab, provided at its root: nothing below
                        // here has to know which module it is drawing.
                        CompositionLocalProvider(LocalModuleAccent provides destination.accent) {
                            when (destination.route) {
                                JOURNAL_ROUTE -> JournalTab()
                                COACH_ROUTE -> CoachScreen()
                                TRENDS_ROUTE -> TrendsScreen()
                                ANALYSIS_ROUTE -> AnalysisScreen(snackbarHostState)
                                TOOLS_ROUTE -> ToolsScreen(snackbarHostState)
                                else -> StubScreen(destination.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 14×3dp accent bar under the active tab.
 *
 * Drawn as an overlay on the stock `NavigationBarItem` rather than as a custom
 * nav component: the accessibility surface — role, selected state, merged
 * label — is the one part of the nav bar worth keeping exactly as Material
 * ships it.
 */
private fun Modifier.selectionUnderline(selected: Boolean, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (!selected) return@drawWithContent
        val width = 14.dp.toPx()
        val height = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - width) / 2f, size.height - height),
            size = Size(width, height),
            cornerRadius = CornerRadius(height / 2f),
        )
    }

@Composable
private fun StubScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = WellnessTheme.type.headline,
            color = WellnessTheme.palette.textFaint,
        )
    }
}
