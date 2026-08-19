package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app's data-entry field: a line of text on paper, and nothing around it.
 *
 * A paper logbook has no input boxes on it. A field at rest is bare text,
 * indistinguishable from the read-only row above it, and only the caret and a
 * 1dp ink underline say which one is being written into. Rest and read-only are
 * therefore the same picture on purpose: a cell nobody is typing in must not
 * advertise that it could be typed in, or the table stops being a table.
 *
 * Being bare is the whole definition, which is why there is no `label`, no
 * `supportingText` and no error state here — a caller sets its own mono-caps
 * label above the field and writes its own error line, so the words belong to
 * the form rather than to the box. (Three skins that drew those things retired
 * with Graphite Signal in Round 4; this is what was left when the boxes went.)
 *
 * Everything about *when* a value is written stays with the caller. This
 * renders [value] and reports keystrokes; the commit-on-focus-loss dance the
 * entry screens depend on happens above it.
 *
 * The painted box stays small — [NAKED_HEIGHT], the height of the line itself —
 * while [minimumInteractiveComponentSize] reserves the 48dp around it and
 * forwards the taps that land in the margin. A field that reads as compact still
 * catches a thumb.
 *
 * @param numeric sets the value in mono and aligns it down its column, the way
 *   every other number in the system is set.
 * @param ghostValue the value shown is a default rather than something the user
 *   logged — "not yet yours". It recedes in `inkFaint` and by colour alone:
 *   saying it twice would make a default a different *kind* of value rather than
 *   a fainter one.
 * @param textAlign numerics only. Null keeps [TextAlign.End] — the set table's
 *   convention, so a cell lines up with the read-only rows it sits between —
 *   while a field under a left-aligned form label passes Start so the value sits
 *   beneath the words that name it.
 */
@Composable
fun WellnessDenseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    numeric: Boolean = false,
    ghostValue: Boolean = false,
    multiLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textAlign: TextAlign? = null,
) {
    val palette = LogbookTheme.palette
    val type = LogbookTheme.type

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // Numbers are mono and column-aligned; a note is prose and is not.
    val baseStyle = if (numeric) type.data.copy(textAlign = textAlign ?: TextAlign.End) else type.body
    // Ink whether or not it can be edited: a value logged on a past day is not
    // a fainter value, and "indistinguishable from the read-only text" is the
    // whole contract. Read-only-ness never recedes — only a value that is not
    // the user's own does.
    val textStyle = baseStyle.copy(color = if (ghostValue) palette.inkFaint else palette.ink)
    val hintStyle = baseStyle.copy(color = palette.inkFaint)

    val selectionColors = remember(palette.ink) {
        TextSelectionColors(
            handleColor = palette.ink,
            backgroundColor = palette.ink.copy(alpha = SELECTION_ALPHA),
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.minimumInteractiveComponentSize(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = !multiLine,
            minLines = if (multiLine) MULTILINE_MIN_LINES else 1,
            cursorBrush = SolidColor(palette.ink),
            interactionSource = interactionSource,
        ) { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (multiLine) NAKED_MULTILINE_HEIGHT else NAKED_HEIGHT)
                    .drawBehind {
                        if (!focused) return@drawBehind
                        val stroke = LogbookSpace.hairline.toPx()
                        drawLine(
                            color = palette.ink,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    },
                contentAlignment = if (multiLine) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = hintStyle,
                        maxLines = if (multiLine) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        // Full width so the style's own alignment decides where
                        // the hint sits — a right-aligned column's ghost has to
                        // land under the value it is standing in for.
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                field()
            }
        }
    }
}

/** The field paints no box, so it reserves only what the line of text needs. */
private val NAKED_HEIGHT = 24.dp
private val NAKED_MULTILINE_HEIGHT = 44.dp

private const val MULTILINE_MIN_LINES = 2
private const val SELECTION_ALPHA = 0.4f
