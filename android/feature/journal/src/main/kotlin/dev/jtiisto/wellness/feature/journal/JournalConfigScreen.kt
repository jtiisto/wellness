package dev.jtiisto.wellness.feature.journal

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to journal")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trackers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = viewModel::addTracker) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Add")
            }
        }

        if (state.isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No trackers configured yet.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap \"Add\" to create your first tracker.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                for (group in state.groups) {
                    item(key = "config-category-${group.name}") {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
            title = { Text("Delete tracker?") },
            text = { Text("Delete \"${state.pendingDeleteName}\"? This cannot be undone.") },
            confirmButton = { TextButton(onClick = viewModel::deleteConfirmed) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackerConfigItem(row: TrackerConfigRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${row.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.name}")
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Eleven fields and a Save button do not fit a short screen, a landscape
        // one, or a portrait one with the keyboard up — and a Save button you
        // cannot reach is the whole form wasted.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEdit) "Edit Tracker" else "New Tracker",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = form.name,
                onValueChange = { next -> onChange { it.copy(name = next) } },
                label = { Text("Name") },
                placeholder = { Text("e.g. Meditation") },
                singleLine = true,
                isError = showErrors && errors.name != null,
                supportingText = errors.name?.takeIf { showErrors }?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryField(form, categories, errors.category.takeIf { showErrors }, onChange)

            TypeField(form.type) { next -> onChange { it.copy(type = next) } }

            if (form.type == TrackerType.QUANTIFIABLE) {
                QuantifiableFieldsSection(form, onChange)
            }

            ScheduleSection(form, onChange)

            PolarityField(form.polarity) { next -> onChange { it.copy(polarity = next) } }

            Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
                Text(if (isEdit) "Save Changes" else "Add Tracker")
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
                OutlinedTextField(
                    value = form.category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    placeholder = { Text("Select category…") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
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
            TextButton(onClick = { onChange { it.copy(useNewCategory = true) } }) {
                Text("+ New Category")
            }
        } else {
            OutlinedTextField(
                value = form.newCategory,
                onValueChange = { next -> onChange { it.copy(newCategory = next) } },
                label = { Text("Category") },
                placeholder = { Text("e.g. Supplements") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (categories.isNotEmpty()) {
                TextButton(onClick = { onChange { it.copy(useNewCategory = false) } }) {
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
        OutlinedTextField(
            value = TYPE_OPTIONS.first { it.first == type }.second,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
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
        OutlinedTextField(
            value = form.unit,
            onValueChange = { next -> onChange { it.copy(unit = next) } },
            label = { Text("Unit Label") },
            placeholder = { Text("e.g. mg, min") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        val defaultValueError = form.defaultValueError
        OutlinedTextField(
            value = form.defaultValue,
            onValueChange = { next -> onChange { it.copy(defaultValue = next) } },
            label = { Text("Default Value") },
            placeholder = { Text("e.g. 30") },
            singleLine = true,
            isError = defaultValueError != null,
            supportingText = defaultValueError?.let { { Text(it) } },
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
    OutlinedTextField(
        value = form.targetInput,
        onValueChange = { next -> onChange { it.copy(targetInput = next) } },
        label = { Text("Target") },
        placeholder = { Text("e.g. 150 or 150-170") },
        singleLine = true,
        isError = liveError != null,
        supportingText = when {
            liveError != null -> {
                { Text(liveError) }
            }

            form.targetParse.target != null -> {
                { Text("Target: ${formatTarget(form.targetParse.target, form.unit)}") }
            }

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
    Text("Scheduled days", style = MaterialTheme.typography.labelLarge)

    LabelledCheckbox(
        checked = form.paused,
        label = "Paused",
        onCheckedChange = { next -> onChange { it.copy(paused = next) } },
    )
    Text(
        text = "Hidden from the daily view; adherence pauses. History is kept.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((day, label) in WEEKDAY_PICKER) {
            FilterChip(
                selected = day in form.days,
                enabled = !form.paused,
                onClick = { onChange { it.toggleDay(day) } },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = DAY_FULL_NAMES[day] },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange { it.copy(days = ALL_DAYS) } }, enabled = !form.paused) {
            Text("Daily")
        }
        TextButton(
            onClick = { onChange { it.copy(days = listOf(1, 2, 3, 4, 5)) } },
            enabled = !form.paused,
        ) { Text("Weekdays") }
    }
    Text(
        text = if (form.paused) "Paused" else formatScheduleSummary(form.days.ifEmpty { ALL_DAYS }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolarityField(polarity: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = POLARITY_OPTIONS.first { it.first == polarity }.second,
            onValueChange = {},
            readOnly = true,
            label = { Text("Polarity") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
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
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
