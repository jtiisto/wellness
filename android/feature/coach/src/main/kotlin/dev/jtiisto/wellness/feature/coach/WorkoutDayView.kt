package dev.jtiisto.wellness.feature.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_TITLE
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.RxKind
import dev.jtiisto.wellness.core.data.coach.RxToken
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.AccentColors
import dev.jtiisto.wellness.core.ui.theme.DenseFieldHint
import dev.jtiisto.wellness.core.ui.theme.DenseFieldSkin
import dev.jtiisto.wellness.core.ui.theme.ModuleAccent
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.ui.theme.colors
import dev.jtiisto.wellness.core.ui.theme.columnHeaderStyle

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
        Spacer(Modifier.height(WellnessSpace.lg))
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
        CircularProgressIndicator(
            color = WellnessTheme.accent.fill,
            trackColor = WellnessTheme.palette.line,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Loading…",
            style = WellnessTheme.type.secondary,
            color = WellnessTheme.palette.textSecondary,
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
    val palette = WellnessTheme.palette
    SemanticBanner(
        rail = palette.error,
        icon = Icons.Filled.ErrorOutline,
        text = day.message,
        modifier = Modifier.padding(top = WellnessSpace.lg),
    )
}

/**
 * A band with a semantic rail down its left edge.
 *
 * The band carries the message and the rail carries the meaning, which keeps
 * error out of the surface itself — a whole card tinted red reads as damage
 * rather than as "this one thing did not load".
 */
@Composable
private fun SemanticBanner(
    rail: Color,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = WellnessTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Intrinsic height, because `fillMaxHeight` inside a scrolling
            // column resolves against unbounded constraints and leaves the rail
            // a zero-height sliver.
            .height(IntrinsicSize.Min)
            .clip(WellnessShape.card)
            .background(palette.band)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(rail),
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = WellnessSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = rail,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = WellnessTheme.type.secondary,
                color = palette.textPrimary,
            )
        }
    }
}

// ---- rest day ------------------------------------------------------------------

@Composable
private fun RestDay(day: WorkoutDayState.Rest, actions: CoachActions) {
    val palette = WellnessTheme.palette
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
                tint = palette.textFaint,
            )
            Spacer(Modifier.height(WellnessSpace.sm))
            Text(
                text = "No workout scheduled for this day",
                style = WellnessTheme.type.body,
                color = palette.textSecondary,
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
        is ExtraSessionState.Saved -> CardSurface {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
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
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = WellnessTheme.palette.error,
                        ),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(WellnessSpace.xs))
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
                    shape = WellnessShape.card,
                    colors = WellnessDefaults.accentOutlinedButtonColors(),
                    border = BorderStroke(1.dp, WellnessTheme.accent.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(WellnessSpace.sm))
                    Text("Add Zone 2 session", style = WellnessTheme.type.label)
                }
            } else {
                CardSurface {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
                    ) {
                        ExtraSessionHeader()
                        Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
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
                            horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
                        ) {
                            TextButton(
                                onClick = { draft = null },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = WellnessTheme.palette.textSecondary,
                                ),
                            ) { Text("Delete") }
                            Button(
                                onClick = {
                                    actions.onSaveExtraSession(current)
                                    draft = null
                                },
                                enabled = draftCanSave(current),
                                shape = WellnessShape.card,
                                colors = WellnessDefaults.accentButtonColors(),
                            ) { Text("Save", style = WellnessTheme.type.label) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The content plane: a card surface behind a hairline, at the default radius.
 *
 * `inline`, like the layout primitives it stands in for: a wrapper this thin
 * should not cost a lambda allocation per card, and keeping the caller's body
 * in the caller is also what lets Kover see it as the composable it is.
 */
@Composable
private inline fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = WellnessTheme.palette
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(1.dp, palette.line, WellnessShape.card),
    ) {
        content()
    }
}

@Composable
private fun ExtraSessionHeader() {
    val palette = WellnessTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
        Text(
            text = EXTRA_SESSION_TITLE,
            style = WellnessTheme.type.title,
            color = palette.textPrimary,
        )
        // Off-plan is a fact, not a warning: band and secondary, no amber.
        Text(
            text = "off-plan".uppercase(),
            style = WellnessTheme.type.micro,
            color = palette.textSecondary,
            modifier = Modifier
                .clip(WellnessShape.card)
                .background(palette.band)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
    SemanticBanner(
        // Past and future are both "not now", and neither is a failure.
        rail = WellnessTheme.palette.textFaint,
        icon = when (banner.kind) {
            ReadOnlyBanner.Kind.PAST -> Icons.Filled.Lock
            ReadOnlyBanner.Kind.FUTURE -> Icons.Filled.EventAvailable
        },
        text = banner.text,
    )
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
    val palette = WellnessTheme.palette

    LaunchedEffect(hasControls, day.gateSatisfied, hasAutoExpanded) {
        if (!hasAutoExpanded && hasControls && !day.gateSatisfied) {
            expanded = true
            hasAutoExpanded = true
        }
    }

    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "controls-chevron")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.band)
            .padding(horizontal = 12.dp, vertical = WellnessSpace.sm),
    ) {
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
                .padding(vertical = WellnessSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = day.dayName,
                style = WellnessTheme.type.headline,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (hasControls) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(rotation),
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
                modifier = Modifier.padding(top = WellnessSpace.sm),
                verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
            ) {
                day.controls?.start?.let { HookButton(model = it, actions = actions) }
                day.controls?.end?.let { HookButton(model = it, actions = actions) }
            }
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String) {
    val palette = WellnessTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = WellnessTheme.type.label,
            color = palette.textSecondary,
        )
    }
}

