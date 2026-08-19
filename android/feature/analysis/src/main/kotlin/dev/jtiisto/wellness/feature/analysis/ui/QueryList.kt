package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.DenseFieldSkin
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.bottomRule

/**
 * The query list.
 *
 * Rows rather than cards, and text rather than glyphs: a card is a second
 * surface, and the icons said nothing the label did not (the coach meta-row
 * precedent — a decorative glyph in front of a name is a picture of the name).
 * What separates one query from the next is a hairline and the air around it.
 *
 * A query that takes no location runs on tap, as the PWA's did. One that takes a
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
        // The error is the whole story when there is nothing to list — a module
        // switched off on the server has no queries *and* a reason.
        if (queriesError != null) {
            InkNotice(text = queriesError, modifier = modifier.padding(vertical = LogbookSpace.grid * 2))
        } else {
            EmptyLine(text = "No queries available", modifier = modifier)
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (queriesError != null) {
            InkNotice(text = queriesError, modifier = Modifier.padding(bottom = LogbookSpace.grid * 2))
        }
        queries.forEach { query ->
            QueryRow(
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
private fun QueryRow(
    query: AnalysisQueryDto,
    expanded: Boolean,
    location: String,
    submitInFlight: Boolean,
    onTap: () -> Unit,
    onRun: () -> Unit,
    onLocationChange: (String) -> Unit,
) {
    val palette = LogbookTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomRule(palette.rule)
            .clickable(enabled = !submitInFlight, onClick = onTap)
            .padding(vertical = ROW_PADDING),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        Text(
            text = query.label,
            style = LogbookTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = if (submitInFlight) palette.inkFaint else palette.ink,
        )
        Text(
            text = query.description,
            style = LogbookTheme.type.body,
            color = palette.inkSoft,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = WellnessMotion.expandEnter,
            exit = WellnessMotion.expandExit,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LogbookSpace.grid),
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
                verticalAlignment = Alignment.Bottom,
            ) {
                LocationField(
                    location = location,
                    enabled = !submitInFlight,
                    onLocationChange = onLocationChange,
                    onDone = { keyboard?.hide() },
                    modifier = Modifier.weight(1f),
                )
                InkOutlineButton(label = "Run", onClick = onRun, enabled = !submitInFlight)
            }
        }
    }
}

/**
 * The optional location, as a form field rather than a permission.
 *
 * Naked: no box, its own mono-caps label above it, and a `ruleStrong` hairline
 * under it so an empty field is still visibly a place to write. The value sets
 * in the **body** face, not in mono — a town is words, and the system's third
 * principle spends mono on numerals. (The mockup drew it mono because it drew it
 * inside a meta row of values.)
 */
@Composable
private fun LocationField(
    location: String,
    enabled: Boolean,
    onLocationChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LogbookTheme.palette
    Column(modifier = modifier) {
        Text(
            text = LOCATION_LABEL.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
        WellnessDenseField(
            value = location,
            onValueChange = onLocationChange,
            modifier = Modifier
                .fillMaxWidth()
                .bottomRule(palette.ruleStrong)
                // The skin draws no label, so the field's own node carries the
                // name — the drawn one above it is not on the node a reader
                // lands on when they focus the input.
                .semantics { contentDescription = LOCATION_LABEL },
            enabled = enabled,
            skin = DenseFieldSkin.NAKED,
            placeholder = LOCATION_PLACEHOLDER,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
    }
}

/** The system's voice for absence: italic, faint, and saying only what is missing. */
@Composable
internal fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
        color = LogbookTheme.palette.inkFaint,
        modifier = modifier.padding(vertical = LogbookSpace.grid * 2),
    )
}

private const val LOCATION_LABEL = "Location (optional)"
private const val LOCATION_PLACEHOLDER = "e.g. Austin, TX"

/** The log's row rhythm, a shade tighter than a coach row: two lines, not a table. */
private val ROW_PADDING = 13.dp
