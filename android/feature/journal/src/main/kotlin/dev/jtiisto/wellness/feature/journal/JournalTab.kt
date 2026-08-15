package dev.jtiisto.wellness.feature.journal

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private const val DAY_ROUTE = "journal"
private const val CONFIG_ROUTE = "journal/config"

/**
 * The Journal tab: the day view, with the config screen as a real destination
 * behind it.
 *
 * A nested [NavHost] rather than a boolean, so system back leaves the config
 * screen instead of the app, and so each screen keeps its own ViewModel scope —
 * the config form is discarded when you navigate away, which is what the PWA's
 * guard-free dismissal does too.
 */
@Composable
fun JournalTab() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = DAY_ROUTE) {
        composable(DAY_ROUTE) {
            JournalScreen(onOpenConfig = { navController.navigate(CONFIG_ROUTE) })
        }
        composable(CONFIG_ROUTE) {
            JournalConfigScreen(onBack = { navController.popBackStack() })
        }
    }
}
