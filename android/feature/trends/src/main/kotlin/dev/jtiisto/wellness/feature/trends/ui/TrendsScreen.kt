package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.trends.TrendsShellViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * The Trends tab.
 *
 * Five sub-screens behind one switcher, each with its own ViewModel and its own
 * fetches — only the one on screen is subscribed, so switching away really does
 * stop the work rather than merely hiding it.
 */
@Composable
fun TrendsScreen(modifier: Modifier = Modifier) {
    val shell: TrendsShellViewModel = koinViewModel()
    val state by shell.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = WellnessSpace.card),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Text(
            text = "Trends",
            style = WellnessTheme.type.headline,
            color = WellnessTheme.palette.textPrimary,
            modifier = Modifier.padding(top = WellnessSpace.md, bottom = 2.dp),
        )
        ScreenSwitcher(selected = state.screen, onSelect = shell::setScreen)

        // The active screen takes the rest of the column and scrolls inside it;
        // the switcher above stays put however long the charts run.
        val screenModifier = Modifier.weight(1f)
        when (state.screen) {
            "strength" -> StrengthTrendsScreen(onRange = shell::setRange, modifier = screenModifier)
            "cardio" -> CardioTrendsScreen(onRange = shell::setRange, modifier = screenModifier)
            "journal" -> JournalTrendsScreen(onRange = shell::setRange, modifier = screenModifier)
            "health" -> HealthTrendsScreen(onRange = shell::setRange, modifier = screenModifier)
            else -> OverviewTrendsScreen(
                onRange = shell::setRange,
                onNavigate = shell::setScreen,
                modifier = screenModifier,
            )
        }
    }
}
