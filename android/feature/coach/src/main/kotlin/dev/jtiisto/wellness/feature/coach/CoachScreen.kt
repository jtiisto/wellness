package dev.jtiisto.wellness.feature.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.ui.SyncStatusIndicator
import dev.jtiisto.wellness.core.ui.hr.HrBpmChip
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import org.koin.androidx.compose.koinViewModel

/**
 * Every callback the day view needs, bundled.
 *
 * A plain holder rather than sixteen parameters threaded through five levels of
 * composable. Remembered against the ViewModel so recomposition does not hand
 * the tree a fresh instance and defeat skipping.
 */
@Suppress("LongParameterList")
class CoachActions(
    val onSelectDate: (String) -> Unit,
    val onPreviousMonth: () -> Unit,
    val onNextMonth: () -> Unit,
    val onToday: () -> Unit,
    val onToggleExercise: (String) -> Unit,
    val onCommitSetCell: (String, Int, String, String) -> Unit,
    val onSetCompleted: (String, Int, Boolean) -> Unit,
    val onCommitCardioField: (String, String, String) -> Unit,
    val onToggleChecklistItem: (String, String) -> Unit,
    val onExerciseNote: (String, String) -> Unit,
    val onFeedback: (String, String) -> Unit,
    val onFireHook: (HookAction) -> Unit,
    val onUndoHook: (HookAction) -> Unit,
    val onConnectStrap: () -> Unit,
    val onSkipStrap: () -> Unit,
    val onStopCapture: () -> Unit,
    val onSaveExtraSession: (ExtraSessionDraft) -> Unit,
    val onCommitExtraSessionField: (String, String) -> Unit,
    val onDeleteExtraSession: () -> Unit,
)

/**
 * The Coach tab: the date header with its calendar popup, and the selected day's
 * workout.
 *
 * Everything drawn here comes off [CoachUiState]. The composables make no
 * decisions of their own, which is what keeps the module's real rules — the
 * entry gate above all — in JVM tests rather than in an emulator.
 */
@Composable
fun CoachScreen(viewModel: CoachViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Editability is "the selected day is today", so a process that sat in the
    // background across midnight has to re-read the clock on the way back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenShown()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val actions = remember(viewModel) {
        CoachActions(
            onSelectDate = viewModel::selectDate,
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onToday = viewModel::goToToday,
            onToggleExercise = viewModel::toggleExercise,
            onCommitSetCell = viewModel::commitSetCell,
            onSetCompleted = viewModel::setSetCompleted,
            onCommitCardioField = viewModel::commitCardioField,
            onToggleChecklistItem = viewModel::toggleChecklistItem,
            onExerciseNote = viewModel::setExerciseNote,
            onFeedback = viewModel::setFeedback,
            onFireHook = viewModel::fireHook,
            onUndoHook = viewModel::undoHook,
            onConnectStrap = viewModel::connectStrap,
            onSkipStrap = viewModel::skipStrap,
            onStopCapture = viewModel::stopCapture,
            onSaveExtraSession = viewModel::saveExtraSession,
            onCommitExtraSessionField = viewModel::commitExtraSessionField,
            onDeleteExtraSession = viewModel::deleteExtraSession,
        )
    }

    val strapPrompt by viewModel.strapPrompt.collectAsStateWithLifecycle()

    CoachContent(state = state, actions = actions)

    strapPrompt?.let { prompt ->
        ConnectStrapSheet(
            prompt = prompt,
            onConnect = actions.onConnectStrap,
            onSkip = actions.onSkipStrap,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoachContent(state: CoachUiState, actions: CoachActions) {
    val palette = LogbookTheme.palette
    // Local because it is a view of state that already exists: the sheet can
    // only be open while there is a capture to describe, and `state.hr` going
    // null closes it on its own.
    var captureSheetOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Coach".uppercase(), style = LogbookTheme.type.section) },
            colors = TopAppBarDefaults.topAppBarColors(
                // Paper, like everything else — the bar is not a second surface.
                containerColor = palette.paper,
                titleContentColor = palette.ink,
            ),
            windowInsets = WindowInsets(0),
            actions = {
                // No chip when idle — `state.hr` is null precisely then.
                state.hr?.let { hr ->
                    HrBpmChip(display = hr, onClick = { captureSheetOpen = true })
                }
                SyncStatusIndicator(
                    status = state.syncStatus,
                    syncing = state.isSyncing,
                    // The colour exception is documented; the type is not — the
                    // label reads in Logbook's own mono, not Graphite's ramp.
                    textStyle = LogbookTheme.type.meta,
                    labelColor = palette.inkSoft,
                    modifier = Modifier.padding(end = LogbookSpace.grid * 3),
                )
            },
        )

        CalendarPicker(state = state, actions = actions)

        WorkoutDayView(day = state.day, actions = actions, modifier = Modifier.weight(1f))
    }

    // Read from the same nullable as the chip: a capture that ends while its
    // sheet is open takes the sheet with it rather than leaving a panel
    // describing a session that no longer exists.
    val hr = state.hr
    if (captureSheetOpen && hr != null) {
        CaptureStatusSheet(
            display = hr,
            link = state.hrLink,
            onStop = {
                captureSheetOpen = false
                actions.onStopCapture()
            },
            onDismiss = { captureSheetOpen = false },
        )
    }
}

