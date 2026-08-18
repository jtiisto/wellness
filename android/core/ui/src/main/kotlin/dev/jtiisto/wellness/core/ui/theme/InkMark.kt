package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Logbook's "this one is logged" mark.
 *
 * An ink square, filled once the thing it stands for has been written down and a
 * hairline outline until then. It replaces Material's `Checkbox` wherever the
 * log records a fact about itself — a tick in a rounded box is Material's
 * notation, not a training log's — and it is deliberately the *same* mark in
 * every such place: a completed set, a ticked checklist item and a logged
 * tracker entry are the same statement about three different things, so they are
 * drawn identically.
 *
 * Hoisted here from the coach feature when the journal's round needed it.
 * Features never depend on each other, and two copies of a system's core mark is
 * how a design language starts to drift.
 */
@Composable
fun InkMark(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Box(
        modifier = modifier
            .size(MARK_SIZE)
            .then(
                if (checked) {
                    Modifier
                        .clip(MARK_SHAPE)
                        .background(palette.ink)
                } else {
                    Modifier.border(LogbookSpace.hairline, palette.inkFaint, MARK_SHAPE)
                },
            ),
    )
}

/**
 * [InkMark] inside the target a finger needs, with the write path behind it.
 *
 * The painted mark stays small and the [width] around it is what catches the
 * tap — the same visual-vs-interactive split the dense field uses. Callers pass
 * whatever their layout owes the mark ([LogbookSpace.touchTarget] where it is
 * touchable, a narrower column where it is only drawn).
 *
 * On a read-only day the mark is not toggleable, but its state still has to be
 * heard: the disabled `Checkbox` this replaced announced checked and unchecked,
 * and "done, done" versus "done" does not.
 */
@Composable
fun InkMarkToggle(
    checked: Boolean,
    editable: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LogbookSpace.touchTarget,
) {
    Box(
        modifier = modifier.then(
            if (editable) {
                Modifier
                    .size(width)
                    .toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = onCheckedChange,
                    )
                    .semantics { contentDescription = description }
            } else {
                Modifier
                    .width(width)
                    .semantics {
                        contentDescription = description
                        stateDescription = if (checked) "Done" else "Not done"
                    }
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        InkMark(checked)
    }
}

/** Marks round by 1dp — enough to read as drawn rather than as a screen artefact. */
private val MARK_SHAPE = RoundedCornerShape(1.dp)
private val MARK_SIZE = 11.dp
