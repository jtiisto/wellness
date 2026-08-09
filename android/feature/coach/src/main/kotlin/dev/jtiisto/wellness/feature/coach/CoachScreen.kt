package dev.jtiisto.wellness.feature.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
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
            onSaveExtraSession = viewModel::saveExtraSession,
            onCommitExtraSessionField = viewModel::commitExtraSessionField,
            onDeleteExtraSession = viewModel::deleteExtraSession,
        )
    }

    CoachContent(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoachContent(state: CoachUiState, actions: CoachActions) {
    val palette = WellnessTheme.palette
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Coach", style = WellnessTheme.type.headline) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = palette.canvas,
                titleContentColor = palette.textPrimary,
            ),
            windowInsets = WindowInsets(0),
            actions = {
                SyncStatusIndicator(
                    status = state.syncStatus,
                    syncing = state.isSyncing,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
        )

        CalendarPicker(state = state, actions = actions)

        WorkoutDayView(day = state.day, actions = actions, modifier = Modifier.weight(1f))
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
    val palette = WellnessTheme.palette

    Box(modifier = Modifier.fillMaxWidth()) {
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
                .clickable { open = !open }
                .onSizeChanged { triggerHeight = it.height }
                .padding(horizontal = WellnessSpace.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = state.dateCaption,
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
            )
            state.selectedStatus?.let { StatusDot(it) }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (open) "Close calendar" else "Open calendar",
                tint = palette.textSecondary,
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
    val palette = WellnessTheme.palette
    Surface(
        modifier = Modifier.padding(horizontal = WellnessSpace.sm),
        // It floats, so it gets a real shadow and the floating radius — and the
        // card surface, never the canvas it is hovering over.
        shape = WellnessShape.floating,
        color = palette.card,
        contentColor = palette.textPrimary,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onPreviousMonth, enabled = calendar.canGoPrev) {
                    Icon(
                        imageVector = Icons.Filled.ChevronLeft,
                        contentDescription = "Previous month",
                        tint = palette.textSecondary,
                    )
                }
                Text(
                    text = calendar.monthCaption,
                    style = WellnessTheme.type.title,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = actions.onNextMonth) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Next month",
                        tint = palette.textSecondary,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                for (label in calendar.weekdayLabels) {
                    Text(
                        text = label.uppercase(),
                        style = WellnessTheme.type.micro,
                        color = palette.textFaint,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                colors = WellnessDefaults.accentTextButtonColors(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Today") }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (status in WorkoutStatus.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusDot(status, size = 6.dp)
                        Text(
                            text = statusLabel(status),
                            style = WellnessTheme.type.label,
                            color = palette.textSecondary,
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
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Surface(
        onClick = onClick,
        enabled = cell.enabled,
        modifier = modifier
            .height(MIN_TOUCH_TARGET)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(6.dp),
        color = if (cell.isSelected) accent.fill else Color.Transparent,
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
                style = WellnessTheme.type.secondary,
                fontWeight = if (cell.isToday) FontWeight(700) else null,
                color = when {
                    cell.isSelected -> accent.ink
                    cell.isToday -> accent.text
                    else -> palette.textPrimary
                },
                modifier = Modifier.clearAndSetSemantics { },
            )
            Box(modifier = Modifier.height(6.dp)) {
                // On a selected day the dot inverts, because the accent fill
                // underneath has already spent the colour it would have used.
                cell.status?.let { StatusDot(it, size = 6.dp, inverted = cell.isSelected) }
            }
        }
    }
}

@Composable
private fun StatusDot(
    status: WorkoutStatus,
    size: androidx.compose.ui.unit.Dp = 8.dp,
    inverted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = if (inverted) WellnessTheme.accent.ink else statusColor(status),
                shape = CircleShape,
            )
            .clearAndSetSemantics { },
    )
}

@Composable
private fun statusColor(status: WorkoutStatus): Color {
    val palette = WellnessTheme.palette
    return when (status) {
        WorkoutStatus.COMPLETED -> palette.success
        WorkoutStatus.MISSED -> palette.error
        // Scheduled is a plan, not a verdict: amber, never red.
        WorkoutStatus.SCHEDULED -> palette.warning
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

/** Android's minimum comfortable tap size; the PWA's 20 px cells miss it. */
internal val MIN_TOUCH_TARGET = 48.dp