/**
 * One hook button, with its Undo beside it when the state allows one.
 *
 * The button stays enabled while FIRED and simply does nothing — the PWA's
 * behaviour, kept because greying it out next to a live Undo reads as "this
 * workout is finished".
 *
 * Start is the day's one filled action; End outlines, so the two never compete
 * for the same weight.
 */
@Composable
private fun HookButton(model: HookButtonModel, actions: CoachActions) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    val settled = model.state == HookButtonState.FIRED || model.state == HookButtonState.LOCKED
    val failed = model.state == HookButtonState.FAILED
    val outlined = model.action == HookAction.END && !settled && !failed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
    ) {
        if (outlined) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { if (model.canFire) actions.onFireHook(model.action) },
                enabled = model.enabled,
                shape = WellnessShape.card,
                colors = WellnessDefaults.accentOutlinedButtonColors(),
                border = BorderStroke(1.dp, accent.border),
            ) { Text(model.label, style = WellnessTheme.type.label) }
        } else {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { if (model.canFire) actions.onFireHook(model.action) },
                enabled = model.enabled,
                shape = WellnessShape.card,
                colors = when {
                    settled -> WellnessDefaults.semanticButtonColors(palette.success)
                    failed -> WellnessDefaults.semanticButtonColors(palette.error)
                    else -> WellnessDefaults.accentButtonColors()
                },
            ) { Text(model.label, style = WellnessTheme.type.label) }
        }

        if (model.canUndo) {
            TextButton(
                onClick = { actions.onUndoHook(model.action) },
                colors = WellnessDefaults.accentTextButtonColors(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(WellnessSpace.xs))
                Text("Undo")
            }
        }
    }
}

// ---- blocks --------------------------------------------------------------------

