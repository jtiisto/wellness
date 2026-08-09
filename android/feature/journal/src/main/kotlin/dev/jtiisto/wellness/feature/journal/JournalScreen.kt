package dev.jtiisto.wellness.feature.journal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.journal.CategoryBadge
import dev.jtiisto.wellness.core.data.journal.DayDot
import dev.jtiisto.wellness.core.data.journal.DotState
import dev.jtiisto.wellness.core.data.journal.ProgressTone
import dev.jtiisto.wellness.core.data.journal.SummaryTone
import dev.jtiisto.wellness.core.data.journal.TargetProgress
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.ui.SyncStatusDot
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.motion.rememberDotReveal
import dev.jtiisto.wellness.core.ui.theme.GHOST_ALPHA
import dev.jtiisto.wellness.core.ui.theme.WeldPosition
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.ui.theme.welded
import org.koin.androidx.compose.koinViewModel

/**
 * The journal day view: the date strip, collapsible category groups, and one
 * row per tracker with the widget its type calls for.
 *
 * Everything here reads off [JournalUiState] and calls back. The composables
 * make no decisions of their own — that is what keeps the day view's real rules
 * in JVM tests rather than in an emulator.
 */
@Composable
fun JournalScreen(
    onOpenConfig: () -> Unit,
    viewModel: JournalViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The date strip is a rolling window over "today", so coming back to the
    // app after it has been away has to recompute it. While the app is in the
    // foreground and online the sync poll keeps the state flowing; the case
    // this covers is returning to a process that sat in the background across
    // midnight. A screen left open in the foreground, offline, across midnight
    // still shows yesterday's strip until something else emits — the PWA has
    // the same gap, and it is not worth a ticker to close.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenShown()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    JournalContent(
        state = state,
        onOpenConfig = onOpenConfig,
        onSelectDate = viewModel::selectDate,
        onToggleCategory = viewModel::toggleCategory,
        onChecked = viewModel::setChecked,
        onCommitNumeric = viewModel::commitNumeric,
        onAccumulate = viewModel::addToAccumulator,
        onSlider = viewModel::setSlider,
        onNote = viewModel::setNote,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalContent(
    state: JournalUiState,
    onOpenConfig: () -> Unit,
    onSelectDate: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onChecked: (String, Boolean) -> Unit,
    onCommitNumeric: (String, Double?, String) -> Unit,
    onAccumulate: (String, Double?, String) -> Unit,
    onSlider: (String, Float) -> Unit,
    onNote: (String, String) -> Unit,
) {
    // The sheet holds an id, not a row: an add must be applied to the value as
    // it stands when the user taps Add, not as it was when the sheet opened.
    var accumulatorId by remember { mutableStateOf<String?>(null) }
    val accumulatorRow = accumulatorId?.let { id ->
        state.groups.asSequence().flatMap { it.trackers }.firstOrNull { it.id == id }
    }
    val palette = WellnessTheme.palette

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Journal", style = WellnessTheme.type.headline) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = palette.canvas,
                titleContentColor = palette.textPrimary,
                actionIconContentColor = palette.textSecondary,
            ),
            // The shell already inset the content below the status bar.
            windowInsets = WindowInsets(0),
            actions = {
                SyncStatusDot(state.syncStatus, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = onOpenConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "Tracker settings")
                }
            },
        )

        DateStrip(cells = state.dateStrip, onSelectDate = onSelectDate)

        when (state.emptyState) {
            JournalEmptyState.NO_TRACKERS -> EmptyState(
                icon = Icons.Filled.ChecklistRtl,
                title = "No trackers configured yet.",
                detail = "Tap the settings icon in the header to add your first tracker.",
            )

            JournalEmptyState.NONE_SCHEDULED -> EmptyState(
                icon = Icons.Filled.EventBusy,
                title = "No trackers scheduled for this day.",
                detail = null,
            )

            null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = WellnessSpace.lg),
            ) {
                for (group in state.groups) {
                    item(key = "category-${group.name}") {
                        CategoryHeader(
                            group = group,
                            onClick = { onToggleCategory(group.name) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (group.expanded) {
                        items(
                            count = group.trackers.size,
                            key = { index -> group.trackers[index].id },
                        ) { index ->
                            TrackerRow(
                                row = group.trackers[index],
                                position = if (index == group.trackers.lastIndex) {
                                    WeldPosition.BOTTOM
                                } else {
                                    WeldPosition.MIDDLE
                                },
                                onChecked = onChecked,
                                onCommitNumeric = onCommitNumeric,
                                onOpenAccumulator = { accumulatorId = group.trackers[index].id },
                                onSlider = onSlider,
                                onNote = onNote,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    accumulatorRow?.let { row ->
        AccumulatorSheet(
            row = row,
            onDismiss = { accumulatorId = null },
            onAdd = { input ->
                onAccumulate(row.id, row.displayedNumber, input)
                accumulatorId = null
            },
        )
    }
}

// ---- date strip ------------------------------------------------------------

@Composable
private fun DateStrip(cells: List<DateCellState>, onSelectDate: (String) -> Unit) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chrome)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(
                    color = palette.line,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = WellnessSpace.sm, vertical = WellnessSpace.sm)
            .semantics { contentDescription = "Select date" },
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
    ) {
        for (cell in cells) {
            DateCell(cell = cell, onClick = { onSelectDate(cell.date) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DateCell(cell: DateCellState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    // The border is always two dips wide and only sometimes visible, so
    // selecting a day cannot shift the strip's layout by a hair.
    val fill by animateColorAsState(
        targetValue = if (cell.isSelected) accent.softFill else Color.Transparent,
        animationSpec = WellnessMotion.fast(),
        label = "date-fill",
    )
    val border by animateColorAsState(
        targetValue = if (cell.isSelected) accent.border else Color.Transparent,
        animationSpec = WellnessMotion.fast(),
        label = "date-border",
    )
    // Locking is a consequence of an edit elsewhere on the screen, so the strip
    // fades into it rather than snapping.
    val contentAlpha by animateFloatAsState(
        targetValue = if (cell.enabled) 1f else LOCKED_ALPHA,
        animationSpec = WellnessMotion.fast(),
        label = "date-lock",
    )
    val label = buildString {
        append("${cell.dayName} ${cell.dayNum}")
        if (cell.isToday) append(", today")
        if (!cell.enabled) append(", locked — commit pending changes first")
    }
    Box(
        modifier = modifier
            .heightIn(min = WellnessSpace.touchTarget)
            .clip(WellnessShape.card)
            .background(fill)
            .border(2.dp, border, WellnessShape.card)
            .clickable(enabled = cell.enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 6.dp)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = cell.dayName,
                style = WellnessTheme.type.label,
                color = palette.textSecondary,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = cell.dayNum.toString(),
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
                modifier = Modifier.clearAndSetSemantics { },
            )
            // Today is marked under the numeral rather than around the cell, so
            // it survives the selected day's border landing on top of it.
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(width = 14.dp, height = 2.dp)
                    .background(
                        if (cell.isToday) accent.fill else Color.Transparent,
                        CircleShape,
                    ),
            )
        }
        if (!cell.enabled) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = palette.textFaint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(10.dp),
            )
        }
    }
}

// ---- categories -------------------------------------------------------------

@Composable
private fun CategoryHeader(group: CategoryGroupState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val rotation by animateFloatAsState(
        targetValue = if (group.expanded) 0f else -90f,
        animationSpec = WellnessMotion.standard(),
        label = "category-chevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = WellnessSpace.md, end = WellnessSpace.md, top = WellnessSpace.sm)
            .welded(
                position = if (group.expanded) WeldPosition.TOP else WeldPosition.SOLO,
                fill = palette.band,
                line = palette.line,
            )
            .clickable(onClick = onClick)
            .heightIn(min = WellnessSpace.touchTarget)
            .padding(horizontal = WellnessSpace.md, vertical = WellnessSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (group.expanded) "Collapse ${group.name}" else "Expand ${group.name}",
            tint = palette.textSecondary,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
        Text(
            text = group.name,
            style = WellnessTheme.type.title,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        group.summary?.let { CategorySummaryPill(it) }
    }
}

@Composable
private fun CategorySummaryPill(badge: CategoryBadge) {
    val palette = WellnessTheme.palette
    Text(
        text = badge.text,
        style = WellnessTheme.type.label,
        color = when (badge.tone) {
            SummaryTone.MET -> palette.success
            SummaryTone.NEUTRAL -> palette.textSecondary
        },
    )
}

// ---- tracker row --------------------------------------------------------------

@Composable
private fun TrackerRow(
    row: TrackerRowState,
    position: WeldPosition,
    onChecked: (String, Boolean) -> Unit,
    onCommitNumeric: (String, Double?, String) -> Unit,
    onOpenAccumulator: () -> Unit,
    onSlider: (String, Float) -> Unit,
    onNote: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = WellnessTheme.palette
    // Uncommitted rows ghost their value controls — but never the dot row,
    // which is history and stays fully legible.
    val valueAlpha = if (row.committed) 1f else GHOST_ALPHA

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WellnessSpace.md)
            .welded(position = position, fill = palette.card, line = palette.line)
            .padding(horizontal = WellnessSpace.sm, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.type != TrackerType.NOTE) {
                Checkbox(
                    checked = row.checked,
                    onCheckedChange = { onChecked(row.id, it) },
                    enabled = row.editable,
                    colors = WellnessDefaults.checkboxColors(),
                    modifier = Modifier.semantics {
                        contentDescription = if (row.checked) "${row.name}, done" else "${row.name}, not done"
                    },
                )
            } else {
                Spacer(Modifier.width(CHECKBOX_COLUMN))
            }
            Text(
                text = row.name,
                style = WellnessTheme.type.body,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.alpha(valueAlpha)) {
                when (row.type) {
                    TrackerType.QUANTIFIABLE -> NumericField(row, onCommitNumeric, onOpenAccumulator)
                    TrackerType.EVALUATION -> EvaluationSlider(row, onSlider)
                    else -> Unit
                }
            }
        }

        row.targetProgress?.let {
            TargetLine(
                progress = it,
                modifier = Modifier
                    .padding(start = CHECKBOX_COLUMN)
                    .alpha(valueAlpha),
            )
        }

        if (row.type == TrackerType.NOTE) {
            NoteField(row, onNote, modifier = Modifier.alpha(valueAlpha))
        }

        row.lastUpdatedCaption?.let {
            Text(
                text = "Last updated $it",
                style = WellnessTheme.type.label,
                color = palette.textFaint,
                modifier = Modifier.padding(start = CHECKBOX_COLUMN),
            )
        }

        DotRow(dots = row.dots, avoidPolarity = row.avoidPolarity)
    }
}

/**
 * A string-backed decimal field. The write happens on focus loss or IME Done,
 * never per keystroke: committing while typing would file "1", "12", "125" as
 * three separate values.
 *
 * The text is re-seeded from [TrackerRowState.valueText] whenever the stored
 * value changes, which is also how an unusable entry gets restored — the commit
 * writes nothing and the next recomposition puts the displayed value back.
 */
@Composable
private fun NumericField(
    row: TrackerRowState,
    onCommit: (String, Double?, String) -> Unit,
    onOpenAccumulator: () -> Unit,
) {
    var text by remember(row.id, row.valueText) { mutableStateOf(row.valueText) }
    var focused by remember(row.id) { mutableStateOf(false) }

    val commit = {
        onCommit(row.id, row.displayedNumber, text)
        text = row.valueText
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = row.editable,
            singleLine = true,
            shape = WellnessShape.input,
            colors = WellnessDefaults.textFieldColors(),
            // Italic while the value is only a default: not yet yours.
            textStyle = WellnessTheme.type.body.copy(
                fontStyle = if (row.committed) FontStyle.Normal else FontStyle.Italic,
            ),
            modifier = Modifier
                .width(96.dp)
                .onFocusChanged { focusState ->
                    if (focused && !focusState.isFocused) commit()
                    focused = focusState.isFocused
                }
                .semantics { contentDescription = "${row.name} value" },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        row.unit?.let {
            Text(
                text = it,
                style = WellnessTheme.type.label,
                color = WellnessTheme.palette.textSecondary,
            )
        }
        if (row.isAccumulator) {
            FilledTonalIconButton(
                onClick = onOpenAccumulator,
                enabled = row.editable,
                colors = WellnessDefaults.accentTonalIconButtonColors(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add to ${row.name} total")
            }
        }
    }
}

/**
 * Five stops, 0..100. The slider writes on every step rather than on release,
 * matching the PWA; the upload debounce absorbs the burst.
 */
@Composable
private fun EvaluationSlider(row: TrackerRowState, onSlider: (String, Float) -> Unit) {
    // The thumb follows the finger locally. Re-keying on the stored value would
    // let a Room emission that lands mid-drag — this row's own earlier write,
    // or a sync — reset the thumb under the user's finger, so the store's value
    // is only adopted once the drag is over.
    var position by remember(row.id) { mutableStateOf(row.sliderValue) }
    var dragging by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.sliderValue, dragging) {
        if (!dragging) position = row.sliderValue
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = position,
            onValueChange = {
                dragging = true
                position = it
                onSlider(row.id, it)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..100f,
            steps = SLIDER_INTERMEDIATE_STOPS,
            enabled = row.editable,
            colors = WellnessDefaults.sliderColors(),
            modifier = Modifier
                .width(150.dp)
                .semantics { contentDescription = "${row.name} rating" },
        )
        Spacer(Modifier.width(WellnessSpace.sm))
        Text(
            text = position.toInt().toString(),
            style = WellnessTheme.type.label,
            color = WellnessTheme.palette.textPrimary,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The note field types locally and writes as it goes.
 *
 * Binding the field straight to the stored value would make every keystroke a
 * round trip through Room before it could be drawn — fast enough most of the
 * time, and visibly dropping or reordering characters when it is not. Local
 * state is the source of truth while the field has focus; the store's value is
 * adopted whenever it does not, which is how an incoming sync still lands.
 */
@Composable
private fun NoteField(row: TrackerRowState, onNote: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(row.id) { mutableStateOf(row.valueText) }
    var focused by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.valueText, focused) {
        if (!focused) text = row.valueText
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onNote(row.id, it)
        },
        enabled = row.editable,
        placeholder = {
            Text(
                text = "Add note…",
                style = WellnessTheme.type.body.copy(fontStyle = FontStyle.Italic),
                color = WellnessTheme.palette.textFaint,
            )
        },
        minLines = 2,
        shape = WellnessShape.input,
        colors = WellnessDefaults.textFieldColors(),
        textStyle = WellnessTheme.type.body,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = WellnessSpace.xs)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "${row.name} note" },
    )
}

@Composable
private fun TargetLine(progress: TargetProgress, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    val tone = when (progress.tone) {
        ProgressTone.MET -> palette.success
        ProgressTone.PARTIAL -> WellnessTheme.accent.text
        // Over target is amber, never red: an overshoot is not a failure.
        ProgressTone.OVER -> palette.warning
        ProgressTone.NEUTRAL -> palette.textSecondary
    }
    Column(modifier = modifier.padding(top = 2.dp)) {
        Text(text = progress.text, style = WellnessTheme.type.secondary, color = tone)
        progress.fillPct?.let { pct ->
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .widthIn(max = TARGET_BAR_MAX_WIDTH)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(palette.line, CircleShape)
                    .clearAndSetSemantics { },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((pct / 100.0).toFloat())
                        .height(3.dp)
                        .background(tone, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun DotRow(dots: List<DayDot>, avoidPolarity: Boolean) {
    val palette = WellnessTheme.palette
    val revealed = rememberDotReveal(dots.size)
    Row(
        modifier = Modifier
            .padding(start = CHECKBOX_COLUMN, top = 6.dp, bottom = 2.dp)
            .semantics { contentDescription = "Last 7 days" },
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
    ) {
        dots.forEachIndexed { index, dot ->
            val alpha by animateFloatAsState(
                targetValue = if (index < revealed) 1f else 0f,
                animationSpec = WellnessMotion.fast(),
                label = "dot-reveal",
            )
            val isToday = index == dots.lastIndex
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .alpha(alpha)
                    // Today wears a ring drawn outside its own 8dp bounds: a
                    // bigger dot would break the row's rhythm.
                    .then(
                        if (isToday) {
                            Modifier.drawBehind {
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val radius = size.minDimension / 2f
                                drawCircle(palette.textFaint, radius + 3.dp.toPx(), centre)
                                drawCircle(palette.card, radius + 1.5.dp.toPx(), centre)
                            }
                        } else {
                            Modifier
                        },
                    )
                    .background(dotColor(dot.state, avoidPolarity), CircleShape),
            )
        }
    }
}

/**
 * A tracker you are trying to *avoid* has no "good" day to celebrate, so its met
 * dots read neutral rather than green — a wall of green for "did not drink" is
 * the wrong kind of encouragement.
 */
@Composable
private fun dotColor(state: DotState, avoidPolarity: Boolean): Color {
    val palette = WellnessTheme.palette
    return when (state) {
        DotState.MET -> if (avoidPolarity) palette.avoided else palette.success
        DotState.PARTIAL -> palette.warning
        DotState.MISSED -> palette.line
        DotState.NOTED -> WellnessTheme.accent.fill
        DotState.QUIET, DotState.OFF -> palette.line.copy(alpha = 0.5f)
    }
}

// ---- accumulator sheet ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccumulatorSheet(row: TrackerRowState, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val palette = WellnessTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        contentColor = palette.textPrimary,
        scrimColor = Color.Black.copy(alpha = SCRIM_ALPHA),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .padding(horizontal = WellnessSpace.lg, vertical = WellnessSpace.sm),
        ) {
            Text(
                text = "Add to ${row.name}",
                style = WellnessTheme.type.headline,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(WellnessSpace.md))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (row.unit != null) "Amount (${row.unit})" else "Amount") },
                placeholder = { Text("e.g. 25") },
                singleLine = true,
                shape = WellnessShape.input,
                colors = WellnessDefaults.textFieldColors(),
                textStyle = WellnessTheme.type.body,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(WellnessSpace.md))
            TextButton(
                onClick = { onAdd(input) },
                colors = WellnessDefaults.accentTextButtonColors(),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Add") }
            Spacer(Modifier.height(WellnessSpace.lg))
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String?,
) {
    val palette = WellnessTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WellnessSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.textFaint,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(WellnessSpace.md))
        Text(text = title, style = WellnessTheme.type.title, color = palette.textPrimary)
        detail?.let {
            Spacer(Modifier.height(WellnessSpace.sm))
            Text(
                text = it,
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
            )
        }
    }
}

/** `step=25` in the PWA: five stops, so three between the ends. */
private const val SLIDER_INTERMEDIATE_STOPS = 3
private const val LOCKED_ALPHA = 0.55f
private const val SCRIM_ALPHA = 0.6f

/** The checkbox gutter. Everything below a row's title line indents past it. */
private val CHECKBOX_COLUMN = 48.dp
private val DOT_SIZE = 8.dp
private val DOT_GAP = 5.dp
private val TARGET_BAR_MAX_WIDTH = 220.dp
