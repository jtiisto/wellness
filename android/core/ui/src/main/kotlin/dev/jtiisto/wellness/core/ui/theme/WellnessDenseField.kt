package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion

/**
 * How much of a box a field needs.
 *
 * A field standing on its own has to declare itself against whatever surface it
 * was dropped on, so it is outlined. A cell inside a grid does not: the grid
 * already says "these are entry boxes", and nine outlines saying it again is
 * most of why the old grid read heavy. The PWA draws those cells borderless,
 * and so do we.
 */
enum class DenseFieldSkin {
    /** `input` behind a hairline — config forms, the journal value field. */
    OUTLINED,

    /** A well, no stroke until focus — grid cells and in-card notes. */
    FILLED,

    /**
     * No box at all — Logbook's coach tables and notes.
     *
     * A paper logbook has no input boxes on it: a cell at rest is bare mono text,
     * indistinguishable from the read-only row above it, and only the caret and a
     * 1dp ink underline say which one is being written into. It is the one skin
     * that reads **Logbook** tokens rather than Graphite's, so it belongs
     * exclusively to a `LogbookTheme` subtree; the other two are unchanged and
     * stay Graphite's.
     *
     * Being bare is the whole definition, so `label`, `supportingText`,
     * `isError`, `italic`, `hint` and `dropdownExpanded` have nothing to draw and
     * are ignored — a Logbook callsite sets its own mono-caps label above the
     * field, and a ghost is a colour rather than a dialect.
     */
    NAKED,
}

/**
 * The quiet fill: one step *down* from the surface the field sits on.
 *
 * `input` cannot do this job borderless, which is what the PWA reference asks
 * for — in the light theme `input` and `card` are both `#FFFFFF`, so the cell
 * would simply vanish. What a well actually is, is darker than its
 * surroundings, and which token is darker than `card` depends on the theme:
 * `canvas` punches back through to the page in the dark theme, and `band` is
 * already the darker neutral in the light one. Both read as inset, which is the
 * point; neither needs a stroke to be seen.
 */
private val WellnessPalette.wellFill: Color
    get() = if (isDark) canvas else band

/**
 * What a placeholder is saying.
 *
 * Three different things get written into an empty field, they do not mean the
 * same thing, so they do not look the same either.
 */
enum class DenseFieldHint {
    /** A worked example — "e.g. 150". Upright: it is instruction, not content. */
    PLAIN,

    /** An invitation to write — "Add notes…". */
    PROMPT,

    /** Last session's number: italic at 55%, the "not yet yours" convention. */
    GHOST,
}

/**
 * The app's data-entry field.
 *
 * Stock `OutlinedTextField` reserves a 56dp box around 16sp text, which is
 * right for a login form and wrong for a set grid: three sets of it fill half a
 * phone. This is the same machinery — a [BasicTextField] in a decoration box,
 * which is all the Material one is — drawn at the density the data deserves: a
 * 40dp box, 15sp inside it, 10dp of side padding.
 *
 * Visual size and interactive size are deliberately different. The painted box
 * is 40dp; [minimumInteractiveComponentSize] reserves 48dp around it and
 * forwards the taps that land in the margin, so a field that reads as compact
 * still catches a thumb.
 *
 * Everything about *when* a value is written stays with the caller. This is a
 * box with a border: it renders [value] and reports keystrokes, and the
 * commit-on-focus-loss dance the entry screens depend on happens above it,
 * exactly as it did around the Material field.
 *
 * @param skin whether the box outlines itself or sits as a quiet fill; see
 *   [DenseFieldSkin].
 * @param label drawn above the box in the taxonomy dialect, not floating inside
 *   it — a floating label needs the 56dp this component exists to avoid.
 * @param numeric centres the text and switches to the tabular ramp, for cells
 *   whose digits have to line up down a column.
 * @param italic renders the *value* italic — a journal default that is not yet
 *   the user's own. Distinct from [DenseFieldHint.GHOST], which is placeholder text.
 * @param dropdownExpanded non-null draws a chevron and rotates it; the menu
 *   itself belongs to the caller's `ExposedDropdownMenuBox`.
 */
