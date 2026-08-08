package dev.jtiisto.wellness.feature.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_TITLE
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.RxKind
import dev.jtiisto.wellness.core.data.coach.RxToken

/**
 * The selected day's workout.
 *
 * A scrolling [Column] rather than a lazy list: a day is a handful of blocks,
 * and the accordions inside need a real scroll container to bring themselves
 * into view when the keyboard appears.
 */
@Composable
fun WorkoutDayView(day: WorkoutDayState, actions: CoachActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (day) {
            WorkoutDayState.Loading -> LoadingDay()
            is WorkoutDayState.PlanUnavailable -> UnreadablePlanDay(day)
            is WorkoutDayState.Rest -> RestDay(day = day, actions = actions)
            is WorkoutDayState.Planned -> PlannedDay(day = day, actions = actions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- the two "nothing to show yet" days --------------------------------------------

/** Storage has not answered yet. Deliberately not the rest-day empty state. */
@Composable
private fun LoadingDay() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A plan is stored for this day but will not decode.
 *
 * No entry, and no ad-hoc session either: offering one here would file an
 * off-plan Zone 2 against a day that already has a workout on it.
 */
@Composable
private fun UnreadablePlanDay(day: WorkoutDayState.PlanUnavailable) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = day.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ---- rest day ------------------------------------------------------------------

@Composable
private fun RestDay(day: WorkoutDayState.Rest, actions: CoachActions) {
    if (day.showEmptyState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.EventAvailable,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No workout scheduled for this day",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    day.extra?.let { ExtraSessionCard(state = it, actions = actions) }
}

/**
 * The ad-hoc Zone 2 card.
 *
 * The draft lives here and nowhere else: until Save it is not in the log store,
 * so nothing syncs and nothing is dirty. Discarding it is a local act, which is
 * why draft-Delete needs no confirmation — and neither does the saved one, whose
 * delete is a recoverable tombstone.
 */
@Composable
private fun ExtraSessionCard(state: ExtraSessionState, actions: CoachActions) {
    var draft by remember { mutableStateOf<ExtraSessionDraft?>(null) }

    when (state) {
        is ExtraSessionState.Saved -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtraSessionHeader()
                CardioFields(
                    durationPlaceholder = "min",
                    durationText = state.durationText,
                    avgHrText = state.avgHrText,
                    maxHrText = state.maxHrText,
                    enabled = state.editable,
                    onCommit = actions.onCommitExtraSessionField,
                )
                if (state.editable) {
                    TextButton(
                        onClick = actions.onDeleteExtraSession,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete session")
                    }
                }
            }
        }

        ExtraSessionState.Idle -> {
            val current = draft
            if (current == null) {
                OutlinedButton(
                    onClick = { draft = ExtraSessionDraft() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Zone 2 session")
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExtraSessionHeader()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumericField(
                                value = current.durationMin,
                                label = "Duration (min)",
                                placeholder = "min",
                                enabled = true,
                                onCommit = { draft = current.copy(durationMin = it) },
                                modifier = Modifier.weight(1f),
                            )
                            NumericField(
                                value = current.avgHr,
                                label = "Avg HR",
                                placeholder = "bpm",
                                enabled = true,
                                onCommit = { draft = current.copy(avgHr = it) },
                                modifier = Modifier.weight(1f),
                            )
                            NumericField(
                                value = current.maxHr,
                                label = "Max HR",
                                placeholder = "bpm",
                                enabled = true,
                                onCommit = { draft = current.copy(maxHr = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { draft = null }) { Text("Delete") }
                            Button(
                                onClick = {
                                    actions.onSaveExtraSession(current)
                                    draft = null
                                },
                                enabled = draftCanSave(current),
                            ) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraSessionHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = EXTRA_SESSION_TITLE, style = MaterialTheme.typography.titleMedium)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "off-plan",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---- planned day ---------------------------------------------------------------

@Composable
private fun PlannedDay(day: WorkoutDayState.Planned, actions: CoachActions) {
    day.banner?.let { ReadOnlyBannerRow(it) }

    WorkoutHeader(day = day, actions = actions)

    for (block in day.blocks) {
        BlockCard(block = block, editable = day.editable, actions = actions)
    }

    SessionFeedbackFields(state = day.feedback, onFeedback = actions.onFeedback)
}

@Composable
private fun ReadOnlyBannerRow(banner: ReadOnlyBanner) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (banner.kind) {
                    ReadOnlyBanner.Kind.PAST -> Icons.Filled.Lock
                    ReadOnlyBanner.Kind.FUTURE -> Icons.Filled.EventAvailable
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(text = banner.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The day's title, its meta, and the collapsible hook controls.
 *
 * The header opens itself once per session while the gate is still shut, so the
 * user can see *why* entry is locked. After that the toggle is theirs: a manual
 * collapse is not undone by the next recomposition.
 */
@Composable
private fun WorkoutHeader(day: WorkoutDayState.Planned, actions: CoachActions) {
    var expanded by remember(day.sessionId) { mutableStateOf(false) }
    var hasAutoExpanded by remember(day.sessionId) { mutableStateOf(false) }
    val hasControls = day.controls != null

    LaunchedEffect(hasControls, day.gateSatisfied, hasAutoExpanded) {
        if (!hasAutoExpanded && hasControls && !day.gateSatisfied) {
            expanded = true
            hasAutoExpanded = true
        }
    }

    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "controls-chevron")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasControls) {
                        Modifier.clickableRow(
                            label = if (expanded) "Hide workout controls" else "Show workout controls",
                            onClick = { expanded = !expanded },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = day.dayName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (hasControls) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            day.location?.let { MetaItem(icon = Icons.Filled.Place, text = it) }
            day.phase?.let { MetaItem(icon = Icons.Filled.BarChart, text = it) }
        }

        AnimatedVisibility(visible = expanded && hasControls) {
            // One full-width row per hook: sharing a single Row pushed the End
            // button's Undo off-screen once labels grew ("(locked)", "Working…").
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                day.controls?.start?.let { HookButton(model = it, actions = actions) }
                day.controls?.end?.let { HookButton(model = it, actions = actions) }
            }
        }
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One hook button, with its Undo beside it when the state allows one.
 *
 * The button stays enabled while FIRED and simply does nothing — the PWA's
 * behaviour, kept because greying it out next to a live Undo reads as "this
 * workout is finished".
 */
@Composable
private fun HookButton(model: HookButtonModel, actions: CoachActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = { if (model.canFire) actions.onFireHook(model.action) },
            enabled = model.enabled,
            colors = when (model.state) {
                HookButtonState.FIRED, HookButtonState.LOCKED -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                )

                HookButtonState.FAILED -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )

                else -> ButtonDefaults.buttonColors()
            },
        ) { Text(model.label) }

        if (model.canUndo) {
            TextButton(onClick = { actions.onUndoHook(model.action) }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo")
            }
        }
    }
}

// ---- blocks --------------------------------------------------------------------

@Composable
private fun BlockCard(block: BlockState, editable: Boolean, actions: CoachActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Stacked like the PWA's .block-header (a column): title, timing badge,
        // rest guidance — each full-width. Sharing a Row squeezed a long
        // guidance sentence into the leftover width beside a long title.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = block.title, style = MaterialTheme.typography.titleMedium)
            if (block.timing.isNotEmpty()) {
                Text(
                    text = block.timing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (block.restGuidance.isNotEmpty()) {
                Text(
                    text = block.restGuidance,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        for (item in block.items) {
            when (item) {
                is BlockItemState.Single -> ExerciseAccordion(
                    row = item.exercise,
                    editable = editable,
                    actions = actions,
                )

                is BlockItemState.Group -> SupersetGroup(
                    group = item,
                    editable = editable,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun SupersetGroup(group: BlockItemState.Group, editable: Boolean, actions: CoachActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = group.displayLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            for (exercise in group.exercises) {
                ExerciseAccordion(row = exercise, editable = editable, actions = actions)
            }
        }
    }
}

/**
 * One exercise, header plus expandable body.
 *
 * Expanding brings the body into view: the PWA does not scroll, which on a phone
 * means the keyboard covers the set grid the moment it opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseAccordion(row: ExerciseRowState, editable: Boolean, actions: CoachActions) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(row.expanded) {
        if (row.expanded) bringIntoView.bringIntoView()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRow(
                    label = if (row.expanded) "Collapse ${row.name}" else "Expand ${row.name}",
                    onClick = { actions.onToggleExercise(row.id) },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .heightIn(min = MIN_TOUCH_TARGET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.completed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            for (pill in row.pills) Pill(text = pill)
            row.exposure?.let { Pill(text = it, emphasized = true) }
            if (row.target.isNotEmpty()) {
                Text(text = row.target, style = MaterialTheme.typography.labelMedium)
            }
            row.progress?.let { progress ->
                Text(
                    text = progress.display,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (progress.complete) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { contentDescription = "Progress: ${progress.display}" },
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(if (row.expanded) 0f else -90f),
            )
        }

        if (row.expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.guidanceNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.prescription.isNotEmpty()) PrescriptionRow(row.prescription)

                when (val entry = row.entry) {
                    is EntryWidgetState.Sets -> SetGrid(
                        exerciseId = row.id,
                        entry = entry,
                        editable = editable,
                        actions = actions,
                    )

                    is EntryWidgetState.Cardio -> CardioFields(
                        durationPlaceholder = entry.durationPlaceholder,
                        durationText = entry.durationText,
                        avgHrText = entry.avgHrText,
                        maxHrText = entry.maxHrText,
                        enabled = editable,
                        onCommit = { field, input -> actions.onCommitCardioField(row.id, field, input) },
                    )

                    is EntryWidgetState.Checklist -> ChecklistItems(
                        items = entry.items,
                        enabled = editable,
                        onToggle = { actions.onToggleChecklistItem(row.id, it) },
                    )

                    null -> Unit
                }

                NoteField(
                    value = row.note,
                    label = "${row.name} note",
                    placeholder = if (editable) "Add notes…" else "No notes",
                    enabled = editable,
                    onChange = { actions.onExerciseNote(row.id, it) },
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** RPE · load · tempo. Load is self-describing, so it gets an icon, not a word. */
@Composable
private fun PrescriptionRow(tokens: List<RxToken>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (token.kind) {
                RxKind.LOAD -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.semantics { contentDescription = "Load ${token.value}" },
                ) {
                    Icon(
                        imageVector = Icons.Filled.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = token.value, style = MaterialTheme.typography.labelMedium)
                }

                else -> Text(
                    text = "${if (token.kind == RxKind.RPE) "RPE" else "Tempo"} ${token.value}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ---- entry widgets ----------------------------------------------------------------

/**
 * The set grid: a header row and exactly `target_sets` rows under it.
 *
 * Ghost values from the last matching session sit in the placeholders, so an
 * untouched row still logs nothing at all.
 */
@Composable
private fun SetGrid(
    exerciseId: String,
    entry: EntryWidgetState.Sets,
    editable: Boolean,
    actions: CoachActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(SET_NUMBER_COLUMN),
            )
            for (column in entry.columns) {
                Text(
                    text = column.unit?.let { "${column.label} ($it)" } ?: column.label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(MIN_TOUCH_TARGET)
                    .semantics { contentDescription = "Done" },
            )
        }

        for (row in entry.rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (row.index + 1).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(SET_NUMBER_COLUMN),
                )
                for (cell in row.cells) {
                    NumericField(
                        value = cell.text,
                        label = "Set ${row.index + 1} ${cell.key}",
                        placeholder = cell.ghost,
                        enabled = editable,
                        onCommit = { actions.onCommitSetCell(exerciseId, row.index, cell.key, it) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                    )
                }
                Box(modifier = Modifier.width(MIN_TOUCH_TARGET), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = row.completed,
                        onCheckedChange = { actions.onSetCompleted(exerciseId, row.index, it) },
                        enabled = editable,
                        modifier = Modifier.semantics {
                            contentDescription = "Set ${row.index + 1} done"
                        },
                    )
                }
            }
        }

        entry.lastPerformanceHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardioFields(
    durationPlaceholder: String,
    durationText: String,
    avgHrText: String,
    maxHrText: String,
    enabled: Boolean,
    onCommit: (String, String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericField(
            value = durationText,
            label = "Duration (min)",
            placeholder = durationPlaceholder.ifEmpty { "min" },
            enabled = enabled,
            onCommit = { onCommit("duration_min", it) },
            modifier = Modifier.weight(1f),
        )
        NumericField(
            value = avgHrText,
            label = "Avg HR",
            placeholder = "bpm",
            enabled = enabled,
            onCommit = { onCommit("avg_hr", it) },
            modifier = Modifier.weight(1f),
        )
        NumericField(
            value = maxHrText,
            label = "Max HR",
            placeholder = "bpm",
            enabled = enabled,
            onCommit = { onCommit("max_hr", it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChecklistItems(items: List<ChecklistItemState>, enabled: Boolean, onToggle: (String) -> Unit) {
    Column {
        for (item in items) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MIN_TOUCH_TARGET),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { onToggle(item.item) },
                    enabled = enabled,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    text = item.item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (item.checked) COMPLETED_ALPHA else 1f)
                        .semantics {
                            contentDescription = if (item.checked) "${item.item}, done" else item.item
                        },
                )
            }
        }
    }
}

@Composable
private fun SessionFeedbackFields(state: SessionFeedbackState, onFeedback: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(text = "Session Feedback", style = MaterialTheme.typography.titleMedium)
        NoteField(
            value = state.painDiscomfort,
            label = "Pain / Discomfort",
            placeholder = if (state.editable) "Note any pain, discomfort, or issues…" else "No notes recorded",
            enabled = state.editable,
            onChange = { onFeedback("pain_discomfort", it) },
            showLabel = true,
        )
        NoteField(
            value = state.generalNotes,
            label = "General Notes",
            placeholder = if (state.editable) "How did the session feel overall?" else "No notes recorded",
            enabled = state.editable,
            onChange = { onFeedback("general_notes", it) },
            showLabel = true,
        )
    }
}

// ---- fields -------------------------------------------------------------------------

/**
 * A string-backed decimal field, committing on focus loss or IME Done.
 *
 * Never per keystroke: typing "125" would otherwise file 1, then 12, then 125 as
 * three separate values. The text is re-seeded whenever the stored value
 * changes, which is also how an unusable entry gets restored — the commit writes
 * nothing and the field snaps back.
 */
@Composable
private fun NumericField(
    value: String,
    label: String,
    placeholder: String?,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    val commit = {
        onCommit(text)
        text = value
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = true,
        placeholder = placeholder?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GHOST_ALPHA),
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .onFocusChanged { focusState ->
                if (focused && !focusState.isFocused) commit()
                focused = focusState.isFocused
            }
            .semantics { contentDescription = label },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

/**
 * A free-text field that types locally and writes as it goes.
 *
 * Binding straight to the stored value would send every keystroke through Room
 * before it could be drawn. Local state owns the field while it has focus; the
 * store's value is adopted whenever it does not, which is how an incoming sync
 * still lands.
 */
@Composable
private fun NoteField(
    value: String,
    label: String,
    placeholder: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    showLabel: Boolean = false,
) {
    var text by remember(label) { mutableStateOf(value) }
    var focused by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        enabled = enabled,
        label = if (showLabel) {
            { Text(label) }
        } else {
            null
        },
        placeholder = { Text(placeholder) },
        minLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
    )
}

/** A whole row made tappable, with the label a screen reader announces. */
private fun Modifier.clickableRow(label: String, onClick: () -> Unit): Modifier = this
    .clickable(onClick = onClick)
    .semantics { contentDescription = label }

private const val COMPLETED_ALPHA = 0.6f
private const val GHOST_ALPHA = 0.5f

/** The set-number gutter, narrow enough to leave the value columns usable. */
private val SET_NUMBER_COLUMN = 28.dp
