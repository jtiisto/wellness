package dev.jtiisto.wellness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.DarkPalette
import dev.jtiisto.wellness.core.ui.theme.LightPalette
import dev.jtiisto.wellness.core.ui.theme.LocalModuleAccent
import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.ModuleAccent
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
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
 * Which design system a destination's composables were written against.
 *
 * Logbook replaces Graphite Signal module by module, so for the length of the
 * migration the two run side by side: the shell is Logbook and every screen
 * carries its own system until its round lands. A screen rendered under the
 * other system's locals does not degrade — it reads tokens that mean something
 * else, which is why the theme is chosen per destination rather than once.
 */
internal enum class ShellSystem { LOGBOOK, GRAPHITE }

/** What the Scaffold paints under a destination, so a tab switch never flashes the other system's page. */
@Immutable
internal data class ShellChrome(val canvas: Color, val content: Color)

internal fun ShellSystem.chrome(isDark: Boolean): ShellChrome = when (this) {
    ShellSystem.LOGBOOK -> ShellChrome(
        canvas = if (isDark) LogbookDark.paper else LogbookLight.paper,
        content = if (isDark) LogbookDark.ink else LogbookLight.ink,
    )
    ShellSystem.GRAPHITE -> ShellChrome(
        canvas = if (isDark) DarkPalette.canvas else LightPalette.canvas,
        content = if (isDark) DarkPalette.textPrimary else LightPalette.textPrimary,
    )
}

@Immutable
internal data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val accent: ModuleAccent,
    val system: ShellSystem,
)

internal val topLevelDestinations = listOf(
    TopLevelDestination(
        route = JOURNAL_ROUTE,
        label = "Journal",
        icon = Icons.Outlined.Checklist,
        accent = ModuleAccent.JOURNAL,
        system = ShellSystem.LOGBOOK,
    ),
    TopLevelDestination(
        route = COACH_ROUTE,
        label = "Coach",
        icon = Icons.Outlined.FitnessCenter,
        accent = ModuleAccent.COACH,
        system = ShellSystem.LOGBOOK,
    ),
    TopLevelDestination(
        route = TRENDS_ROUTE,
        label = "Trends",
        icon = Icons.Outlined.Insights,
        accent = ModuleAccent.TRENDS,
        system = ShellSystem.LOGBOOK,
    ),
    TopLevelDestination(
        route = ANALYSIS_ROUTE,
        label = "Analysis",
        icon = Icons.Outlined.Analytics,
        accent = ModuleAccent.ANALYSIS,
        system = ShellSystem.GRAPHITE,
    ),
    TopLevelDestination(
        route = TOOLS_ROUTE,
        label = "Tools",
        icon = Icons.Outlined.Settings,
        accent = ModuleAccent.TOOLS,
        system = ShellSystem.GRAPHITE,
    ),
)

/** The tab the app opens on; the nav graph and [shellSystemFor] read it from here so they cannot drift. */
internal val startTab = topLevelDestinations.first()

/**
 * The system whose canvas the Scaffold paints for [route].
 *
 * A route that is not a top-level tab resolves to [startTab]'s system rather
 * than the shell's own: the first frame composes before the nav host has
 * committed a back stack entry, and answering with the shell's system there
 * would show one frame of the wrong paper behind the start destination.
 */
internal fun shellSystemFor(route: String?): ShellSystem =
    (topLevelDestinations.firstOrNull { it.route == route } ?: startTab).system

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

        val chrome = shellSystemFor(currentDestination?.route).chrome(palette.isDark)

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
                        DestinationContent(destination, snackbarHostState)
                    }
                }
            }
        }
    }
}

/**
 * A destination's content under the theme its own composables read.
 *
 * The shell above is already Logbook, so a Graphite screen has to re-establish
 * its palette here rather than inherit one; the module accent rides along with
 * it, since accents exist only in Graphite Signal.
 */
@Composable
private fun DestinationContent(
    destination: TopLevelDestination,
    snackbarHostState: SnackbarHostState,
) {
    when (destination.system) {
        ShellSystem.LOGBOOK -> LogbookTheme {
            DestinationScreen(destination, snackbarHostState)
        }
        ShellSystem.GRAPHITE -> WellnessTheme {
            // One accent per tab, provided at its root: nothing below here has
            // to know which module it is drawing.
            CompositionLocalProvider(LocalModuleAccent provides destination.accent) {
                DestinationScreen(destination, snackbarHostState)
            }
        }
    }
}

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
        else -> StubScreen(destination.label)
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