@Composable
fun WellnessDenseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    skin: DenseFieldSkin = DenseFieldSkin.OUTLINED,
    label: String? = null,
    placeholder: String? = null,
    hint: DenseFieldHint = DenseFieldHint.PLAIN,
    supportingText: String? = null,
    isError: Boolean = false,
    numeric: Boolean = false,
    italic: Boolean = false,
    multiLine: Boolean = false,
    dropdownExpanded: Boolean? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // NAKED numerics only. Null keeps the skin's own End — the set table's
    // convention — while a field under a left-aligned form label passes Start
    // so the value sits beneath the words that name it.
    textAlign: TextAlign? = null,
) {
    // Routed before everything else: the skin's contract is that nothing is
    // drawn around the text, so the label/supporting-text column below would
    // contradict it (and would set Graphite type on a Logbook page besides).
    if (skin == DenseFieldSkin.NAKED) {
        NakedInput(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            placeholder = placeholder,
            numeric = numeric,
            multiLine = multiLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textAlign = textAlign,
        )
        return
    }

    // The bare field takes the caller's modifier itself, so a `contentDescription`
    // passed in lands on the editable node rather than on a wrapper beside it —
    // a screen reader would otherwise announce the grid cell twice.
    if (label == null && supportingText == null) {
        DenseInput(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            skin = skin,
            placeholder = placeholder,
            hint = hint,
            isError = isError,
            errorDescription = null,
            numeric = numeric,
            italic = italic,
            multiLine = multiLine,
            dropdownExpanded = dropdownExpanded,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
        return
    }

    val palette = WellnessTheme.palette
    val type = WellnessTheme.type
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = type.micro,
                color = if (isError) palette.error else palette.textSecondary,
                modifier = Modifier.padding(bottom = LABEL_GAP),
            )
        }
        DenseInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            skin = skin,
            placeholder = placeholder,
            hint = hint,
            isError = isError,
            errorDescription = supportingText,
            numeric = numeric,
            italic = italic,
            multiLine = multiLine,
            dropdownExpanded = dropdownExpanded,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = type.label,
                color = if (isError) palette.error else palette.textSecondary,
                modifier = Modifier.padding(top = SUPPORT_GAP, start = LABEL_GAP),
            )
        }
    }
}

/** The box itself: everything [WellnessDenseField] draws that a finger can land on. */
@Composable
private fun DenseInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    skin: DenseFieldSkin,
    placeholder: String?,
    hint: DenseFieldHint,
    isError: Boolean,
    errorDescription: String?,
    numeric: Boolean,
    italic: Boolean,
    multiLine: Boolean,
    dropdownExpanded: Boolean?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    val type = WellnessTheme.type

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val filled = skin == DenseFieldSkin.FILLED
    val restBorder = if (filled) Color.Transparent else palette.line

    // Error outranks focus, as it does in Material: a field you are fixing
    // should not stop saying so while you are in it.
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> restBorder
            isError -> palette.error
            focused -> accent.text
            else -> restBorder
        },
        animationSpec = WellnessMotion.fast(),
        label = "dense-field-border",
    )
    // `border` draws inward without taking layout space, so thickening it on
    // focus moves nothing on screen. A filled box holds the ring's width the
    // whole time and lets the colour fade in from transparent; an outlined one
    // thickens from its resting hairline.
    val borderWidth = when {
        filled -> FOCUSED_BORDER
        enabled && (focused || isError) -> FOCUSED_BORDER
        else -> WellnessSpace.hairline
    }

    val baseStyle = if (numeric) {
        type.label.copy(fontSize = TEXT_SIZE, lineHeight = LINE_HEIGHT, textAlign = TextAlign.Center)
    } else {
        type.body
    }
    val textStyle = baseStyle.copy(
        color = if (enabled) palette.textPrimary else palette.textFaint,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )
    val hintStyle = baseStyle.copy(
        fontStyle = if (hint == DenseFieldHint.PLAIN) FontStyle.Normal else FontStyle.Italic,
        color = when (hint) {
            DenseFieldHint.GHOST -> palette.textSecondary.copy(alpha = GHOST_ALPHA)
            else -> palette.textFaint
        },
    )

    val selectionColors = remember(accent.text) {
        TextSelectionColors(
            handleColor = accent.text,
            backgroundColor = accent.text.copy(alpha = SELECTION_ALPHA),
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .minimumInteractiveComponentSize()
                .semantics { if (isError) error(errorDescription ?: "Invalid value") },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = !multiLine,
            minLines = if (multiLine) MULTILINE_MIN_LINES else 1,
            cursorBrush = SolidColor(accent.text),
            interactionSource = interactionSource,
        ) { field ->
            Row(
                modifier = Modifier
                    .heightIn(min = if (multiLine) MULTILINE_HEIGHT else HEIGHT)
                    .clip(WellnessShape.input)
                    .background(if (filled) palette.wellFill else palette.input)
                    .border(borderWidth, borderColor, WellnessShape.input)
                    .padding(
                        horizontal = SIDE_PADDING,
                        vertical = if (multiLine) MULTILINE_TOP_PADDING else 0.dp,
                    ),
                verticalAlignment = if (multiLine) Alignment.Top else Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = when {
                        multiLine -> Alignment.TopStart
                        numeric -> Alignment.Center
                        else -> Alignment.CenterStart
                    },
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = hintStyle,
                            maxLines = if (multiLine) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
                if (dropdownExpanded != null) {
                    val rotation by animateFloatAsState(
                        targetValue = if (dropdownExpanded) CHEVRON_FLIP else 0f,
                        animationSpec = WellnessMotion.fast(),
                        label = "dense-field-chevron",
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = if (enabled) palette.textSecondary else palette.textFaint,
                        modifier = Modifier
                            .size(CHEVRON_SIZE)
                            .rotate(rotation),
                    )
                }
            }
        }
    }
}

