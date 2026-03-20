package dev.jtiisto.wellness.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar {
        topLevelRoutes.forEach { route ->
            NavigationBarItem(
                selected = currentRoute == route.route,
                onClick = { onNavigate(route.route) },
                icon = { Icon(route.icon, contentDescription = route.label) },
                label = { Text(route.label) },
            )
        }
    }
}
