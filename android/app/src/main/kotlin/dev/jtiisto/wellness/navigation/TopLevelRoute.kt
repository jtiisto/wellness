package dev.jtiisto.wellness.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.ui.graphics.vector.ImageVector

data class TopLevelRoute(
    val label: String,
    val route: String,
    val icon: ImageVector,
)

val topLevelRoutes = listOf(
    TopLevelRoute("Journal", "journal", Icons.Default.Book),
    TopLevelRoute("Coach", "coach", Icons.Default.FitnessCenter),
    TopLevelRoute("Analysis", "analysis", Icons.Default.AutoGraph),
)
