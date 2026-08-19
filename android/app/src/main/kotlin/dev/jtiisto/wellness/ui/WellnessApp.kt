package dev.jtiisto.wellness.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
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

/**
 * What the Scaffold paints behind a destination.
 *
 * One page for every tab now — Logbook paper and its ink. It is still resolved
 * here rather than read off the palette at the callsite because the Scaffold's
 * canvas is what a tab switch shows for the frame before the new screen draws,
 * and that frame is the one thing about the shell worth pinning by test.
 */
@Immutable
internal data class ShellChrome(val canvas: Color, val content: Color)

internal fun shellChrome(isDark: Boolean): ShellChrome = ShellChrome(
    canvas = if (isDark) LogbookDark.paper else LogbookLight.paper,
    content = if (isDark) LogbookDark.ink else LogbookLight.ink,
)

@Immutable
internal data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

internal val topLevelDestinations = listOf(
    TopLevelDestination(route = JOURNAL_ROUTE, label = "Journal", icon = Icons.Outlined.Checklist),
    TopLevelDestination(route = COACH_ROUTE, label = "Coach", icon = Icons.Outlined.FitnessCenter),
    TopLevelDestination(route = TRENDS_ROUTE, label = "Trends", icon = Icons.Outlined.Insights),
    TopLevelDestination(route = ANALYSIS_ROUTE, label = "Analysis", icon = Icons.Outlined.Analytics),
    TopLevelDestination(route = TOOLS_ROUTE, label = "Tools", icon = Icons.Outlined.Settings),
)

/** The tab the app opens on; the nav graph reads it from here so the two cannot drift. */
internal val startTab = topLevelDestinations.first()

@Composable
fun WellnessApp() {
    LogbookTheme {
        val koin = getKoin()
        val resolution by koin.get<ServerBootstrap>().state.collectAsStateWithLifecycle()
        // Fail-closed: with no resolved server there is no HTTP client and no
        // scheduler to render a tab against, and falling back to the built-in
        // address would sync the previous server's data to it. See
        // [ServerRecoveryScreen].
        (resolution as? ServerResolution.Failed)?.let { failure ->
            ServerRecoveryScreen(message = failure.message, onRetry = { bootWellness(koin) })
            return@LogbookTheme
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val snackbarHostState = remember { SnackbarHostState() }
        val palette = LogbookTheme.palette

        // Server-side sync failures, shown once each. The events arrive on a
        // channel rather than as state, so a rotation cannot re-raise a
        // snackbar for a sync that failed minutes ago.
        val syncErrors = koinInject<SyncErrorEvents>()
        LaunchedEffect(syncErrors) {
            syncErrors.messages.collect { message ->
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            }
        }

        // Analysis raises its own events, and they are collected here rather
        // than on the tab for the reason the poll lives in the store: the work
        // outlives the composition. Collected on the screen, a report finishing
        // while the user is reading Journal announced itself whenever they next
        // opened Analysis — which could be minutes, and by then the snackbar is
        // about something they have stopped waiting for.
        val analysisEvents = koinInject<AnalysisEvents>()
        LaunchedEffect(analysisEvents) {
            analysisEvents.events.collect { event ->
                snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Short,
                )
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

        val chrome = shellChrome(palette.isDark)

        Scaffold(
            // The active destination's canvas runs full-bleed behind the
            // translucent system bars; the insets below keep the content itself
            // clear of them.
            containerColor = chrome.canvas,
            contentColor = chrome.content,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = palette.paper,
                    contentColor = palette.ink,
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            color = palette.rule,
                            size = Size(size.width, LogbookSpace.hairline.toPx()),
                        )
                    },
                ) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
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
                            // Caps mono: the tabs of a paper logbook. With a
                            // label present M3 drops the icon's description
                            // from the merged node, so TalkBack reads this
                            // text — five real words, whose pronunciation caps
                            // do not change.
                            label = {
                                Text(
                                    text = destination.label.uppercase(),
                                    style = LogbookTheme.type.eyebrow,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = palette.ink,
                                selectedTextColor = palette.ink,
                                unselectedIconColor = palette.inkSoft,
                                unselectedTextColor = palette.inkSoft,
                                // The M3 pill would be a second surface on a
                                // page that has only one; the underline below
                                // says the same thing in ink.
                                indicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.selectionUnderline(selected, palette.ink),
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startTab.route,
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
                        DestinationScreen(destination, snackbarHostState)
                    }
                }
            }
        }
    }
}

/**
 * The screen behind a tab.
 *
 * No per-destination theme wrapper any more: the shell established
 * [LogbookTheme] once at the top, and with Graphite Signal retired there is no
 * second system for a screen to re-establish.
 */
@Composable
private fun DestinationScreen(
    destination: TopLevelDestination,
    snackbarHostState: SnackbarHostState,
) {
    when (destination.route) {
        JOURNAL_ROUTE -> JournalTab()
        COACH_ROUTE -> CoachScreen()
        TRENDS_ROUTE -> TrendsScreen()
        ANALYSIS_ROUTE -> AnalysisScreen()
        TOOLS_ROUTE -> ToolsScreen(snackbarHostState)
        // Unreachable by construction: the NavHost registers exactly the routes
        // in [topLevelDestinations], so a route with no screen is a table that
        // was edited in one place only. Loud rather than a blank page, which is
        // what the placeholder screen here used to be.
        else -> error("No screen for route '${destination.route}'")
    }
}

/**
 * The 14×3dp bar under the active tab.
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
