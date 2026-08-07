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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.ui.tools.ToolsScreen

private const val TOOLS_ROUTE = "tools"

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("journal", "Journal", Icons.Filled.Checklist),
    TopLevelDestination("coach", "Coach", Icons.Filled.FitnessCenter),
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

        Scaffold(
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
                startDestination = "journal",
                modifier = Modifier.padding(innerPadding),
            ) {
                topLevelDestinations.forEach { destination ->
                    composable(destination.route) {
                        when (destination.route) {
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
