package dev.jtiisto.wellness.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun WellnessNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "journal",
        modifier = modifier,
    ) {
        composable("journal") {
            StubScreen("Journal")
        }
        composable("coach") {
            StubScreen("Coach")
        }
        composable("analysis") {
            StubScreen("Analysis")
        }
    }
}

@Composable
private fun StubScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
