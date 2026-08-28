package dev.jtiisto.wellness.feature.journal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.journal.TargetProgress
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.describeCategoryRollup
import dev.jtiisto.wellness.core.ui.SyncStatusDot
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkMarkToggle
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSheetHandle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.ROLLUP_MARK_SIZE
import dev.jtiisto.wellness.core.ui.theme.WeekMarkGlyph
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.a11yLabel
import org.koin.androidx.compose.koinViewModel

/**
 * The journal day view, on paper.
 *
 * The header block, a strip of seven days, categories as underlined sections and
 * one row per tracker — drawn entirely in ink. The journal has no tiers, so it
 * spends no colour at all: everything the retired palette used to say (held,
 * slipped, off-schedule, not yet judged) is said by the shape of a mark instead.
 *
 * Nothing here decides anything. Which mark a day earns, what the eyebrow says,
 * which class a row belongs to and what its run reads aloud all arrive already
 * derived from `JournalNotation` and [JournalUiState] — which is what keeps the
 * day view's real rules in JVM tests rather than in an emulator.
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
        onRefresh = viewModel::refresh,
        onSelectDate = viewModel::selectDate,
        onToggleCategory = viewModel::toggleCategory,
        onChecked = viewModel::setChecked,
        onCommitNumeric = viewModel::commitNumeric,
        onAccumulate = viewModel::addToAccumulator,
        onSlider = viewModel::setSlider,
        onNote = viewModel::setNote,
    )
}

@Composable
private fun JournalContent(
    state: JournalUiState,
    onOpenConfig: () -> Unit,
    onRefresh: () -> Unit,
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
        JournalHeader(state = state, onOpenConfig = onOpenConfig, onSelectDate = onSelectDate)

        // Below the masthead, never around it: the eyebrow, the title and the
        // date strip are the page's fixed furniture, and dragging them down to
        // reveal a spinner would move the one thing on screen that says which
        // day is being looked at.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
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
                    contentPadding = PaddingValues(bottom = SCREEN_BOTTOM),
                ) {
                    for (group in state.groups) {
                        item(key = "category-${group.name}") {
                            SectionHead(
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

// ---- header block --------------------------------------------------------------

/**
 * Eyebrow, title, strip, legend — the page's masthead.
 *
 * The app bar retires with it: a `TopAppBar` here would put the word "Journal"
 * above a heading that already says it, and the two things the bar was carrying
 * (the sync dot and the settings gear) sit better on the title's own line, where
 * the display type gives their 48dp targets somewhere to stand.
 */
@Composable
private fun JournalHeader(
    state: JournalUiState,
    onOpenConfig: () -> Unit,
    onSelectDate: (String) -> Unit,
) {
    val palette = LogbookTheme.palette
    Column(modifier = Modifier.padding(horizontal = SCREEN_PADDING)) {
        Spacer(Modifier.height(SCREEN_TOP))
        Text(
            text = state.eyebrow.label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Journal".uppercase(),
                style = LogbookTheme.type.display,
                color = palette.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = TITLE_TOP),
            )
            // The one thing on the page carrying colour, and it carries it for
            // the documented reason: the sync state is transient device truth,
            // not decoration. It draws no text, and its four colours are the
            // live-signal set — resolved against this page's own mode.
            //
            // The pulse is what a pull has to be able to see. Until the journal
            // store's busy flag became observable it was permanently static
            // here, so a sync that changed nothing changed nothing on screen.
            SyncStatusDot(
                state.syncStatus,
                syncing = state.isSyncing,
                modifier = Modifier.padding(end = LogbookSpace.grid),
            )
            IconButton(
                onClick = onOpenConfig,
                colors = IconButtonDefaults.iconButtonColors(contentColor = palette.ink),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Tracker settings",
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }

        DateStrip(cells = state.dateStrip, onSelectDate = onSelectDate)
        ShapeLegend(modifier = Modifier.padding(top = LEGEND_TOP))
    }
}

/**
 * The shape legend.
 *
 * Coach's legend is load-bearing because its plate colours are positional; this
 * one is load-bearing because a shape vocabulary has to be handed to a reader
 * once. Seven marks and the words `JournalNotation` already gives them — the two
 * omitted are the slashed and hollow forms of marks named here, and the line has
 * taught both modifiers by the time they appear.
 *
 * The marks themselves are geometry a screen reader cannot see, so the row reads
 * as one node naming the vocabulary rather than as seven unattached words.
 */
