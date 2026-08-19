package dev.jtiisto.wellness.feature.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.journal.ALL_DAYS
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.formatScheduleSummary
import dev.jtiisto.wellness.core.data.journal.formatTarget
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkMark
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSheetHandle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
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

/**
 * Tracker CRUD: the grouped list, the form sheet, and the delete confirmation.
 *
 * The same paper as the day view, and the same rule about decisions — every
 * seeding, validation and assembly rule lives in `TrackerFormLogic` and the
 * ViewModel, so this file only chooses type and ink.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalConfigScreen(
    onBack: () -> Unit,
    viewModel: TrackerFormViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LogbookTheme.palette

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings".uppercase(), style = LogbookTheme.type.section) },
            colors = TopAppBarDefaults.topAppBarColors(
                // Paper, like everything else — the bar is not a second surface.
                containerColor = palette.paper,
                titleContentColor = palette.ink,
                navigationIconContentColor = palette.ink,
            ),
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to journal",
                    )
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SCREEN_PADDING, vertical = LogbookSpace.grid * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Trackers".uppercase(),
                style = LogbookTheme.type.display,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            InkButton(label = "Add", onClick = viewModel::addTracker)
        }

        if (state.isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = SCREEN_PADDING, vertical = EMPTY_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyText("No trackers configured yet.")
                Spacer(Modifier.height(LogbookSpace.grid * 2))
                EmptyText("Tap \"Add\" to create your first tracker.")
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = SCREEN_BOTTOM)) {
                for (group in state.groups) {
                    item(key = "config-category-${group.name}") {
                        SectionRule(
                            label = group.name,
                            modifier = Modifier.padding(
                                start = SCREEN_PADDING,
                                end = SCREEN_PADDING,
                                top = SECTION_GAP,
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
        // Left an AlertDialog on purpose: it inherits the Logbook M3 mapping —
        // paper surface, 2dp corners, ink on it — so there is no per-callsite
        // colour work to do, and the confirm button loses the error red because
        // the system has no such colour to spend.
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete tracker?", style = LogbookTheme.type.section) },
            text = {
                Text(
                    text = "Delete \"${state.pendingDeleteName}\"? This cannot be undone.",
                    style = LogbookTheme.type.body,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::deleteConfirmed,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.ink),
                ) { Text("DELETE", style = LogbookTheme.type.eyebrow) }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSoft),
                ) { Text("CANCEL", style = LogbookTheme.type.eyebrow) }
            },
        )
    }
}

@Composable
private fun TrackerConfigItem(row: TrackerConfigRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SCREEN_PADDING)
            .hairlineBelow(palette.rule)
            .padding(vertical = LogbookSpace.grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = LogbookTheme.type.name, color = palette.ink)
            // The summary is schedule and target — numbers and weekday names,
            // which is the mono voice by definition.
            Text(row.summary, style = LogbookTheme.type.meta, color = palette.inkSoft)
        }
        IconButton(
            onClick = onEdit,
            colors = IconButtonDefaults.iconButtonColors(contentColor = palette.ink),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit ${row.name}",
                modifier = Modifier.size(GLYPH_SIZE),
            )
        }
        IconButton(
            onClick = onDelete,
            colors = IconButtonDefaults.iconButtonColors(contentColor = palette.ink),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete ${row.name}",
                modifier = Modifier.size(GLYPH_SIZE),
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
    val palette = LogbookTheme.palette

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.paper,
        contentColor = palette.ink,
        shape = LogbookShapes.square,
        dragHandle = { LogbookSheetHandle() },
    ) {
        // Eleven fields and a Save button do not fit a short screen, a landscape
        // one, or a portrait one with the keyboard up — and a Save button you
        // cannot reach is the whole form wasted.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = SCREEN_PADDING)
                .padding(bottom = SCREEN_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
        ) {
            Text(
                text = (if (isEdit) "Edit tracker" else "New tracker").uppercase(),
                style = LogbookTheme.type.section,
                color = palette.ink,
            )

            val nameError = errors.name.takeIf { showErrors }
            FormField(label = "Name", error = nameError) {
                WellnessDenseField(
                    value = form.name,
                    onValueChange = { next -> onChange { it.copy(name = next) } },
                    placeholder = "e.g. Meditation",
                    modifier = Modifier
                        .fillMaxWidth()
                        .fieldSemantics("Name", nameError),
                )
            }

            CategoryField(form, categories, errors.category.takeIf { showErrors }, onChange)

            SelectField(
                label = "Type",
                value = TYPE_OPTIONS.first { it.first == form.type }.second,
                options = TYPE_OPTIONS.map { it.second },
                onSelect = { index -> onChange { it.copy(type = TYPE_OPTIONS[index].first) } },
            )

            if (form.type == TrackerType.QUANTIFIABLE) {
                QuantifiableFieldsSection(form, onChange)
            }

            ScheduleSection(form, onChange)

            SelectField(
                label = "Polarity",
                value = POLARITY_OPTIONS.first { it.first == form.polarity }.second,
                options = POLARITY_OPTIONS.map { it.second },
                onSelect = { index -> onChange { it.copy(polarity = POLARITY_OPTIONS[index].first) } },
            )

            InkButton(
                label = if (isEdit) "Save changes" else "Add tracker",
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryField(
    form: TrackerFormState,
    categories: List<String>,
    error: String?,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid)) {
        if (categories.isNotEmpty() && !form.useNewCategory) {
            SelectField(
                label = "Category",
                value = form.category.ifEmpty { "Select category…" },
                options = categories,
                error = error,
                onSelect = { index -> onChange { it.copy(category = categories[index]) } },
            )
            QuietTextButton(label = "+ New category") {
                onChange { it.copy(useNewCategory = true) }
            }
        } else {
            FormField(label = "Category", error = error) {
                WellnessDenseField(
                    value = form.newCategory,
                    onValueChange = { next -> onChange { it.copy(newCategory = next) } },
                    placeholder = "e.g. Supplements",
                    modifier = Modifier
                        .fillMaxWidth()
                        .fieldSemantics("Category", error),
                )
            }
            if (categories.isNotEmpty()) {
                QuietTextButton(label = "Use existing") {
                    onChange { it.copy(useNewCategory = false) }
                }
            }
        }
    }
}

@Composable
private fun QuantifiableFieldsSection(
    form: TrackerFormState,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
        // Bottom-aligned so the two input boxes share one line: top-aligned, a
        // label that wraps pushes only its own field down and the pair reads as
        // two different rows.
        verticalAlignment = Alignment.Bottom,
    ) {
        FormField(label = "Unit label", modifier = Modifier.weight(1f)) {
            WellnessDenseField(
                value = form.unit,
                onValueChange = { next -> onChange { it.copy(unit = next) } },
                placeholder = "e.g. mg, min",
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Unit Label" },
            )
        }
        FormField(
            label = "Default value",
            error = form.defaultValueError,
            modifier = Modifier.weight(1f),
        ) {
            WellnessDenseField(
                value = form.defaultValue,
                onValueChange = { next -> onChange { it.copy(defaultValue = next) } },
                numeric = true,
                textAlign = TextAlign.Start,
                placeholder = "e.g. 30",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .fieldSemantics("Default Value", form.defaultValueError),
            )
        }
    }

    MarkCheckbox(
        checked = form.accumulator,
        label = "Running total (accumulator) — tap + to add throughout the day",
        onCheckedChange = { next -> onChange { it.copy(accumulator = next) } },
    )

    // The error shows while typing (the PWA's inline path); the preview shows
    // how the entered text will actually be read under the current polarity.
    val liveError = form.targetError
    val parsedTarget = form.targetParse.target
    FormField(label = "Target", error = liveError) {
        WellnessDenseField(
            value = form.targetInput,
            onValueChange = { next -> onChange { it.copy(targetInput = next) } },
            placeholder = "e.g. 150 or 150-170",
            modifier = Modifier
                .fillMaxWidth()
                .fieldSemantics("Target value or range", liveError),
        )
        if (liveError == null && parsedTarget != null) {
            Text(
                text = "Target: ${formatTarget(parsedTarget, form.unit)}",
                style = LogbookTheme.type.meta,
                color = LogbookTheme.palette.inkSoft,
            )
        }
    }
}

@Composable
private fun ScheduleSection(
    form: TrackerFormState,
    onChange: ((TrackerFormState) -> TrackerFormState) -> Unit,
) {
    val palette = LogbookTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2)) {
        SectionRule(label = "Scheduled days")

        MarkCheckbox(
            checked = form.paused,
            label = "Paused",
            onCheckedChange = { next -> onChange { it.copy(paused = next) } },
        )
        // Free prose is marginalia, never a hint on a control's baseline — the
        // lesson the coach round learned on a device.
        Marginalia("Hidden from the daily view; adherence pauses. History is kept.")

        // Paused dims the picker rather than hiding it: the schedule is still
        // worth reading, it is simply not in effect. The picker itself carries
        // that — it is genuinely disabled — while the line under it stays
        // legible, because "Paused" is the word that explains the dimming.
        WeekdayPicker(
            selected = form.days,
            enabled = !form.paused,
            onToggle = { day -> onChange { it.toggleDay(day) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2)) {
            QuietTextButton(label = "Daily", enabled = !form.paused) {
                onChange { it.copy(days = ALL_DAYS) }
            }
            QuietTextButton(label = "Weekdays", enabled = !form.paused) {
                onChange { it.copy(days = WEEKDAY_PRESET) }
            }
        }
        Text(
            text = if (form.paused) "Paused" else formatScheduleSummary(form.days.ifEmpty { ALL_DAYS }),
            style = LogbookTheme.type.meta,
            color = palette.inkSoft,
        )
    }
}

/**
 * Seven mono initials over seven toggleable ink marks.
 *
 * The mark language rather than `FilterChip`s: a chip is a Material object with
 * its own container, and the page has one surface. A selected day is a filled
 * mark, an unselected one the same hollow outline every unfilled mark in the
 * system uses — so a schedule reads like the week marks it will eventually
 * produce.
 */
