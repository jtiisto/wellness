package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic

/**
 * The query grid.
 *
 * A card that takes no location runs on tap, as the PWA's did. One that takes a
 * location opens instead, revealing the field — the PWA silently geolocated,
 * which meant a query's answer depended on where the phone thought it was
 * without ever saying so.
 */
@Composable
fun QueryList(
    queries: List<AnalysisQueryDto>,
    expandedQueryId: String?,
    locations: Map<String, String>,
    submitInFlight: Boolean,
    queriesError: String?,
    onTap: (AnalysisQueryDto) -> Unit,
    onRun: (AnalysisQueryDto) -> Unit,
    onLocationChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queries.isEmpty()) {
        EmptyState(
            text = queriesError ?: "No queries available",
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        if (queriesError != null) {
            Text(
                text = queriesError,
                style = WellnessTheme.type.secondary,
                color = WellnessTheme.palette.error,
            )
        }
        queries.forEach { query ->
            QueryCard(
                query = query,
                expanded = expandedQueryId == query.id,
                location = locations[query.id].orEmpty(),
                submitInFlight = submitInFlight,
                onTap = { onTap(query) },
                onRun = { onRun(query) },
                onLocationChange = { onLocationChange(query.id, it) },
            )
        }
    }
}

@Composable
private fun QueryCard(
    query: AnalysisQueryDto,
    expanded: Boolean,
    location: String,
    submitInFlight: Boolean,
    onTap: () -> Unit,
    onRun: () -> Unit,
    onLocationChange: (String) -> Unit,
) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(
                WellnessSpace.hairline,
                if (expanded) accent.border else palette.line,
                WellnessShape.card,
            )
            .clickable(enabled = !submitInFlight, onClick = onTap)
            .padding(WellnessSpace.card),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
        ) {
            Icon(
                imageVector = AnalysisUiLogic.queryIcon(query.icon),
                contentDescription = null,
                tint = if (submitInFlight) palette.textFaint else accent.text,
                modifier = Modifier.size(ICON_SIZE),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = query.label,
                    style = WellnessTheme.type.title,
                    color = if (submitInFlight) palette.textFaint else palette.textPrimary,
                )
                Text(
                    text = query.description,
                    style = WellnessTheme.type.secondary,
                    color = palette.textSecondary,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = WellnessMotion.expandEnter,
            exit = WellnessMotion.expandExit,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
                WellnessDenseField(
                    value = location,
                    onValueChange = onLocationChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitInFlight,
                    label = "Location (optional)",
                    placeholder = "e.g. Austin, TX",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
                Button(
                    onClick = onRun,
                    enabled = !submitInFlight,
                    colors = WellnessDefaults.accentButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Run")
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = WellnessSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = WellnessTheme.type.secondary,
            color = WellnessTheme.palette.textFaint,
        )
    }
}

private val ICON_SIZE = 24.dp
