package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The glyph a Logbook button may carry.
 *
 * Two marks and nothing else: something settled, or something that failed. Both
 * are drawn in the button's own ink — Logbook has no semantic palette to reach
 * for, so a failed action says `FAILED` beside a cross rather than turning red.
 */
enum class InkGlyph { NONE, CHECK, CROSS }

/**
 * Ink on paper: a filled call to action.
 *
 * Hoisted out of the coach feature for the journal's round — the button
 * language belongs to the design system, not to whichever screen needed it
 * first. Coach layers its hook-state machine on top by mapping its own skin onto
 * [glyph], [note] and [stateDescription]; nothing about the rendering moved.
 *
 * A **settled** button (the check glyph) is disabled only in the sense that
 * pressing it does nothing: it is a record now, and a record keeps full ink
 * (device-found 2026-08-17 — rule-on-paper under ink-faint text read as mush).
 * Only a button that is genuinely unavailable recedes.
 */
@Composable
fun InkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: InkGlyph = InkGlyph.NONE,
    note: String? = null,
    stateDescription: String? = null,
) {
    val palette = LogbookTheme.palette
    val settled = glyph == InkGlyph.CHECK
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        // Explicit: M3's default is a fully rounded pill, the one shape the
        // system forbids.
        shape = LogbookShapes.soft,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.ink,
            contentColor = palette.paper,
            disabledContainerColor = if (settled) palette.ink else palette.rule,
            disabledContentColor = if (settled) palette.paper else palette.inkFaint,
        ),
    ) { InkButtonLabel(label = label, glyph = glyph, note = note, state = stateDescription) }
}

/**
 * The same button, hollow — everything that is not the primary call.
 *
 * [quiet] draws it one step further back: a `ruleStrong` edge around ink-soft
 * text, for a control that has to stay available without competing with the
 * thing it stands beside (Cancel next to a running query, Share next to a log).
 * It is **not** the disabled rendering — the button works, it is just not what
 * the page is about.
 */
@Composable
fun InkOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
    glyph: InkGlyph = InkGlyph.NONE,
    note: String? = null,
    stateDescription: String? = null,
) {
    val palette = LogbookTheme.palette
    // Same settled rule as InkButton: a fired End that cannot be pressed is a
    // record in full ink, not a faded control.
    val settled = glyph == InkGlyph.CHECK
    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = LogbookShapes.soft,
        border = BorderStroke(
            LogbookSpace.hairline,
            if (quiet) palette.ruleStrong else palette.ink,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (quiet) palette.inkSoft else palette.ink,
            disabledContentColor = if (settled) palette.ink else palette.inkFaint,
        ),
    ) { InkButtonLabel(label = label, glyph = glyph, note = note, state = stateDescription) }
}

@Composable
private fun InkButtonLabel(label: String, glyph: InkGlyph, note: String?, state: String?) {
    Row(
        // The fill and the glyph are visual; the state has to be audible too,
        // and it merges into the button's own node.
        modifier = Modifier.semantics {
            state?.let { stateDescription = it }
        },
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 1.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (glyph) {
            InkGlyph.CHECK -> Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(GLYPH_SIZE),
            )

            InkGlyph.CROSS -> Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                modifier = Modifier.size(GLYPH_SIZE),
            )

            InkGlyph.NONE -> Unit
        }
        Text(text = label.uppercase(), style = LogbookTheme.type.eyebrow)
        note?.let {
            Text(text = it.uppercase(), style = LogbookTheme.type.eyebrow)
        }
    }
}

private val GLYPH_SIZE = 14.dp