// ---- calendar ------------------------------------------------------------------

/**
 * The date trigger and its popup.
 *
 * A [Popup] rather than a dialog so the day view stays visible behind it, and
 * with `focusable` so the system back gesture dismisses it — the PWA's
 * document-level outside-click listener has no equivalent here.
 */
@Composable
private fun CalendarPicker(state: CoachUiState, actions: CoachActions) {
    var open by remember { mutableStateOf(false) }
    var triggerHeight by remember { mutableIntStateOf(0) }
    val palette = LogbookTheme.palette

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Paper with a hairline under it: the chrome fill retires, and
                // the rule is what separates the strip from the day below.
                .background(palette.paper)
                .drawWithContent {
                    drawContent()
                    val stroke = LogbookSpace.hairline.toPx()
                    drawLine(
                        color = palette.rule,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }
                .clickable { open = !open }
                .onSizeChanged { triggerHeight = it.height }
                .padding(horizontal = STRIP_PADDING, vertical = LogbookSpace.grid * 3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarToday,
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier.size(TRIGGER_GLYPH),
            )
            Text(
                text = state.dateCaption,
                style = LogbookTheme.type.name,
                color = palette.ink,
            )
            // The drawn mark says completed/scheduled/missed to the eye; the
            // semantics say it to TalkBack, or the trigger row is blind to it.
            DayMarkGlyph(
                mark = state.selectedMark,
                color = palette.ink,
                markSize = DAY_MARK_SIZE,
                modifier = state.selectedMark.a11yLabel()
                    ?.let { label -> Modifier.semantics { contentDescription = label } }
                    ?: Modifier,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (open) "Close calendar" else "Open calendar",
                tint = palette.inkSoft,
            )
        }

        // The popup outlives `open` by one transition: dismissing sets the
        // target false, the card plays its exit, and only once the transition
        // is idle does the window come down. Removing it on `open` alone — the
        // obvious way to write this — makes the exit unreachable, because the
        // composable it belongs to is gone before it can run a frame.
        val calendarVisible = remember { MutableTransitionState(false) }
        calendarVisible.targetState = open
        if (calendarVisible.currentState || calendarVisible.targetState) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, triggerHeight),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = calendarVisible,
                    enter = WellnessMotion.popoverEnter,
                    exit = WellnessMotion.exitFadeThrough,
                ) {
                    CalendarCard(
                        calendar = state.calendar,
                        actions = actions,
                        onDismiss = { open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarCard(calendar: CalendarState, actions: CoachActions, onDismiss: () -> Unit) {
    val palette = LogbookTheme.palette
    Surface(
        modifier = Modifier.padding(horizontal = LogbookSpace.grid * 2),
        // A drawn border instead of a shadow: the design is flat inside the
        // screen, and a floating card is still the same sheet of paper.
        shape = LogbookShapes.soft,
        color = palette.paper,
        contentColor = palette.ink,
        border = BorderStroke(LogbookSpace.hairline, palette.ruleStrong),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(LogbookSpace.grid * 3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onPreviousMonth, enabled = calendar.canGoPrev) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronLeft,
                        contentDescription = "Previous month",
                        tint = if (calendar.canGoPrev) palette.ink else palette.inkFaint,
                    )
                }
                Text(
                    text = calendar.monthCaption.uppercase(),
                    style = LogbookTheme.type.section,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = actions.onNextMonth) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = "Next month",
                        tint = palette.ink,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                for (label in calendar.weekdayLabels) {
                    Text(
                        text = label.uppercase(),
                        style = LogbookTheme.type.tableHeader,
                        color = palette.inkFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Six fixed rows of seven, so paging never resizes the card.
            calendar.cells.chunked(DAYS_PER_WEEK).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (cell in week) {
                        DayCell(
                            cell = cell,
                            onClick = {
                                actions.onSelectDate(cell.date)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            TextButton(
                onClick = {
                    actions.onToday()
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = palette.ink),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("TODAY", style = LogbookTheme.type.eyebrow) }

            // The legend speaks the marks' language, not a colour's: there are
            // no status hues left to name.
            FlowRow(
                modifier = Modifier.padding(top = LogbookSpace.grid),
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
                verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
            ) {
                for (status in WorkoutStatus.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid + 2.dp),
                    ) {
                        DayMarkGlyph(mark = dayMark(status), color = palette.ink, markSize = DAY_MARK_SIZE)
                        Text(
                            text = statusLabel(status).uppercase(),
                            style = LogbookTheme.type.eyebrow,
                            color = palette.inkSoft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(cell: CalendarCell, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = buildString {
        append(cell.date)
        cell.status?.let { append(", ${statusLabel(it)}") }
        if (cell.isToday) append(", today")
        if (!cell.enabled) append(", unavailable")
    }
    val palette = LogbookTheme.palette
    // On a selected day the ink is spent on the fill, so everything drawn on
    // top of it inverts to paper.
    val onCell = if (cell.isSelected) palette.paper else palette.ink

    Box(
        modifier = modifier
            .height(MIN_TOUCH_TARGET)
            .background(if (cell.isSelected) palette.ink else Color.Transparent)
            .clickable(enabled = cell.enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(
                when {
                    !cell.enabled -> DISABLED_ALPHA
                    !cell.inViewMonth -> OTHER_MONTH_ALPHA
                    else -> 1f
                },
            ),
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                style = LogbookTheme.type.data,
                color = onCell,
                modifier = Modifier
                    .then(
                        // Today is underlined rather than emboldened: weight is
                        // how the log says "logged", and this day may not be.
                        if (cell.isToday) {
                            Modifier.drawBehind {
                                val stroke = TODAY_UNDERLINE.toPx()
                                drawLine(
                                    color = onCell,
                                    start = Offset(0f, size.height - stroke / 2f),
                                    end = Offset(size.width, size.height - stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(bottom = TODAY_UNDERLINE_GAP)
                    .clearAndSetSemantics { },
            )
            Box(modifier = Modifier.height(MARK_ROW_HEIGHT)) {
                DayMarkGlyph(mark = cell.mark, color = onCell, markSize = DAY_MARK_SIZE)
            }
        }
    }
}

/**
 * A day's status as notation.
 *
 * The slash is drawn rather than typed: a `/` glyph would sit on the text
 * baseline at whatever weight the face happens to have, next to two marks that
 * are geometry.
 */
@Composable
private fun DayMarkGlyph(mark: DayMark, color: Color, markSize: Dp, modifier: Modifier = Modifier) {
    if (mark == DayMark.NONE) return
    Canvas(modifier = modifier.size(markSize)) {
        val stroke = DAY_MARK_STROKE.toPx()
        when (mark) {
            DayMark.FILLED -> drawCircle(color = color)
            DayMark.OUTLINED -> drawCircle(
                color = color,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )

            DayMark.SLASHED -> drawLine(
                color = color,
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
                strokeWidth = stroke,
            )

            DayMark.NONE -> Unit
        }
    }
}

private fun statusLabel(status: WorkoutStatus): String = when (status) {
    WorkoutStatus.COMPLETED -> "Completed"
    WorkoutStatus.MISSED -> "Missed"
    WorkoutStatus.SCHEDULED -> "Scheduled"
}

private const val DAYS_PER_WEEK = 7
private const val OTHER_MONTH_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.25f

private val STRIP_PADDING = 16.dp
private val TRIGGER_GLYPH = 16.dp
private val DAY_MARK_SIZE = 8.dp
private val DAY_MARK_STROKE = 1.5.dp
private val MARK_ROW_HEIGHT = 10.dp
private val TODAY_UNDERLINE = 1.5.dp
private val TODAY_UNDERLINE_GAP = 2.dp

/** Android's minimum comfortable tap size; the PWA's 20 px cells miss it. */
internal val MIN_TOUCH_TARGET = 48.dp