/**
 * The bare field: Logbook's [DenseFieldSkin.NAKED].
 *
 * Deliberately not a branch inside [DenseInput]. Everything about it is the
 * other system's — Plex Mono against ink, an underline where the other two draw
 * a border, no fill to animate — and threading a second palette through the
 * Graphite box would leave both harder to read than the two of them apart.
 *
 * Rest and read-only are the same picture on purpose: a cell nobody is typing in
 * must not advertise that it could be typed in, or the table stops being a
 * table. The caret and the underline arrive together on focus, and they are the
 * only difference.
 *
 * The painted box stays small — [NAKED_HEIGHT] — while
 * [minimumInteractiveComponentSize] reserves the 48dp around it, the same
 * visual-vs-interactive split the dense field has always used.
 */
@Composable
private fun NakedInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    placeholder: String?,
    numeric: Boolean,
    multiLine: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    textAlign: TextAlign?,
) {
    val palette = LogbookTheme.palette
    val type = LogbookTheme.type

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // Numbers are mono and column-aligned; a note is prose and is not. End is
    // the set table's convention — the cells match the read-only rows they sit
    // between — and [textAlign] is how a form-labelled field asks for Start
    // instead, so its value sits under the words that name it.
    val baseStyle = if (numeric) type.data.copy(textAlign = textAlign ?: TextAlign.End) else type.body
    // Ink whether or not it can be edited: a value logged on a past day is not
    // a fainter value, and "indistinguishable from the read-only text" is the
    // whole contract of the skin. Absence is what recedes, not read-only-ness.
    val textStyle = baseStyle.copy(color = palette.ink)
    // A ghost recedes by colour alone. Italic is Graphite's convention and
    // saying it twice here would make last session's number a different *kind*
    // of thing rather than a fainter one.
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
                        val stroke = WellnessSpace.hairline.toPx()
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

/**
 * A set-grid column head.
 *
 * Micro's size, quieted: a shade lighter and the tracking pulled most of the
 * way in. Still the uppercase taxonomy dialect, because that is what a column
 * head is — but the hairline rule beneath it now does the separating, so the
 * type no longer has to shout to hold the columns apart.
 */
@Composable
fun columnHeaderStyle() = WellnessTheme.type.micro.copy(
    fontWeight = FontWeight(550),
    letterSpacing = 0.2.sp,
)

/** The painted box. The 48dp around it is reserved, not drawn. */
private val HEIGHT = 40.dp

/** The naked skin paints no box, so it reserves only what the line of text needs. */
private val NAKED_HEIGHT = 24.dp
private val NAKED_MULTILINE_HEIGHT = 44.dp

/** Two lines of note plus its padding — the notes fields, top-aligned. */
private val MULTILINE_HEIGHT = 72.dp
private const val MULTILINE_MIN_LINES = 2
private val MULTILINE_TOP_PADDING = 8.dp

private val SIDE_PADDING = 10.dp
private val LABEL_GAP = 3.dp
private val SUPPORT_GAP = 2.dp
private val FOCUSED_BORDER = 2.dp
private val CHEVRON_SIZE = 18.dp
private const val CHEVRON_FLIP = 180f
private val TEXT_SIZE = 15.sp
private val LINE_HEIGHT = 20.sp
private const val SELECTION_ALPHA = 0.4f
