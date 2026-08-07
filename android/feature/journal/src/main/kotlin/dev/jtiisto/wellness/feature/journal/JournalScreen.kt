package dev.jtiisto.wellness.feature.journal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Journal") },
            actions = {
                SyncStatusDot(state.syncStatus, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = onOpenConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "Tracker settings")
                }
            },
        )

        DateStrip(cells = state.dateStrip, onSelectDate = onSelectDate)
        HorizontalDivider()

        when (state.emptyState) {
            JournalEmptyState.NO_TRACKERS -> EmptyState(
                title = "No trackers configured yet.",
                detail = "Tap the settings icon in the header to add your first tracker.",
            )

            JournalEmptyState.NONE_SCHEDULED -> EmptyState(
                title = "No trackers scheduled for this day.",
                detail = null,
            )

            null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                for (group in state.groups) {
                    item(key = "category-${group.name}") {
                        CategoryHeader(group = group, onClick = { onToggleCategory(group.name) })
                    }
                    if (group.expanded) {
                        items(
                            count = group.trackers.size,
                            key = { index -> group.trackers[index].id },
                        ) { index ->
                            TrackerRow(
                                row = group.trackers[index],
                                onChecked = onChecked,
                                onCommitNumeric = onCommitNumeric,
                                onOpenAccumulator = { accumulatorId = group.trackers[index].id },
                                onSlider = onSlider,
                                onNote = onNote,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = "Select date" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (cell in cells) {
            DateCell(cell = cell, onClick = { onSelectDate(cell.date) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DateCell(cell: DateCellState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = when {
        cell.isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val label = buildString {
        append("${cell.dayName} ${cell.dayNum}")
        if (cell.isToday) append(", today")
        if (!cell.enabled) append(", locked — commit pending changes first")
    }
    Surface(
        onClick = onClick,
        enabled = cell.enabled,
        modifier = modifier.semantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = if (cell.isToday) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .alpha(if (cell.enabled) 1f else LOCKED_ALPHA),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = cell.dayName,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = cell.dayNum.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clearAndSetSemantics { },
            )
            if (!cell.enabled) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

// ---- categories -------------------------------------------------------------

@Composable
private fun CategoryHeader(group: CategoryGroupState, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (group.expanded) 0f else -90f,
        label = "category-chevron",
    )
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (group.expanded) "Collapse ${group.name}" else "Expand ${group.name}",
                modifier = Modifier.rotate(rotation),
            )
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            group.summary?.let { CategorySummaryPill(it) }
        }
    }
}

@Composable
private fun CategorySummaryPill(badge: CategoryBadge) {
    val color = when (badge.tone) {
        SummaryTone.MET -> MaterialTheme.colorScheme.primary
        SummaryTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = badge.text, style = MaterialTheme.typography.labelMedium, color = color)
}

// ---- tracker row --------------------------------------------------------------

@Composable
private fun TrackerRow(
    row: TrackerRowState,
    onChecked: (String, Boolean) -> Unit,
    onCommitNumeric: (String, Double?, String) -> Unit,
    onOpenAccumulator: () -> Unit,
    onSlider: (String, Float) -> Unit,
    onNote: (String, String) -> Unit,
) {
    // Uncommitted rows ghost their value controls — but never the dot row,
    // which is history and stays fully legible.
    val valueAlpha = if (row.committed) 1f else UNCOMMITTED_ALPHA

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.type != TrackerType.NOTE) {
                Checkbox(
                    checked = row.checked,
                    onCheckedChange = { onChecked(row.id, it) },
                    enabled = row.editable,
                    modifier = Modifier.semantics {
                        contentDescription = if (row.checked) "${row.name}, done" else "${row.name}, not done"
                    },
                )
            }
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
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

        row.targetProgress?.let { TargetLine(it, modifier = Modifier.alpha(valueAlpha)) }

        if (row.type == TrackerType.NOTE) {
            NoteField(row, onNote, modifier = Modifier.alpha(valueAlpha))
        }

        row.lastUpdatedCaption?.let {
            Text(
                text = "Last updated $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        row.unit?.let { Text(text = it, style = MaterialTheme.typography.labelMedium) }
        if (row.isAccumulator) {
            FilledTonalIconButton(
                onClick = onOpenAccumulator,
                enabled = row.editable,
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
            modifier = Modifier
                .width(150.dp)
                .semantics { contentDescription = "${row.name} rating" },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = position.toInt().toString(),
            style = MaterialTheme.typography.labelLarge,
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
        placeholder = { Text("Add note…") },
        minLines = 2,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "${row.name} note" },
    )
}

@Composable
private fun TargetLine(progress: TargetProgress, modifier: Modifier = Modifier) {
    val color = when (progress.tone) {
        ProgressTone.MET -> MaterialTheme.colorScheme.primary
        ProgressTone.PARTIAL -> MaterialTheme.colorScheme.tertiary
        ProgressTone.OVER -> MaterialTheme.colorScheme.error
        ProgressTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = modifier.padding(top = 2.dp)) {
        Text(text = progress.text, style = MaterialTheme.typography.labelMedium, color = color)
        progress.fillPct?.let { pct ->
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
                    .clearAndSetSemantics { },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((pct / 100.0).toFloat())
                        .height(3.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun DotRow(dots: List<DayDot>, avoidPolarity: Boolean) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .semantics { contentDescription = "Last 7 days" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        dots.forEachIndexed { index, dot ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor(dot.state, avoidPolarity), CircleShape)
                    .then(
                        if (index == dots.lastIndex) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
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
private fun dotColor(state: DotState, avoidPolarity: Boolean): Color = when (state) {
    DotState.MET -> if (avoidPolarity) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.primary
    }

    DotState.PARTIAL -> MaterialTheme.colorScheme.tertiary
    DotState.MISSED -> MaterialTheme.colorScheme.surfaceVariant
    DotState.NOTED -> MaterialTheme.colorScheme.secondary
    DotState.QUIET, DotState.OFF -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
}

// ---- accumulator sheet ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccumulatorSheet(row: TrackerRowState, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(text = "Add to ${row.name}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (row.unit != null) "Amount (${row.unit})" else "Amount") },
                placeholder = { Text("e.g. 25") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { onAdd(input) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Add") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `step=25` in the PWA: five stops, so three between the ends. */
private const val SLIDER_INTERMEDIATE_STOPS = 3
private const val UNCOMMITTED_ALPHA = 0.55f
private const val LOCKED_ALPHA = 0.5f