@Composable
private fun ShapeLegend(modifier: Modifier = Modifier) {
    val spoken = JOURNAL_SHAPE_LEGEND.joinToString { it.a11yLabel() }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Mark key: $spoken" },
        horizontalArrangement = Arrangement.spacedBy(LEGEND_GAP),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        for (mark in JOURNAL_SHAPE_LEGEND) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid + 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeekMarkGlyph(mark)
                Text(
                    text = mark.a11yLabel().uppercase(),
                    style = LogbookTheme.type.eyebrow,
                    color = LogbookTheme.palette.inkSoft,
                )
            }
        }
    }
}

// ---- date strip ------------------------------------------------------------

@Composable
private fun DateStrip(cells: List<DateCellState>, onSelectDate: (String) -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = STRIP_TOP)
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawLine(
                    color = palette.rule,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .semantics { contentDescription = "Select date" },
    ) {
        for (cell in cells) {
            DateCell(cell = cell, onClick = { onSelectDate(cell.date) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One column of the strip.
 *
 * Today wears no mark of its own: the strip is a trailing window that *ends*
 * today, so the last column is today by construction and a badge saying so would
 * be the page repeating its own shape back at itself. The selected day is what
 * needs marking, and it is marked the way the calendar marks its own — ink
 * underneath, ink in the numeral.
 */
@Composable
private fun DateCell(cell: DateCellState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    val label = buildString {
        append("${cell.dayName} ${cell.dayNum}")
        if (cell.isToday) append(", today")
    }
    val numeral = if (cell.isSelected) palette.ink else palette.inkSoft
    Box(
        modifier = modifier
            .heightIn(min = LogbookSpace.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (cell.isSelected) {
                    Modifier.drawBehind {
                        val stroke = SELECTED_UNDERLINE.toPx()
                        val inset = size.width * SELECTED_UNDERLINE_INSET
                        drawLine(
                            color = palette.ink,
                            start = Offset(inset, size.height - stroke / 2f),
                            end = Offset(size.width - inset, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = label },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = LogbookSpace.grid),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = cell.initial.uppercase(),
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = cell.dayNum.toString(),
                style = LogbookTheme.type.data.copy(
                    fontWeight = if (cell.isSelected) FontWeight.Medium else FontWeight.Normal,
                ),
                color = numeral,
                modifier = Modifier
                    .padding(top = STRIP_NUMERAL_GAP)
                    .clearAndSetSemantics { },
            )
        }
    }
}

// ---- sections -------------------------------------------------------------

/**
 * A category, drawn as a section: chevron, display-caps label, rollup cluster,
 * and the 1.5dp ink rule under all three.
 *
 * The whole head is one node. A merged clickable replaces its children's
 * semantics, so the category name, the cluster's sentence and the expanded state
 * all have to be said here or they are not said at all — the same lesson coach's
 * collapsible header learned.
 *
 * Measurement order is the layout rule: the cluster is unweighted so it measures
 * **first** and can never be squeezed out by a long category name, and it is
 * safe to give it that priority because its width is bounded by the category's
 * own tracker count rather than by free text. The name takes what remains and
 * wraps, which is the readable failure of the two.
 */
@Composable
private fun SectionHead(group: CategoryGroupState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    val rotation by animateFloatAsState(
        targetValue = if (group.expanded) 0f else -QUARTER_TURN,
        label = "category-chevron",
    )
    val spoken = buildString {
        append(group.name)
        group.rollup?.let { append(", ${describeCategoryRollup(it)}") }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = SECTION_GAP)
            .padding(horizontal = SCREEN_PADDING)
            .clickable(
                onClickLabel = if (group.expanded) "Collapse" else "Expand",
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                stateDescription = if (group.expanded) "Expanded" else "Collapsed"
            }
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = palette.inkSoft,
            modifier = Modifier
                .size(CHEVRON_SIZE)
                .rotate(rotation),
        )
        Text(
            text = group.name.uppercase(),
            style = LogbookTheme.type.section,
            color = palette.ink,
            modifier = Modifier
                .weight(1f)
                .padding(start = LogbookSpace.grid * 2, end = LogbookSpace.grid * 2),
        )
        group.rollup?.let { rollup ->
            rollupCluster(rollup)?.let { RollupClusterRow(it) }
        }
    }
}

/**
 * The category's day as one flat cluster: habit marks, then the avoidance hoop,
 * then the observation diamond and its count.
 *
 * A wider gap between classes than within one is the whole of what says these
 * are three different questions. Everything drawn was decided by
 * [rollupCluster]; the cluster's sentence lives on the head above, so the count
 * is silenced here rather than read out as a stray number.
 */
@Composable
private fun RollupClusterRow(cluster: RollupCluster) {
    Row(
        modifier = Modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(CLUSTER_CLASS_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cluster.habits.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CLUSTER_MARK_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (mark in cluster.habits) {
                    WeekMarkGlyph(mark = mark, markSize = ROLLUP_MARK_SIZE)
                }
            }
        }
        cluster.avoidance?.let { WeekMarkGlyph(mark = it, markSize = ROLLUP_MARK_SIZE) }
        cluster.observation?.let { observation ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(CLUSTER_MARK_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeekMarkGlyph(mark = observation.mark, markSize = ROLLUP_MARK_SIZE)
                Text(
                    text = observation.noted.toString(),
                    style = LogbookTheme.type.meta,
                    color = LogbookTheme.palette.inkSoft,
                )
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    val palette = LogbookTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SCREEN_PADDING)
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawLine(
                    color = palette.rule,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(top = ROW_TOP, bottom = ROW_BOTTOM),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.type != TrackerType.NOTE) {
                InkMarkToggle(
                    checked = row.checked,
                    editable = true,
                    description = if (row.checked) "${row.name}, done" else "${row.name}, not done",
                    onCheckedChange = { onChecked(row.id, it) },
                )
            } else {
                Spacer(Modifier.width(MARK_COLUMN))
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.name,
                    style = LogbookTheme.type.name,
                    color = palette.ink,
                    // Shrink-wrapped so the off-schedule label sits against the
                    // name rather than adrift at the far end of the row.
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.offSchedule) {
                    // A non-day the user wrote on anyway. Stated in the mono
                    // label voice rather than tinted: there is no failure here
                    // to colour, only a fact about the schedule.
                    Text(
                        text = OFF_SCHEDULE_LABEL.uppercase(),
                        style = LogbookTheme.type.eyebrow,
                        color = palette.inkSoft,
                        modifier = Modifier.padding(start = LogbookSpace.grid * 1.5f),
                    )
                }
            }
            when (row.type) {
                TrackerType.QUANTIFIABLE -> NumericField(row, onCommitNumeric, onOpenAccumulator)
                TrackerType.EVALUATION -> EvaluationSlider(row, onSlider)
                else -> Unit
            }
        }

        if (row.type == TrackerType.NOTE) {
            NoteField(row, onNote)
        }

        Row(
            modifier = Modifier.padding(start = MARK_COLUMN, top = SUBLINE_TOP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted, so the run of marks — bounded at seven by construction —
            // measures first and keeps its place whatever the target line says.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = LogbookSpace.grid * 2),
            ) {
                row.targetProgress?.let { TargetLine(it) }
            }
            WeekMarkRun(row)
        }

        row.lastUpdatedCaption?.let {
            Text(
                text = "Last updated $it",
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
                modifier = Modifier.padding(start = MARK_COLUMN, top = LogbookSpace.grid),
            )
        }
    }
}

/**
 * The target, in words and then as a bar.
 *
 * No tone: `ProgressTone` was a colour axis and Logbook has none, so met,
 * partial and over all read in the same ink. The sentence already distinguishes
 * them — "6 / 8 glasses" and "9 of 8 mg · over by 1" do not need a hue to be
 * told apart — and an overshoot was never a failure to alarm about anyway.
 *
 * The bar appears only for an at-least target, exactly as before: there is no
 * honest way to draw progress towards a ceiling.
 */
@Composable
private fun TargetLine(progress: TargetProgress) {
    val palette = LogbookTheme.palette
    Text(
        text = progress.text,
        style = LogbookTheme.type.meta,
        color = palette.inkSoft,
    )
    progress.fillPct?.let { pct ->
        Box(
            modifier = Modifier
                .padding(top = LogbookSpace.grid)
                .widthIn(max = TARGET_BAR_MAX_WIDTH)
                .fillMaxWidth()
                .height(TARGET_BAR_HEIGHT)
                .background(palette.rule)
                .clearAndSetSemantics { },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((pct / 100.0).toFloat())
                    .height(TARGET_BAR_HEIGHT)
                    .background(palette.ink),
            )
        }
    }
}