@Composable
private fun WeekdayPicker(selected: List<Int>, enabled: Boolean, onToggle: (Int) -> Unit) {
    val palette = LogbookTheme.palette
    Row(modifier = Modifier.fillMaxWidth()) {
        for ((day, label) in WEEKDAY_PICKER) {
            val isSelected = day in selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = LogbookSpace.touchTarget)
                    .toggleable(
                        value = isSelected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(day) },
                    )
                    .semantics { contentDescription = DAY_FULL_NAMES[day] },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style = LogbookTheme.type.eyebrow,
                    color = if (enabled) palette.inkSoft else palette.inkFaint,
                )
                Box(
                    modifier = Modifier
                        .padding(top = LogbookSpace.grid)
                        .size(DAY_MARK_SIZE)
                        .then(
                            when {
                                isSelected && enabled -> Modifier.background(palette.ink, LogbookShapes.soft)
                                isSelected -> Modifier.background(palette.inkFaint, LogbookShapes.soft)
                                else -> Modifier.border(
                                    LogbookSpace.hairline,
                                    palette.inkFaint,
                                    LogbookShapes.soft,
                                )
                            },
                        ),
                )
            }
        }
    }
}

/**
 * A dropdown as a line of the form rather than as a text field.
 *
 * The naked skin draws no box and no chevron — being bare is its whole
 * definition — so a select has to say "there is a menu behind this" itself: the
 * value in ink, a chevron in ink-faint, and the hairline the rest of the form
 * already stands on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LogbookTheme.palette
    FormField(label = label, error = error) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .heightIn(min = LogbookSpace.touchTarget)
                    .hairlineBelow(palette.rule)
                    .fieldSemantics(label, error),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    style = LogbookTheme.type.body,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    // The affordance that says this line opens: a control glyph
                    // keeps the 3:1 non-text floor.
                    tint = palette.inkSoft,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = LogbookTheme.type.body, color = palette.ink) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** A field under its mono-caps name, with its error line beneath. */