@Composable
private fun BlockCard(block: BlockState, editable: Boolean, actions: CoachActions) {
    val palette = WellnessTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Stacked like the PWA's .block-header (a column): title, timing badge,
        // rest guidance — each full-width. Sharing a Row squeezed a long
        // guidance sentence into the leftover width beside a long title.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = block.title, style = WellnessTheme.type.title, color = palette.textPrimary)
            if (block.timing.isNotEmpty()) {
                Text(
                    text = block.timing,
                    style = WellnessTheme.type.label,
                    color = WellnessTheme.accent.text,
                )
            }
            if (block.restGuidance.isNotEmpty()) {
                Text(
                    text = block.restGuidance,
                    style = WellnessTheme.type.label,
                    color = palette.textFaint,
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

/**
 * A superset, marked by a rail rather than a box.
 *
 * Concurrent groups rotate hue exactly as the PWA does — A stays the module's
 * own accent, B borrows sky and C violet — so two groups running side by side
 * are told apart by colour rather than by reading their labels.
 */
@Composable
private fun SupersetGroup(group: BlockItemState.Group, editable: Boolean, actions: CoachActions) {
    val accent = supersetAccent(group.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(accent.wash),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent.fill),
        )
        Column(
            modifier = Modifier.padding(WellnessSpace.sm),
            verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
        ) {
            Text(
                text = group.displayLabel.uppercase(),
                style = WellnessTheme.type.micro,
                color = accent.text,
                modifier = Modifier
                    .clip(WellnessShape.card)
                    .background(accent.chipFill)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            for (exercise in group.exercises) {
                ExerciseAccordion(row = exercise, editable = editable, actions = actions)
            }
        }
    }
}

/** A → the module's accent, B → sky, C → violet. Anything else keeps the module's. */
@Composable
private fun supersetAccent(label: String): AccentColors {
    val palette = WellnessTheme.palette
    return when (label.trim().firstOrNull()?.uppercaseChar()) {
        'B' -> ModuleAccent.TRENDS.colors(palette)
        'C' -> ModuleAccent.ANALYSIS.colors(palette)
        else -> WellnessTheme.accent
    }
}

/** What an accordion body should draw, and whether a finger may reach it. */
internal data class AccordionBody(
    val entry: EntryWidgetState?,
    val interactive: Boolean,
)

/**
 * Resolve the accordion body against the collapse animation.
 *
 * The ViewModel builds `entry` only while a row is expanded, so a collapse
 * would animate an empty box; the composable keeps a copy to shrink. That copy
 * is a *picture of the past*, and the rule this function exists to enforce is
 * that it can never be typed into: during the exit the widgets are rendered
 * disabled, so a tap landing in the closing gap cannot commit a set against
 * values the store has already moved on from.
 *
 * The retained copy is also only ever used on the way *down*. While a row is
 * genuinely expanded, a null entry draws nothing — which is what the pre-5.5
 * code did — rather than leaving a stale grid on screen indefinitely.
 */
internal fun accordionBody(
    expanded: Boolean,
    liveEntry: EntryWidgetState?,
    retainedEntry: EntryWidgetState?,
    editable: Boolean,
): AccordionBody = if (expanded) {
    AccordionBody(entry = liveEntry, interactive = editable && liveEntry != null)
} else {
    AccordionBody(entry = retainedEntry, interactive = false)
}

/**
 * One exercise, header plus expandable body.
 *
 * Expanding brings the body into view: the PWA does not scroll, which on a phone
 * means the keyboard covers the set grid the moment it opens. The request fires
 * when the expansion *finishes* — asking mid-spring scrolls to a rectangle that
 * is still growing, and lands short.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseAccordion(row: ExerciseRowState, editable: Boolean, actions: CoachActions) {
    val palette = WellnessTheme.palette
    val bringIntoView = remember { BringIntoViewRequester() }
    val bodyVisible = remember(row.id) { MutableTransitionState(row.expanded) }
    bodyVisible.targetState = row.expanded

    LaunchedEffect(bodyVisible.currentState, bodyVisible.isIdle) {
        if (bodyVisible.isIdle && bodyVisible.currentState) bringIntoView.bringIntoView()
    }

    // The entry is only built while the accordion is open, so the collapse would
    // otherwise animate an empty box. A copy is kept for the way down and
    // dropped the moment the exit settles — see [accordionBody] for why it is
    // never touchable.
    var retainedEntry by remember(row.id) { mutableStateOf(row.entry) }
    row.entry?.let { retainedEntry = it }
    LaunchedEffect(bodyVisible.isIdle, bodyVisible.currentState) {
        if (bodyVisible.isIdle && !bodyVisible.currentState) retainedEntry = null
    }
    val body = accordionBody(
        expanded = row.expanded,
        liveEntry = row.entry,
        retainedEntry = retainedEntry,
        editable = editable,
    )

    CardSurface(modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        Column {
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
                    style = WellnessTheme.type.title,
                    color = if (row.completed) palette.success else palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                for (pill in row.pills) NeutralPill(text = pill)
                row.exposure?.let { ExposureChip(text = it) }
                if (row.target.isNotEmpty()) {
                    Text(
                        text = row.target,
                        style = WellnessTheme.type.label,
                        color = palette.textSecondary,
                    )
                }
                row.progress?.let { ProgressPill(display = it.display, complete = it.complete) }
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(if (row.expanded) 0f else -90f),
                )
            }

            AnimatedVisibility(
                visibleState = bodyVisible,
                enter = WellnessMotion.expandEnter,
                exit = WellnessMotion.expandExit,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = WellnessSpace.sm),
                    verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
                ) {
                    row.guidanceNote?.let { GuidanceNote(it) }
                    if (row.prescription.isNotEmpty()) PrescriptionRow(row.prescription)

                    when (val entry = body.entry) {
                        is EntryWidgetState.Sets -> SetGrid(
                            exerciseId = row.id,
                            entry = entry,
                            editable = body.interactive,
                            actions = actions,
                        )

                        is EntryWidgetState.Cardio -> CardioFields(
                            durationPlaceholder = entry.durationPlaceholder,
                            durationText = entry.durationText,
                            avgHrText = entry.avgHrText,
                            maxHrText = entry.maxHrText,
                            enabled = body.interactive,
                            onCommit = { field, input -> actions.onCommitCardioField(row.id, field, input) },
                        )

                        is EntryWidgetState.Checklist -> ChecklistItems(
                            items = entry.items,
                            enabled = body.interactive,
                            onToggle = { actions.onToggleChecklistItem(row.id, it) },
                        )

                        null -> Unit
                    }

                    NoteField(
                        value = row.note,
                        label = "${row.name} note",
                        placeholder = if (editable) "Add notes…" else "No notes",
                        enabled = body.interactive,
                        onChange = { actions.onExerciseNote(row.id, it) },
                    )
                }
            }
        }
    }
}

/** Coaching advice, railed in warning amber and set in italic — it is a caution, not a value. */
@Composable
private fun GuidanceNote(text: String) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(WellnessShape.pill)
                .background(palette.warning),
        )
        Text(
            text = text,
            style = WellnessTheme.type.secondary.copy(fontStyle = FontStyle.Italic),
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun NeutralPill(text: String) {
    val palette = WellnessTheme.palette
    Text(
        text = text,
        style = WellnessTheme.type.label,
        color = palette.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.band)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Exposure is taxonomy — the one place the uppercase micro style belongs. */
@Composable
private fun ExposureChip(text: String) {
    val accent = WellnessTheme.accent
    Text(
        text = text.uppercase(),
        style = WellnessTheme.type.micro,
        color = accent.text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.softFill)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProgressPill(display: String, complete: Boolean) {
    val palette = WellnessTheme.palette
    Text(
        text = display,
        style = WellnessTheme.type.label,
        color = if (complete) palette.onSemanticFill else palette.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (complete) palette.success else palette.band)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = "Progress: $display" },
    )
}

/** RPE · load · tempo. Load is self-describing, so it gets an icon, not a word. */
@Composable
private fun PrescriptionRow(tokens: List<RxToken>) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(text = "·", color = palette.textFaint)
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
                        tint = accent.text,
                    )
                    Text(
                        text = token.value,
                        style = WellnessTheme.type.label,
                        color = palette.textPrimary,
                    )
                }

                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = if (token.kind == RxKind.RPE) "RPE" else "TEMPO",
                        style = WellnessTheme.type.micro,
                        color = accent.text,
                    )
                    Text(
                        text = token.value,
                        style = WellnessTheme.type.label,
                        color = palette.textPrimary,
                    )
                }
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
    val palette = WellnessTheme.palette
    val headerStyle = columnHeaderStyle()
    // No spacing between rows: each one already reserves 48dp around a 40dp
    // box, so the touch targets themselves leave an 8dp gutter. Adding more
    // would open the grid back up, which is the thing this pass closed.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = WellnessSpace.xs),
        ) {
            Text(
                text = "#",
                style = headerStyle,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(SET_NUMBER_COLUMN),
            )
            for (column in entry.columns) {
                Text(
                    text = columnLabel(column.label, column.unit, palette.textFaint),
                    style = headerStyle,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "✓",
                style = headerStyle,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(MIN_TOUCH_TARGET)
                    .semantics { contentDescription = "Done" },
            )
        }
        // The rule the PWA draws under its column heads. With it there the
        // heads no longer have to carry the separation themselves, which is
        // what let them drop back to a quiet micro.
        HorizontalDivider(color = palette.line)

        for (row in entry.rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (row.index + 1).toString(),
                    style = WellnessTheme.type.secondary,
                    color = palette.textFaint,
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
                        colors = WellnessDefaults.checkboxColors(),
                        modifier = Modifier.semantics {
                            contentDescription = "Set ${row.index + 1} done"
                        },
                    )
                }
            }
        }

        entry.provenance?.let {
            Text(
                text = it.label,
                style = WellnessTheme.type.label,
                color = palette.textFaint,
                modifier = Modifier.padding(top = WellnessSpace.xs),
            )
        }
    }
}