/**
 * The seven-day run.
 *
 * One semantics node, not seven: it is a single glance, and a reader stopping on
 * each mark would hear geometry with no sentence around it. What it says is the
 * *drawn* marks — so a verdict the today-open rule suspended survives aloud by
 * construction, and an open day is never read out as "missed" to the one
 * audience that cannot see the difference.
 */
@Composable
private fun WeekMarkRun(row: TrackerRowState) {
    val description = row.marksDescription
    Row(
        modifier = if (description == null) {
            Modifier
        } else {
            Modifier.clearAndSetSemantics { contentDescription = description }
        },
        horizontalArrangement = Arrangement.spacedBy(WEEK_MARK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (mark in row.marks) {
            WeekMarkGlyph(mark = mark.mark, ringed = mark.ringed)
        }
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
    val palette = LogbookTheme.palette

    val commit = {
        onCommit(row.id, row.displayedNumber, text)
        text = row.valueText
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        if (row.isAccumulator) {
            // The "+" leads the cluster rather than trailing it, so every row's
            // value ends against its unit at the same trailing edge whether or
            // not it has one — trailing, it pushed only its own row's numbers
            // leftward and the column read as three unrelated stops
            // (device-found, 2026-08-18).
            IconButton(
                onClick = onOpenAccumulator,
                // No explicit size: M3's own 48dp is the target Android asks
                // for, and it costs the row nothing — the entry mark on the
                // other side already holds the line at 48dp.
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = palette.ink,
                    disabledContentColor = palette.inkFaint,
                ),
            ) {
                // A plus is the affordance rather than a decoration of it —
                // there is no word for "add to today's running total" that fits
                // beside a value field.
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add to ${row.name} total",
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }
        WellnessDenseField(
            value = text,
            onValueChange = { text = it },
            numeric = true,
            // A default nobody has committed to yet is not the user's number.
            // On paper that recedes in ink rather than leaning into italics.
            ghostValue = !row.committed,
            // End, the table convention — the device pass overruled the spec's
            // Start here: these rows stack into a column read down the page,
            // and Start-aligned digits in a fixed field left a gulf between
            // each number and its own unit. End welds the pair into the one
            // right-aligned token the mockups draw ("22 min", never "22   min").
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(VALUE_FIELD_WIDTH)
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
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
            )
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
    val palette = LogbookTheme.palette

    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = position,
            onValueChange = {
                dragging = true
                position = it
                onSlider(row.id, it)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..SLIDER_MAX,
            steps = SLIDER_INTERMEDIATE_STOPS,
            // Named explicitly rather than inherited: M3 would take `primary`
            // for the track and its own disabled greys for the rest, and this
            // is the one control on the page with enough surface to matter.
            colors = SliderDefaults.colors(
                thumbColor = palette.ink,
                activeTrackColor = palette.ink,
                activeTickColor = palette.paper,
                inactiveTrackColor = palette.rule,
                inactiveTickColor = palette.inkFaint,
                disabledThumbColor = palette.inkFaint,
                disabledActiveTrackColor = palette.inkFaint,
                disabledInactiveTrackColor = palette.rule,
            ),
            modifier = Modifier
                .width(SLIDER_WIDTH)
                .semantics { contentDescription = "${row.name} rating" },
        )
        Spacer(Modifier.width(LogbookSpace.grid))
        Text(
            text = position.toInt().toString(),
            style = LogbookTheme.type.data,
            color = if (row.committed) palette.ink else palette.inkFaint,
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
 *
 * Bare, with no label of its own: the tracker's name is directly above it and is
 * the only name this note has.
 */
@Composable
private fun NoteField(row: TrackerRowState, onNote: (String, String) -> Unit) {
    var text by remember(row.id) { mutableStateOf(row.valueText) }
    var focused by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.valueText, focused) {
        if (!focused) text = row.valueText
    }

    WellnessDenseField(
        value = text,
        onValueChange = {
            text = it
            onNote(row.id, it)
        },
        multiLine = true,
        placeholder = "Add note…",
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MARK_COLUMN)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "${row.name} note" },
    )
}

// ---- accumulator sheet ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccumulatorSheet(row: TrackerRowState, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val palette = LogbookTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.paper,
        contentColor = palette.ink,
        shape = LogbookShapes.square,
        dragHandle = { LogbookSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = SHEET_PADDING, end = SHEET_PADDING, bottom = SHEET_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        ) {
            Text(
                text = "Add to ${row.name}".uppercase(),
                style = LogbookTheme.type.section,
                color = palette.ink,
            )
            Text(
                text = (if (row.unit != null) "Amount (${row.unit})" else "Amount").uppercase(),
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
            )
            WellnessDenseField(
                value = input,
                onValueChange = { input = it },
                numeric = true,
                textAlign = TextAlign.Start,
                placeholder = "e.g. 25",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd(input) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Amount to add" },
            )
            InkButton(
                label = "Add",
                onClick = { onAdd(input) },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/**
 * Whatever is not there yet, said in the italic ink-faint voice reserved for
 * absence.
 *
 * A one-item `LazyColumn` rather than the `Column` this was, for a reason that
 * has nothing to do with laziness: the pull gesture is nested-scroll based, so a
 * child that installs no scroll modifier dispatches nothing and the pull is dead
 * on an empty day — the one day a user is most likely to pull, having found
 * nothing. A lazy list installs `Modifier.scrollable` whether or not its content
 * overflows, so the events flow.
 *
 * `Modifier.verticalScroll` would have done the same job and cost the centring:
 * it measures its content against an unbounded height, leaving
 * `Arrangement.Center` no slack to centre into, and the message would ride up
 * under the legend.
 */
@Composable
private fun EmptyState(title: String, detail: String?) {
    val palette = LogbookTheme.palette
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SCREEN_PADDING, vertical = EMPTY_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Text(
                text = title,
                style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
                color = palette.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
        detail?.let { text ->
            item {
                Spacer(Modifier.height(LogbookSpace.grid * 2))
                Text(
                    text = text,
                    style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
                    color = palette.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The one word a row says about a day nothing was asked of it. */
private const val OFF_SCHEDULE_LABEL = "off"

/** `step=25` in the PWA: five stops, so three between the ends. */
private const val SLIDER_INTERMEDIATE_STOPS = 3
private const val SLIDER_MAX = 100f
private const val QUARTER_TURN = 90f

/** The page margin: the mockups' 20dp of paper down each side. */
private val SCREEN_PADDING = 20.dp
private val SCREEN_TOP = 22.dp
private val SCREEN_BOTTOM = 40.dp

private val TITLE_TOP = 10.dp
private val STRIP_TOP = 14.dp
private val STRIP_NUMERAL_GAP = 3.dp
private val SELECTED_UNDERLINE = 2.dp

/** The underline sits under the numeral, not under the whole column. */
private const val SELECTED_UNDERLINE_INSET = 0.22f

private val LEGEND_TOP = 14.dp
private val LEGEND_GAP = 14.dp

private val SECTION_GAP = 26.dp
private val SECTION_UNDERLINE_GAP = 6.dp

/**
 * The entry mark's gutter. Everything below a row's first line indents past it,
 * and it is the mark's touch target as well as its column — Android law beats
 * paper density, and the journal has always spent this width.
 */
private val MARK_COLUMN = 48.dp
private val ROW_TOP = 12.dp
private val ROW_BOTTOM = 11.dp
private val SUBLINE_TOP = 7.dp
private val WEEK_MARK_GAP = 6.dp

private val CLUSTER_CLASS_GAP = 9.dp
private val CLUSTER_MARK_GAP = 4.dp

private val TARGET_BAR_MAX_WIDTH = 150.dp
private val TARGET_BAR_HEIGHT = 3.dp

private val VALUE_FIELD_WIDTH = 72.dp
private val SLIDER_WIDTH = 140.dp

private val GLYPH_SIZE = 18.dp
private val CHEVRON_SIZE = 14.dp

private val SHEET_PADDING = 20.dp
private val SHEET_BOTTOM = 24.dp
private val EMPTY_PADDING = 48.dp