@Composable
private inline fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = LogbookTheme.palette.inkSoft,
        )
        content()
        error?.let { FieldError(it) }
    }
}

/**
 * A form error, in ink.
 *
 * There is no error colour in Logbook and this is not a place to invent one: a
 * `!` beside the sentence carries the same urgency. The spoken half lives on
 * the *field's* node via [fieldSemantics], not here — this row is where the
 * error is drawn, but the field is where a reader is standing when they need
 * to hear it.
 */
@Composable
private fun FieldError(message: String) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier.padding(top = LogbookSpace.grid),
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        Text(text = "!", style = LogbookTheme.type.meta, color = palette.ink)
        Text(text = message, style = LogbookTheme.type.body, color = palette.ink)
    }
}

/**
 * An input's spoken identity, with its live error riding the same node.
 *
 * The drawn error line sits below the field, but a reader focusing the field
 * hears the field's node alone — an error announced only from a sibling row is
 * an error the form never actually tells them about.
 */
private fun Modifier.fieldSemantics(description: String, errorMessage: String? = null): Modifier =
    semantics {
        contentDescription = description
        errorMessage?.let { error(it) }
    }

/** A toggle in the mark language: the same ink square the log uses everywhere else. */
@Composable
private fun MarkCheckbox(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = LogbookSpace.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(LogbookSpace.touchTarget),
            contentAlignment = Alignment.Center,
        ) { InkMark(checked) }
        Text(
            text = label,
            style = LogbookTheme.type.body,
            color = palette.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The form's secondary actions: ink words, no container. */
@Composable
private fun QuietTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LogbookTheme.palette
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = palette.ink,
            disabledContentColor = palette.inkFaint,
        ),
    ) { Text(label.uppercase(), style = LogbookTheme.type.eyebrow) }
}

