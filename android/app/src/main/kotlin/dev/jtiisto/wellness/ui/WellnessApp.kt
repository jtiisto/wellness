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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.journal.JournalTab
import dev.jtiisto.wellness.ui.coach.CoachDebugScreen
import dev.jtiisto.wellness.ui.tools.ToolsScreen
import org.koin.compose.koinInject

private const val JOURNAL_ROUTE = "journal"
private const val COACH_ROUTE = "coach"
private const val TOOLS_ROUTE = "tools"

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(JOURNAL_ROUTE, "Journal", Icons.Filled.Checklist),
    TopLevelDestination(COACH_ROUTE, "Coach", Icons.Filled.FitnessCenter),
    TopLevelDestination("trends", "Trends", Icons.Filled.Insights),
    TopLevelDestination("analysis", "Analysis", Icons.Filled.Analytics),
    TopLevelDestination(TOOLS_ROUTE, "Tools", Icons.Filled.Settings),
)

@Composable
fun WellnessApp() {
    WellnessTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val snackbarHostState = remember { SnackbarHostState() }

        // Server-side sync failures, shown once each. The events arrive on a
        // channel rather than as state, so a rotation cannot re-raise a
        // snackbar for a sync that failed minutes ago.
        val syncErrors = koinInject<SyncErrorEvents>()
        LaunchedEffect(syncErrors) {
            syncErrors.messages.collect { message ->
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
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
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = JOURNAL_ROUTE,
                modifier = Modifier.padding(innerPadding),
            ) {
                topLevelDestinations.forEach { destination ->
                    composable(destination.route) {
                        when (destination.route) {
                            JOURNAL_ROUTE -> JournalTab()
                            COACH_ROUTE -> CoachDebugScreen()
                            TOOLS_ROUTE -> ToolsScreen()
                            else -> StubScreen(destination.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StubScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = name, style = MaterialTheme.typography.headlineMedium)
    }
}
