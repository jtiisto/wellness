package dev.jtiisto.wellness.feature.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.journal.ALL_DAYS
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.formatScheduleSummary
import dev.jtiisto.wellness.core.data.journal.formatTarget
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import org.koin.androidx.compose.koinViewModel

/** Weekday toggles, Monday-first for display. The values stay 0=Sun..6=Sat. */
private val WEEKDAY_PICKER = listOf(
    1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 0 to "S",
)
private val DAY_FULL_NAMES = listOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
)
private val POLARITY_OPTIONS = listOf(
    "" to "Unspecified",
    "positive" to "Positive (build)",
    "negative" to "Negative (avoid)",
    "neutral" to "Neutral (measure)",
)
private val TYPE_OPTIONS = listOf(
    TrackerType.SIMPLE to "Simple (Yes/No only)",
    TrackerType.QUANTIFIABLE to "Quantifiable (Yes/No + Value)",
    TrackerType.EVALUATION to "Evaluation (Yes/No + Percentage)",
    TrackerType.NOTE to "Note (Yes/No + Text)",
)

/** Tracker CRUD: the grouped list, the form sheet, and the delete confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalConfigScreen(
    onBack: () -> Unit,
    viewModel: TrackerFormViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = WellnessTheme.palette

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", style = WellnessTheme.type.headline) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = palette.canvas,
                titleContentColor = palette.textPrimary,
                navigationIconContentColor = palette.textSecondary,
            ),
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to journal")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WellnessSpace.md, vertical = WellnessSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Trackers",
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = viewModel::addTracker,
                colors = WellnessDefaults.accentButtonColors(),
                shape = WellnessShape.card,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Add", style = WellnessTheme.type.label)
            }
        }

        if (state.isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WellnessSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = palette.textFaint,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(WellnessSpace.md))
                Text(
                    text = "No trackers configured yet.",
                    style = WellnessTheme.type.title,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(WellnessSpace.sm))
                Text(
                    text = "Tap \"Add\" to create your first tracker.",
                    style = WellnessTheme.type.secondary,
                    color = palette.textSecondary,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = WellnessSpace.lg)) {
                for (group in state.groups) {
                    item(key = "config-category-${group.name}") {
                        Text(
                            text = group.name,
                            style = WellnessTheme.type.label,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(
                                horizontal = WellnessSpace.md,
                                vertical = WellnessSpace.sm,
                            ),
                        )
                    }
                    items(count = group.trackers.size, key = { group.trackers[it].id }) { index ->
                        TrackerConfigItem(
                            row = group.trackers[index],
                            onEdit = { viewModel.editTracker(group.trackers[index].id) },
                            onDelete = { viewModel.confirmDelete(group.trackers[index].id) },
                        )
                    }
                }
            }
        }
    }

    state.form?.let { form ->
        TrackerFormSheet(
            form = form,
            categories = state.categories,
            isEdit = state.editingId != null,
            onChange = viewModel::updateForm,
            onSubmit = { viewModel.submitForm() },
            onDismiss = viewModel::dismissForm,
        )
    }

    if (state.pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            // A dialog floats, so it takes the card surface — never the canvas,
            // which would leave it indistinguishable from the page behind it.
            containerColor = palette.card,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            shape = WellnessShape.floating,
            title = { Text("Delete tracker?", style = WellnessTheme.type.title) },
            text = { Text("Delete \"${state.pendingDeleteName}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::deleteConfirmed,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelDelete,
                    colors = WellnessDefaults.accentTextButtonColors(),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrackerConfigItem(row: TrackerConfigRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WellnessSpace.md, vertical = WellnessSpace.xs)
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(1.dp, palette.line, WellnessShape.card)
            .padding(start = WellnessSpace.md, top = WellnessSpace.sm, bottom = WellnessSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = WellnessTheme.type.body, color = palette.textPrimary)
            Text(
                text = row.summary,
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit ${row.name}",
                tint = palette.textSecondary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete ${row.name}",
                tint = palette.textSecondary,
            )
        }
    }
}

/**
 * The tracker form.
 *
 * Every dismissal path — scrim, swipe, system back, navigating away — discards
 * silently, matching the PWA. There is no unsaved-changes guard because there
 * never was one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerFormSheet(
    form: TrackerFormState,
    categories: List<String>,
    isEdit: Boolean,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val errors = validateTrackerForm(form)
    val showErrors = form.submitAttempted

    val palette = WellnessTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        contentColor = palette.textPrimary,
        scrimColor = Color.Black.copy(alpha = SCRIM_ALPHA),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        // Eleven fields and a Save button do not fit a short screen, a landscape
        // one, or a portrait one with the keyboard up — and a Save button you
        // cannot reach is the whole form wasted.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = WellnessSpace.lg)
                .padding(bottom = WellnessSpace.xl),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEdit) "Edit Tracker" else "New Tracker",
                style = WellnessTheme.type.headline,
                color = palette.textPrimary,
            )

            WellnessDenseField(
                value = form.name,
                onValueChange = { next -> onChange { it.copy(name = next) } },
                label = "Name",
                placeholder = "e.g. Meditation",
                isError = showErrors && errors.name != null,
                supportingText = errors.name?.takeIf { showErrors },
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryField(form, categories, errors.category.takeIf { showErrors }, onChange)

            TypeField(form.type) { next -> onChange { it.copy(type = next) } }

            if (form.type == TrackerType.QUANTIFIABLE) {
                QuantifiableFieldsSection(form, onChange)
            }

            ScheduleSection(form, onChange)

            PolarityField(form.polarity) { next -> onChange { it.copy(polarity = next) } }

            Button(
                onClick = onSubmit,
                colors = WellnessDefaults.accentButtonColors(),
                shape = WellnessShape.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isEdit) "Save Changes" else "Add Tracker", style = WellnessTheme.type.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    form: TrackerFormState,
    categories: List<String>,
    error: String?,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (categories.isNotEmpty() && !form.useNewCategory) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                WellnessDenseField(
                    value = form.category,
                    onValueChange = {},
                    readOnly = true,
                    label = "Category",
                    placeholder = "Select category…",
                    dropdownExpanded = expanded,
                    isError = error != null,
                    supportingText = error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (category in categories) {
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                onChange { it.copy(category = category) }
                                expanded = false
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = { onChange { it.copy(useNewCategory = true) } },
                colors = WellnessDefaults.accentTextButtonColors(),
            ) {
                Text("+ New Category")
            }
        } else {
            WellnessDenseField(
                value = form.newCategory,
                onValueChange = { next -> onChange { it.copy(newCategory = next) } },
                label = "Category",
                placeholder = "e.g. Supplements",
                isError = error != null,
                supportingText = error,
                modifier = Modifier.fillMaxWidth(),
            )
            if (categories.isNotEmpty()) {
                TextButton(
                    onClick = { onChange { it.copy(useNewCategory = false) } },
                    colors = WellnessDefaults.accentTextButtonColors(),
                ) {
                    Text("Use Existing")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeField(type: TrackerType, onSelect: (TrackerType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        WellnessDenseField(
            value = TYPE_OPTIONS.first { it.first == type }.second,
            onValueChange = {},
            readOnly = true,
            label = "Type",
            dropdownExpanded = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, label) in TYPE_OPTIONS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun QuantifiableFieldsSection(
    form: TrackerFormState,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WellnessDenseField(
            value = form.unit,
            onValueChange = { next -> onChange { it.copy(unit = next) } },
            label = "Unit Label",
            placeholder = "e.g. mg, min",
            modifier = Modifier.weight(1f),
        )
        val defaultValueError = form.defaultValueError
        WellnessDenseField(
            value = form.defaultValue,
            onValueChange = { next -> onChange { it.copy(defaultValue = next) } },
            label = "Default Value",
            placeholder = "e.g. 30",
            numeric = true,
            isError = defaultValueError != null,
            supportingText = defaultValueError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
    }

    LabelledCheckbox(
        checked = form.accumulator,
        label = "Running total (accumulator) — tap + to add throughout the day",
        onCheckedChange = { next -> onChange { it.copy(accumulator = next) } },
    )

    // The error shows while typing (the PWA's inline path); the preview shows
    // how the entered text will actually be read under the current polarity.
    val liveError = form.targetError
    val parsedTarget = form.targetParse.target
    WellnessDenseField(
        value = form.targetInput,
        onValueChange = { next -> onChange { it.copy(targetInput = next) } },
        label = "Target",
        placeholder = "e.g. 150 or 150-170",
        isError = liveError != null,
        supportingText = when {
            liveError != null -> liveError
            parsedTarget != null -> "Target: ${formatTarget(parsedTarget, form.unit)}"
            else -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Target value or range" },
    )
}

@Composable
private fun ScheduleSection(
    form: TrackerFormState,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    val palette = WellnessTheme.palette
    Text("Scheduled days", style = WellnessTheme.type.label, color = palette.textPrimary)

    LabelledCheckbox(
        checked = form.paused,
        label = "Paused",
        onCheckedChange = { next -> onChange { it.copy(paused = next) } },
    )
    Text(
        text = "Hidden from the daily view; adherence pauses. History is kept.",
        style = WellnessTheme.type.secondary,
        color = palette.textSecondary,
    )

    // Paused dims the picker rather than hiding it: the schedule is still worth
    // reading, it is simply not in effect.
    Column(
        modifier = Modifier.alpha(if (form.paused) PAUSED_ALPHA else 1f),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs)) {
            for ((day, label) in WEEKDAY_PICKER) {
                FilterChip(
                    selected = day in form.days,
                    enabled = !form.paused,
                    onClick = { onChange { it.toggleDay(day) } },
                    label = { Text(label, style = WellnessTheme.type.label) },
                    shape = WellnessShape.card,
                    colors = WellnessDefaults.filterChipColors(),
                    border = WellnessDefaults.filterChipBorder(
                        enabled = !form.paused,
                        selected = day in form.days,
                    ),
                    modifier = Modifier.semantics { contentDescription = DAY_FULL_NAMES[day] },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
            TextButton(
                onClick = { onChange { it.copy(days = ALL_DAYS) } },
                enabled = !form.paused,
                colors = WellnessDefaults.accentTextButtonColors(),
            ) {
                Text("Daily")
            }
            TextButton(
                onClick = { onChange { it.copy(days = listOf(1, 2, 3, 4, 5)) } },
                enabled = !form.paused,
                colors = WellnessDefaults.accentTextButtonColors(),
            ) { Text("Weekdays") }
        }
    }
    Text(
        text = if (form.paused) "Paused" else formatScheduleSummary(form.days.ifEmpty { ALL_DAYS }),
        style = WellnessTheme.type.secondary,
        color = if (form.paused) palette.textFaint else palette.textSecondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolarityField(polarity: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        WellnessDenseField(
            value = POLARITY_OPTIONS.first { it.first == polarity }.second,
            onValueChange = {},
            readOnly = true,
            label = "Polarity",
            dropdownExpanded = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .semantics { contentDescription = "Polarity" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, label) in POLARITY_OPTIONS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LabelledCheckbox(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = WellnessDefaults.checkboxColors(),
        )
        Spacer(Modifier.size(WellnessSpace.sm))
        Text(label, style = WellnessTheme.type.secondary, color = WellnessTheme.palette.textPrimary)
    }
}

private const val SCRIM_ALPHA = 0.6f
private const val PAUSED_ALPHA = 0.45f