/** A section label over the 1.5dp ink rule that groups what follows it. */
@Composable
private fun SectionRule(label: String, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Text(
        text = label.uppercase(),
        style = LogbookTheme.type.section,
        color = palette.ink,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = LogbookSpace.sectionUnderline.toPx()
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(bottom = SECTION_UNDERLINE_GAP),
    )
}

/** Guidance the app is giving the user: italic prose behind a rail, never a hint. */
@Composable
private fun Marginalia(text: String) {
    val palette = LogbookTheme.palette
    Text(
        text = text,
        style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
        color = palette.inkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = MARGINALIA_RAIL.toPx()
                drawLine(
                    color = palette.ruleStrong,
                    start = Offset(stroke / 2f, 0f),
                    end = Offset(stroke / 2f, size.height),
                    strokeWidth = stroke,
                )
            }
            .padding(start = MARGINALIA_INSET),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
        color = LogbookTheme.palette.inkSoft,
        textAlign = TextAlign.Center,
    )
}

/** The system's one divider: a 1dp line along the bottom edge, drawn not laid out. */
private fun Modifier.hairlineBelow(color: Color): Modifier = this.drawBehind {
    val stroke = LogbookSpace.hairline.toPx()
    drawLine(
        color = color,
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke,
    )
}

/** Monday through Friday, in the store's 0=Sun numbering. */
private val WEEKDAY_PRESET = listOf(1, 2, 3, 4, 5)

private val SCREEN_PADDING = 20.dp
private val SCREEN_BOTTOM = 40.dp
private val SECTION_GAP = 26.dp
private val SECTION_UNDERLINE_GAP = 6.dp
private val GLYPH_SIZE = 18.dp
private val DAY_MARK_SIZE = 18.dp
private val MARGINALIA_RAIL = 2.dp
private val MARGINALIA_INSET = 10.dp
private val EMPTY_PADDING = 48.dp