/** "WEIGHT (LBS)" with the unit dropped back to faint — the label is the signal. */
private fun columnLabel(label: String, unit: String?, unitColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(label.uppercase())
        if (unit != null) {
            withStyle(SpanStyle(color = unitColor)) { append(" (${unit.uppercase()})") }
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
    Row(horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
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
    val palette = WellnessTheme.palette
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
                    colors = WellnessDefaults.checkboxColors(),
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    text = item.item,
                    style = WellnessTheme.type.secondary,
                    color = palette.textPrimary,
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
    val palette = WellnessTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
        HorizontalDivider(color = palette.line)
        Text(
            text = "Session Feedback",
            style = WellnessTheme.type.title,
            color = palette.textPrimary,
        )
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

    WellnessDenseField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        // A grid of outlines is what made the old grid heavy; the columns
        // already say these are boxes.
        skin = DenseFieldSkin.FILLED,
        numeric = true,
        placeholder = placeholder,
        // A ghost is last session's number, not this one's: italic and
        // half-there until you make it yours.
        hint = DenseFieldHint.GHOST,
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

    WellnessDenseField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        enabled = enabled,
        skin = DenseFieldSkin.FILLED,
        multiLine = true,
        label = label.takeIf { showLabel },
        placeholder = placeholder,
        hint = DenseFieldHint.PROMPT,
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

/** The set-number gutter, narrow enough to leave the value columns usable. */
private val SET_NUMBER_COLUMN = 28.dp
