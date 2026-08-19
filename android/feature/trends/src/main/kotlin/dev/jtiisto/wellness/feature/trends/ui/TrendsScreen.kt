package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.TrendsShellViewModel
import dev.jtiisto.wellness.feature.trends.chart.screenTitle
import dev.jtiisto.wellness.feature.trends.chart.trendsEyebrow
import org.koin.androidx.compose.koinViewModel

/**
 * The Trends tab, on paper.
 *
 * Five sub-screens behind one strip of tabs, each with its own ViewModel and its
 * own fetches — only the one on screen is subscribed, so switching away really
 * does stop the work rather than merely hiding it.
 *
 * The masthead is the journal's: an eyebrow saying what window everything below
 * is measured over, the sub-screen's own name in display caps, then the tabs.
 * The page is titled by what it is showing rather than by the tab it lives in —
 * the nav bar already says "Trends", and a heading that repeated it would spend
 * the largest type in the system on a word the reader just tapped.
 */
@Composable
fun TrendsScreen(modifier: Modifier = Modifier) {
    val shell: TrendsShellViewModel = koinViewModel()
    val state by shell.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        Spacer(Modifier.height(SCREEN_TOP))
        Text(
            text = trendsEyebrow(state.range).uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = LogbookTheme.palette.inkSoft,
        )
        Text(
            text = screenTitle(state.screen).uppercase(),
            style = LogbookTheme.type.display,
            color = LogbookTheme.palette.ink,
            modifier = Modifier.padding(top = TITLE_TOP, bottom = 2.dp),
        )
        ScreenTabs(selected = state.screen, onSelect = shell::setScreen)

        // The active screen takes the rest of the column and scrolls inside it;
        // the masthead above stays put however long the charts run.
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

/** The page margin: the mockups' 20dp of paper down each side, as journal. */
private val SCREEN_PADDING = 20.dp
private val SCREEN_TOP = 22.dp
private val TITLE_TOP = 10.dp
