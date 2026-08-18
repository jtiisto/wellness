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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_TITLE
import dev.jtiisto.wellness.core.data.coach.RxKind
import dev.jtiisto.wellness.core.data.coach.RxToken
import dev.jtiisto.wellness.core.data.coach.SetColumn
import dev.jtiisto.wellness.core.data.coach.TallyMarks
import dev.jtiisto.wellness.core.ui.motion.WellnessMotion
import dev.jtiisto.wellness.core.ui.theme.DenseFieldSkin
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField

/**
 * The selected day's workout, on paper.
 *
 * A scrolling [Column] rather than a lazy list: a day is a handful of blocks,
 * and the accordions inside need a real scroll container to bring themselves
 * into view when the keyboard appears.
 *
 * Everything below draws Logbook — one surface, whitespace and hairlines for
 * grouping, colour spent only on the tier dots. Nothing here decides anything:
 * the eyebrow, the plates, the legend, the tally counts and the provenance
 * wording all arrive already derived, and the few remaining presentation choices
 * (which glyph, which empty-note wording, filled or outlined) live in
 * `CoachNotation` where a JVM test can hold them.
 */
@Composable
fun WorkoutDayView(day: WorkoutDayState, actions: CoachActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = SCREEN_PADDING),
    ) {
        Spacer(Modifier.height(SCREEN_TOP))
        when (day) {
            WorkoutDayState.Loading -> LoadingDay()
            is WorkoutDayState.PlanUnavailable -> UnreadablePlanDay(day)
            is WorkoutDayState.Rest -> RestDay(day = day, actions = actions)
            is WorkoutDayState.Planned -> PlannedDay(day = day, actions = actions)
        }
        Spacer(Modifier.height(SCREEN_BOTTOM))
    }
}

// ---- the two "nothing to show yet" days --------------------------------------------

/** Storage has not answered yet. Deliberately not the rest-day empty state. */
@Composable
private fun LoadingDay() {
    val palette = LogbookTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EMPTY_DAY_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = palette.ink,
            trackColor = palette.rule,
            modifier = Modifier.size(SPINNER_SIZE),
        )
        Spacer(Modifier.height(LogbookSpace.grid * 3))
        EmptyStateText("Loading…")
    }
}

/**
 * A plan is stored for this day but will not decode.
 *
 * No entry, and no ad-hoc session either: offering one here would file an
 * off-plan Zone 2 against a day that already has a workout on it.
 *
 * Drawn as marginalia with an ink glyph rather than as a railed band: Logbook
 * has no error colour to rail it in, and a note in the margin is what this is —
 * the page explaining itself, not the page reporting damage.
 */
@Composable
private fun UnreadablePlanDay(day: WorkoutDayState.PlanUnavailable) {
    Marginalia(
        text = day.message,
        glyph = Icons.Outlined.ErrorOutline,
        modifier = Modifier.padding(top = LogbookSpace.group),
    )
}

/**
 * An execution note: the programme talking to the user.
 *
 * Italic prose behind a 2dp `ruleStrong` rail, identical at every level it
 * attaches to (section, group, exercise) because it means the same thing at all
 * of them. The user's own notes are the opposite direction and never look like
 * this — see [NoteBlock].
 */
@Composable
private fun Marginalia(text: String, modifier: Modifier = Modifier, glyph: ImageVector? = null) {
    val palette = LogbookTheme.palette
    Row(
        modifier = modifier
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
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 1.5f),
    ) {
        glyph?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier
                    .padding(top = MARGINALIA_GLYPH_LIFT)
                    .size(GLYPH_SIZE),
            )
        }
        Text(
            text = text,
            style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
            color = palette.inkSoft,
        )
    }
}

/** Whatever is not there yet, said in the italic ink-faint voice reserved for absence. */
@Composable
private fun EmptyStateText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
        color = LogbookTheme.palette.inkFaint,
        modifier = modifier,
    )
}

// ---- rest day ------------------------------------------------------------------

@Composable
private fun RestDay(day: WorkoutDayState.Rest, actions: CoachActions) {
    if (day.showEmptyState) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = EMPTY_DAY_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            // The calendar glyph retires with the rest of the decorative set:
            // the sentence says it, and an icon above it says it twice.
            EmptyStateText("No workout scheduled for this day")
        }
    }
    day.extra?.let { ExtraSessionSection(state = it, actions = actions) }
}

/**
 * The ad-hoc Zone 2 session.
 *
 * The draft lives here and nowhere else: until Save it is not in the log store,
 * so nothing syncs and nothing is dirty. Discarding it is a local act, which is
 * why draft-Delete needs no confirmation — and neither does the saved one, whose
 * delete is a recoverable tombstone.
 */
@Composable
private fun ExtraSessionSection(state: ExtraSessionState, actions: CoachActions) {
    var draft by remember { mutableStateOf<ExtraSessionDraft?>(null) }

    when (state) {
        is ExtraSessionState.Saved -> Section {
            ExtraSessionHead()
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
                    colors = ButtonDefaults.textButtonColors(contentColor = LogbookTheme.palette.ink),
                    modifier = Modifier.align(Alignment.End),
                ) { Text("DELETE SESSION", style = LogbookTheme.type.eyebrow) }
            }
        }

        ExtraSessionState.Idle -> {
            val current = draft
            if (current == null) {
                Spacer(Modifier.height(LogbookSpace.group))
                InkOutlineButton(
                    label = "Add Zone 2 session",
                    onClick = { draft = ExtraSessionDraft() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Section {
                    ExtraSessionHead()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
                        // Same rule as CardioFields: the boxes share a line,
                        // labels float above at their own heights.
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        LabelledField(label = "Duration (min)", modifier = Modifier.weight(1f)) {
                            NumericField(
                                value = current.durationMin,
                                label = "Duration (min)",
                                placeholder = "min",
                                enabled = true,
                                onCommit = { draft = current.copy(durationMin = it) },
                            )
                        }
                        LabelledField(label = "Avg HR", modifier = Modifier.weight(1f)) {
                            NumericField(
                                value = current.avgHr,
                                label = "Avg HR",
                                placeholder = "bpm",
                                enabled = true,
                                onCommit = { draft = current.copy(avgHr = it) },
                            )
                        }
                        LabelledField(label = "Max HR", modifier = Modifier.weight(1f)) {
                            NumericField(
                                value = current.maxHr,
                                label = "Max HR",
                                placeholder = "bpm",
                                enabled = true,
                                onCommit = { draft = current.copy(maxHr = it) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = LogbookSpace.grid * 2),
                        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
                    ) {
                        TextButton(
                            onClick = { draft = null },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = LogbookTheme.palette.inkSoft,
                            ),
                        ) { Text("DELETE", style = LogbookTheme.type.eyebrow) }
                        InkButton(
                            label = "Save",
                            enabled = draftCanSave(current),
                            onClick = {
                                actions.onSaveExtraSession(current)
                                draft = null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A flat, rule-bounded block of the page.
 *
 * The card retires with the fills and the borders that made it one: what is left
 * is air above, a `ruleStrong` boundary across the top, and air below. `inline`
 * for the same reason `CardSurface` was — a wrapper this thin should not cost a
 * lambda allocation, and keeping the caller's body in the caller is what lets
 * Kover see it as the composable it is.
 */
@Composable
private inline fun Section(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LogbookTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = LogbookSpace.group)
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawLine(
                    color = palette.ruleStrong,
                    start = Offset(0f, stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(top = LogbookSpace.grid * 3),
        content = content,
    )
}

/** The off-plan session names itself, and says so in the eyebrow dialect. */
@Composable
private fun ExtraSessionHead() {
    val palette = LogbookTheme.palette
    Text(
        text = "off-plan".uppercase(),
        style = LogbookTheme.type.eyebrow,
        color = palette.inkSoft,
    )
    Spacer(Modifier.height(LogbookSpace.grid))
    SectionHead(label = EXTRA_SESSION_TITLE, hint = "")
}

// ---- planned day ---------------------------------------------------------------

@Composable
private fun PlannedDay(day: WorkoutDayState.Planned, actions: CoachActions) {
    WorkoutHeader(day = day, actions = actions)

    // One voice for the whole day, resolved once: every empty note on screen is
    // answering the same question about the same session.
    val voice = noteVoice(day.eyebrow, day.editable)

    for (block in day.blocks) {
        BlockSection(block = block, editable = day.editable, voice = voice, actions = actions)
    }

    SessionFeedbackSection(state = day.feedback, voice = voice, onFeedback = actions.onFeedback)
}

/**
 * The day's eyebrow, title, meta and legend — and the collapsible hook controls.
 *
 * The lifecycle is the eyebrow's whole job now: the read-only bands are gone,
 * and so is every semantic colour they carried.
 *
 * The header opens its controls once per session while the gate is still shut,
 * so the user can see *why* entry is locked. After that the toggle is theirs: a
 * manual collapse is not undone by the next recomposition.
 */
@Composable
private fun WorkoutHeader(day: WorkoutDayState.Planned, actions: CoachActions) {
    var expanded by remember(day.sessionId) { mutableStateOf(false) }
    var hasAutoExpanded by remember(day.sessionId) { mutableStateOf(false) }
    val hasControls = day.controls != null
    val palette = LogbookTheme.palette

    LaunchedEffect(hasControls, day.gateSatisfied, hasAutoExpanded) {
        if (!hasAutoExpanded && hasControls && !day.gateSatisfied) {
            expanded = true
            hasAutoExpanded = true
        }
    }

    val rotation by animateFloatAsState(if (expanded) 0f else -QUARTER_TURN, label = "controls-chevron")

    Column(modifier = Modifier.fillMaxWidth()) {
        EyebrowLine(day.eyebrow)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasControls) {
                        Modifier.clickableRow(
                            // The title rides along: a merged clickable replaces
                            // its children's semantics, so without it the day
                            // name is never announced at all.
                            label = if (expanded) {
                                "${day.dayName}, hide workout controls"
                            } else {
                                "${day.dayName}, show workout controls"
                            },
                            onClick = { expanded = !expanded },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(top = TITLE_TOP, bottom = TITLE_BOTTOM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = day.dayName.uppercase(),
                style = LogbookTheme.type.display,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            if (hasControls) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = palette.inkSoft,
                    modifier = Modifier
                        .size(GLYPH_SIZE)
                        .rotate(rotation),
                )
            }
        }

        MetaLine(location = day.location, phase = day.phase)

        if (day.legend.isNotEmpty()) {
            LegendRow(legend = day.legend, modifier = Modifier.padding(top = LEGEND_TOP))
        }

        AnimatedVisibility(visible = expanded && hasControls) {
            // One full-width row per hook: sharing a single Row pushed the End
            // button's Undo off-screen once labels grew ("(locked)", "Working…").
            Column(
                modifier = Modifier.padding(top = LogbookSpace.grid * 3),
                verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
            ) {
                day.controls?.start?.let { HookButton(model = it, actions = actions) }
                day.controls?.end?.let { HookButton(model = it, actions = actions) }
            }
        }
    }
}

/** Mono caps in ink-soft behind an ink glyph — never a colour emoji. */
@Composable
private fun EyebrowLine(eyebrow: WorkoutEyebrow) {
    val palette = LogbookTheme.palette
    Row(
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (eyebrow.glyph()) {
            EyebrowGlyph.LOCK -> Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier.size(EYEBROW_GLYPH),
            )

            EyebrowGlyph.CALENDAR -> Icon(
                imageVector = Icons.Outlined.CalendarToday,
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier.size(EYEBROW_GLYPH),
            )

            // Under way is the one state with no icon for it: a filled dot is
            // the notation for "this is live", the same mark the tally uses.
            EyebrowGlyph.DOT -> Box(
                modifier = Modifier
                    .size(EYEBROW_DOT)
                    .clip(CircleShape)
                    .background(palette.ink),
            )
        }
        Text(
            text = eyebrow.label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
    }
}

/** Where and which phase, as words — the location pin and the bar chart retire. */
@Composable
private fun MetaLine(location: String?, phase: String?) {
    if (location == null && phase == null) return
    val palette = LogbookTheme.palette
    Text(
        text = buildAnnotatedString {
            location?.let {
                withStyle(SpanStyle(color = palette.ink, fontWeight = FontWeight.Medium)) { append(it) }
            }
            if (location != null && phase != null) {
                withStyle(SpanStyle(color = palette.inkFaint)) { append(META_SEPARATOR) }
            }
            phase?.let { append(it) }
        },
        style = LogbookTheme.type.body,
        color = palette.inkSoft,
    )
}

/**
 * The tier legend — load-bearing, not decoration.
 *
 * Plate assignment is positional, so the same word is red in one workout and
 * blue in the next; a dot without this row underneath says nothing at all. It
 * flows rather than scrolls, because a legend that runs off the edge of the page
 * is the same as no legend.
 */
@Composable
private fun LegendRow(legend: List<TierLegendEntry>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 4),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        for (entry in legend) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 1.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(PLATE_DOT)
                        .clip(CircleShape)
                        .background(plateColor(entry.slot)),
                )
                Text(
                    // Uppercased here and nowhere else: the string is the plan's
                    // and is rendered verbatim everywhere it is not a legend.
                    text = entry.exposure.uppercase(),
                    style = LogbookTheme.type.eyebrow,
                    color = LogbookTheme.palette.inkSoft,
                )
            }
        }
    }
}

/** A plate index against the palette; anything past it is the ink fallback. */
@Composable
private fun plateColor(slot: PlateSlot): Color {
    val palette = LogbookTheme.palette
    return when (slot) {
        // `getOrElse` rather than an index: the assignment and the palette are
        // pinned to the same length by test, and a drawing routine is the wrong
        // place to find out they stopped agreeing.
        is PlateSlot.Plate -> palette.plates.getOrElse(slot.index) { palette.ink }
        PlateSlot.Ink -> palette.ink
    }
}

// ---- blocks --------------------------------------------------------------------

/**
 * A block, drawn as a section: display-caps label over a 1.5dp ink underline,
 * with its timing and rest guidance as the one mono hint on the same baseline.
 */
@Composable
private fun BlockSection(
    block: BlockState,
    editable: Boolean,
    voice: NoteVoice,
    actions: CoachActions,
) {
    Column(modifier = Modifier.padding(top = SECTION_GAP)) {
        // Timing is the one baseline hint — it is short by construction
        // (formatInterval). Rest guidance is free coaching prose that can run
        // to sentences, and prose cannot share a baseline with the label: it
        // reads as a section marginalia below the head, the mockups' own
        // pattern for section-level instruction.
        SectionHead(label = block.title, hint = block.timing)
        if (block.restGuidance.isNotBlank()) {
            Marginalia(
                text = block.restGuidance,
                modifier = Modifier.padding(top = LogbookSpace.grid * 2),
            )
        }

        for (item in block.items) {
            when (item) {
                is BlockItemState.Single -> ExerciseAccordion(
                    row = item.exercise,
                    editable = editable,
                    voice = voice,
                    actions = actions,
                    gutter = true,
                )

                is BlockItemState.Group -> SupersetBracket {
                    for (exercise in item.exercises) {
                        ExerciseAccordion(
                            row = exercise,
                            editable = editable,
                            voice = voice,
                            actions = actions,
                            // The bracket already owns the gutter it is drawn
                            // in; indenting the rows again would double it.
                            gutter = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(label: String, hint: String) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
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
    ) {
        // Measurement order is the layout rule here: the label is unweighted so
        // it measures FIRST at its own width and can never be crushed into a
        // letter column; the weighted hint takes whatever remains and wraps on
        // the right if it must. The inverse arrangement hands a long hint the
        // whole row first — one letter of label per line.
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.section,
            color = palette.ink,
            modifier = Modifier.alignByBaseline(),
        )
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = LogbookSpace.grid * 2)
                    .alignByBaseline(),
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * A superset, drawn rather than named.
 *
 * A 1.5dp ink stroke in the gutter with a 6dp tick at each end — a square
 * bracket, inset from the top and bottom rows so it brackets the group rather
 * than boxing it. It spans whatever the group contains, expanded bodies
 * included, because it is drawn behind the whole column.
 *
 * The accent rail, the 6% wash, the label chip and the A/B/C hue rotation all
 * retire with it: `supersetDisplayLabel` is still ported and still tested, and
 * nothing renders it any more.
 */
@Composable
private inline fun SupersetBracket(content: @Composable ColumnScope.() -> Unit) {
    val palette = LogbookTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = BRACKET_STROKE.toPx()
                val tick = BRACKET_TICK.toPx()
                val inset = LogbookSpace.row.toPx()
                val top = inset
                val bottom = (size.height - inset).coerceAtLeast(inset)
                drawLine(
                    color = palette.ink,
                    start = Offset(stroke / 2f, top),
                    end = Offset(stroke / 2f, bottom),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, top + stroke / 2f),
                    end = Offset(tick, top + stroke / 2f),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = palette.ink,
                    start = Offset(0f, bottom - stroke / 2f),
                    end = Offset(tick, bottom - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(start = GUTTER),
        content = content,
    )
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
 * One exercise: a row of the log, and everything behind it.
 *
 * Collapsed it is one line — dot, name, scheme, tally — because the design's
 * sixth principle allows exactly one metadata cluster per row. Everything else
 * is in the expanded body.
 *
 * Expanding brings the body into view: the PWA does not scroll, which on a phone
 * means the keyboard covers the set grid the moment it opens. The request fires
 * when the expansion *finishes* — asking mid-spring scrolls to a rectangle that
 * is still growing, and lands short.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseAccordion(
    row: ExerciseRowState,
    editable: Boolean,
    voice: NoteVoice,
    actions: CoachActions,
    gutter: Boolean,
) {
    val palette = LogbookTheme.palette
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            // The hairline runs the full width of the row, gutter included —
            // a group's rows inherit the bracket's inset instead, which is how
            // the drawn bracket reads as owning them.
            .hairlineBelow(palette.rule)
            .padding(start = if (gutter) GUTTER else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRow(
                    // The row is one merged node, so everything drawn on it has
                    // to be in this sentence — the tally above all, since marks
                    // are geometry to a screen reader.
                    label = exerciseRowDescription(
                        name = row.name,
                        exposure = row.exposure,
                        scheme = row.target,
                        tally = row.tally,
                        expanded = row.expanded,
                    ),
                    onClick = { actions.onToggleExercise(row.id) },
                )
                // Height before padding: the other order asks for 48dp *plus*
                // 28dp of padding and gives every row of the log a 76dp bed.
                .heightIn(min = MIN_TOUCH_TARGET)
                .padding(vertical = LogbookSpace.row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExerciseNameText(
                row = row,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline(),
            )
            Row(
                modifier = Modifier
                    .padding(start = LogbookSpace.grid * 2.5f)
                    .alignByBaseline(),
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.target.isNotEmpty()) {
                    Text(
                        text = row.target,
                        style = LogbookTheme.type.data,
                        color = palette.inkSoft,
                    )
                }
                row.tally?.let { Tally(it) }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = palette.inkFaint,
                modifier = Modifier
                    .padding(start = LogbookSpace.grid)
                    .size(GLYPH_SIZE)
                    .rotate(if (row.expanded) 0f else -QUARTER_TURN),
            )
        }

        AnimatedVisibility(
            visibleState = bodyVisible,
            enter = WellnessMotion.expandEnter,
            exit = WellnessMotion.expandExit,
        ) {
            Column(modifier = Modifier.padding(bottom = LogbookSpace.grid * 3)) {
                if (row.prescription.isNotEmpty()) {
                    PrescriptionMeta(row.prescription)
                }

                when (val entry = body.entry) {
                    is EntryWidgetState.Sets -> SetTable(
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

                row.guidanceNote?.let {
                    Marginalia(text = it, modifier = Modifier.padding(top = LogbookSpace.grid * 3))
                }

                NoteBlock(
                    label = "Your notes",
                    value = row.note,
                    voice = voice,
                    scope = NoteScope.EXERCISE,
                    accessibilityLabel = "${row.name} note",
                    enabled = body.interactive,
                    onChange = { actions.onExerciseNote(row.id, it) },
                )
            }
        }
    }
}

/**
 * The exercise's name with its tier dot set into it.
 *
 * Inline content rather than a `Row`, so a dot belongs to the text: a two-line
 * name keeps its dot against the first word instead of floating beside a column
 * of wrapped prose. The parsed-name pills follow as plain ink-soft text — the
 * chips they used to be were a second surface, and there is only one.
 */
@Composable
private fun ExerciseNameText(row: ExerciseRowState, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    val dotColor = row.plate?.let { plateColor(it) }
    val density = LocalDensity.current

    val text = buildAnnotatedString {
        if (dotColor != null) appendInlineContent(PLATE_DOT_SLOT, "●")
        append(row.name)
        for (pill in row.pills) {
            withStyle(SpanStyle(color = palette.inkSoft)) { append("  $pill") }
        }
    }

    val inlineContent = remember(dotColor, density) {
        if (dotColor == null) {
            emptyMap()
        } else {
            // TextUnit carries no `plus` — sp and em are not addable — so the
            // span's width is summed in Dp and converted once.
            val dot = with(density) { PLATE_DOT.toSp() }
            val span = with(density) { (PLATE_DOT + PLATE_DOT_GAP).toSp() }
            mapOf(
                PLATE_DOT_SLOT to InlineTextContent(
                    Placeholder(
                        width = span,
                        height = dot,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Box(
                            modifier = Modifier
                                .size(PLATE_DOT)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    }
                },
            )
        }
    }

    Text(
        text = text,
        style = LogbookTheme.type.name,
        // Completion is the tally's job now; a green name said it twice and said
        // it in a colour the system does not own.
        color = palette.ink,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}

/** One mark per unit of work: inked when done, outlined when not. */
@Composable
private fun Tally(tally: TallyMarks) {
    val palette = LogbookTheme.palette
    Row(horizontalArrangement = Arrangement.spacedBy(TALLY_GAP)) {
        for (filled in tallyMarkFills(tally)) {
            Box(
                modifier = Modifier
                    .size(width = TALLY_WIDTH, height = TALLY_HEIGHT)
                    .then(
                        if (filled) {
                            Modifier
                                .clip(MARK_SHAPE)
                                .background(palette.ink)
                        } else {
                            Modifier.border(LogbookSpace.hairline, palette.inkFaint, MARK_SHAPE)
                        },
                    ),
            )
        }
    }
}

/**
 * RPE · load · tempo, in mono: labels ink-soft at 400, values ink at 500.
 *
 * The load slot holds free coaching text, so it is introduced by the word `load`
 * whenever it does not begin with a number — see [loadNeedsLabel]. The dumbbell
 * icon retires with the rest of the decorative set.
 */
@Composable
private fun PrescriptionMeta(tokens: List<RxToken>) {
    val palette = LogbookTheme.palette
    val value = SpanStyle(color = palette.ink, fontWeight = FontWeight.Medium)
    Text(
        text = buildAnnotatedString {
            tokens.forEachIndexed { index, token ->
                if (index > 0) withStyle(SpanStyle(color = palette.inkFaint)) { append(META_SEPARATOR) }
                when (token.kind) {
                    RxKind.RPE -> {
                        append("target ")
                        withStyle(value) { append("RPE ${token.value}") }
                    }

                    RxKind.LOAD -> {
                        if (loadNeedsLabel(token.value)) append("load ")
                        withStyle(value) { append(token.value) }
                    }

                    RxKind.TEMPO -> {
                        append("tempo ")
                        withStyle(value) { append(token.value) }
                    }
                }
            }
        },
        style = LogbookTheme.type.meta,
        color = palette.inkSoft,
        modifier = Modifier.padding(bottom = LogbookSpace.grid * 2.5f),
    )
}

// ---- entry widgets ----------------------------------------------------------------

/**
 * The set table: a header row and exactly `target_sets` rows under it.
 *
 * Bare mono cells on paper — no fills, no zebra, hairline row rules only. Ghost
 * values from the last matching session sit in the cells as placeholders, so an
 * untouched row still logs nothing at all; they are drawn in ink-faint and turn
 * to ink the moment a value of the row's own arrives. The structure never
 * changes between the two, which is the whole point of the rule.
 *
 * A read-only day draws plain text rather than disabled fields: with nothing to
 * touch there is no target to reserve, and a past workout reads as the compact
 * table it is.
 */
@Composable
private fun SetTable(
    exerciseId: String,
    entry: EntryWidgetState.Sets,
    editable: Boolean,
    actions: CoachActions,
) {
    val palette = LogbookTheme.palette
    val markColumn = if (editable) MIN_TOUCH_TARGET else MARK_COLUMN
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hairlineBelow(palette.rule)
                .padding(bottom = TABLE_HEAD_GAP),
        ) {
            Text(
                text = "Set".uppercase(),
                style = LogbookTheme.type.tableHeader,
                color = palette.inkFaint,
                // Spoken in its natural case: TalkBack spells out a three-letter
                // capital as letters, and this one is a word.
                modifier = Modifier
                    .width(SET_NUMBER_COLUMN)
                    .semantics { contentDescription = "Set" },
            )
            for (column in entry.columns) {
                Text(
                    text = columnLabel(column),
                    style = LogbookTheme.type.tableHeader,
                    color = palette.inkFaint,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            // The tick column's head is deliberately blank: the marks under it
            // say what they are, and a "✓" over them is the word for the mark.
            Box(modifier = Modifier.width(markColumn))
        }

        entry.rows.forEachIndexed { position, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // The mockup drops the last row's rule; the provenance
                        // footer or the marginalia below is the boundary there.
                        if (position == entry.rows.lastIndex) Modifier else Modifier.hairlineBelow(palette.rule),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (row.index + 1).toString(),
                    style = LogbookTheme.type.data,
                    // Set numbers are structure, not data: they stay ink whether
                    // the row has been logged or not.
                    color = palette.ink,
                    modifier = Modifier
                        .width(SET_NUMBER_COLUMN)
                        .padding(vertical = TABLE_CELL_PADDING),
                )
                for (cell in row.cells) {
                    if (editable) {
                        NumericField(
                            value = cell.text,
                            label = "Set ${row.index + 1} ${cell.key}",
                            placeholder = cell.ghost,
                            enabled = true,
                            onCommit = { actions.onCommitSetCell(exerciseId, row.index, cell.key, it) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val shown = cell.text.ifEmpty { cell.ghost.orEmpty() }
                        val spoken = setCellDescription(
                            setNumber = row.index + 1,
                            key = cell.key,
                            value = shown,
                            isGhost = cell.showsGhost,
                        )
                        Text(
                            text = shown,
                            style = LogbookTheme.type.data,
                            color = if (cell.showsGhost) palette.inkFaint else palette.ink,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = TABLE_CELL_PADDING)
                                .semantics { contentDescription = spoken },
                        )
                    }
                }
                SetMark(
                    checked = row.completed,
                    editable = editable,
                    description = "Set ${row.index + 1} done",
                    width = markColumn,
                    onCheckedChange = { actions.onSetCompleted(exerciseId, row.index, it) },
                )
            }
        }

        entry.provenance?.let {
            Text(
                // Verbatim, unlike the eyebrow and the legend: this sentence is
                // already written the way it should read.
                text = it.label,
                style = LogbookTheme.type.meta,
                color = palette.inkFaint,
                modifier = Modifier.padding(top = LogbookSpace.grid * 2.5f),
            )
        }
    }
}

/** "WEIGHT (LBS)" — the unit rides along in the head so the cells stay bare. */
private fun columnLabel(column: SetColumn): String =
    if (column.unit == null) column.label.uppercase() else "${column.label} (${column.unit})".uppercase()

/**
 * The set-completion mark: an ink square, filled once the set is logged.
 *
 * Replaces the M3 `Checkbox` — a tick in a rounded box is Material's notation,
 * not the log's — while keeping the target it stood in and the write path behind
 * it. On a read-only day it is drawn and nothing more.
 */
@Composable
private fun SetMark(
    checked: Boolean,
    editable: Boolean,
    description: String,
    width: Dp,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = if (editable) {
            Modifier
                .size(width)
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .semantics { contentDescription = description }
        } else {
            // A read-only mark is not toggleable, but its state still has to be
            // heard: the disabled Checkbox this replaced announced checked and
            // unchecked, and "done, done" versus "done" does not.
            Modifier
                .width(width)
                .semantics {
                    contentDescription = description
                    stateDescription = if (checked) "Done" else "Not done"
                }
        },
        contentAlignment = Alignment.Center,
    ) {
        MarkBox(checked)
    }
}

/** The mark itself: filled ink when done, an ink-faint outline when not. */
@Composable
private fun MarkBox(checked: Boolean) {
    val palette = LogbookTheme.palette
    Box(
        modifier = Modifier
            .size(MARK_SIZE)
            .then(
                if (checked) {
                    Modifier
                        .clip(MARK_SHAPE)
                        .background(palette.ink)
                } else {
                    Modifier.border(LogbookSpace.hairline, palette.inkFaint, MARK_SHAPE)
                },
            ),
    )
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
        // Bottom-aligned so the three input boxes share one line: top-aligned,
        // a label that wraps ("Duration (min)" at a third of the screen) pushes
        // only its own field down and the row reads as three different rows.
        verticalAlignment = Alignment.Bottom,
    ) {
        LabelledField(label = "Duration (min)", modifier = Modifier.weight(1f)) {
            NumericField(
                value = durationText,
                label = "Duration (min)",
                placeholder = durationPlaceholder.ifEmpty { "min" },
                enabled = enabled,
                onCommit = { onCommit("duration_min", it) },
            )
        }
        LabelledField(label = "Avg HR", modifier = Modifier.weight(1f)) {
            NumericField(
                value = avgHrText,
                label = "Avg HR",
                placeholder = "bpm",
                enabled = enabled,
                onCommit = { onCommit("avg_hr", it) },
            )
        }
        LabelledField(label = "Max HR", modifier = Modifier.weight(1f)) {
            NumericField(
                value = maxHrText,
                label = "Max HR",
                placeholder = "bpm",
                enabled = enabled,
                onCommit = { onCommit("max_hr", it) },
            )
        }
    }
}

/** A field under its mono-caps name — the naked skin draws no label of its own. */
@Composable
private inline fun LabelledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = LogbookTheme.palette.inkSoft,
        )
        content()
    }
}

@Composable
private fun ChecklistItems(items: List<ChecklistItemState>, enabled: Boolean, onToggle: (String) -> Unit) {
    val palette = LogbookTheme.palette
    Column {
        for (item in items) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (enabled) {
                            Modifier.toggleable(
                                value = item.checked,
                                role = Role.Checkbox,
                                onValueChange = { onToggle(item.item) },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .semantics {
                        contentDescription = if (item.checked) "${item.item}, done" else item.item
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(MIN_TOUCH_TARGET),
                    contentAlignment = Alignment.Center,
                ) { MarkBox(item.checked) }
                Text(
                    text = item.item,
                    style = LogbookTheme.type.body,
                    color = if (item.checked) palette.inkSoft else palette.ink,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun SessionFeedbackSection(
    state: SessionFeedbackState,
    voice: NoteVoice,
    onFeedback: (String, String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SECTION_GAP)) {
        SectionHead(label = "Session feedback", hint = "")
        NoteBlock(
            label = "Pain / discomfort",
            value = state.painDiscomfort,
            voice = voice,
            scope = NoteScope.SESSION,
            accessibilityLabel = "Pain / Discomfort",
            enabled = state.editable,
            onChange = { onFeedback("pain_discomfort", it) },
        )
        NoteBlock(
            label = "General notes",
            value = state.generalNotes,
            voice = voice,
            scope = NoteScope.SESSION,
            accessibilityLabel = "General Notes",
            enabled = state.editable,
            onChange = { onFeedback("general_notes", it) },
        )
    }
}

/**
 * A note the user writes: mono-caps label over roman ink prose.
 *
 * Never italic and never railed — that treatment belongs to the programme's own
 * notes, and the two directions must not look alike. Empty is the one italic
 * case, and what it says depends on whether the session can still be written to
 * (see [NoteVoice]).
 */
@Composable
private fun NoteBlock(
    label: String,
    value: String,
    voice: NoteVoice,
    scope: NoteScope,
    accessibilityLabel: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = LogbookSpace.grid * 3.5f)) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = LogbookTheme.palette.inkSoft,
        )
        Spacer(Modifier.height(LogbookSpace.grid))
        if (enabled) {
            NoteField(
                value = value,
                label = accessibilityLabel,
                placeholder = voice.emptyNote(scope),
                onChange = onChange,
            )
        } else if (value.isEmpty()) {
            EmptyStateText(voice.emptyNote(scope))
        } else {
            Text(
                text = value,
                style = LogbookTheme.type.body,
                color = LogbookTheme.palette.ink,
            )
        }
    }
}

// ---- controls -------------------------------------------------------------------------

/**
 * One hook button, with its Undo beside it when the state allows one.
 *
 * The button stays enabled while FIRED and simply does nothing — the PWA's
 * behaviour, kept because greying it out next to a live Undo reads as "this
 * workout is finished".
 *
 * Fill and glyph come from [hookButtonSkin]; the label is the state's own and is
 * only uppercased here. No semantic colour is spent at any point: a settled hook
 * ticks, a failed one crosses and says `FAILED`.
 */
@Composable
private fun HookButton(model: HookButtonModel, actions: CoachActions) {
    val palette = LogbookTheme.palette
    val skin = hookButtonSkin(model.action, model.state)
    val onClick = { if (model.canFire) actions.onFireHook(model.action) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
    ) {
        if (skin.filled) {
            InkButton(
                label = model.label,
                enabled = model.enabled,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                skin = skin,
            )
        } else {
            InkOutlineButton(
                label = model.label,
                enabled = model.enabled,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                skin = skin,
            )
        }

        if (model.canUndo) {
            TextButton(
                onClick = { actions.onUndoHook(model.action) },
                colors = ButtonDefaults.textButtonColors(contentColor = palette.ink),
            ) { Text("UNDO", style = LogbookTheme.type.eyebrow) }
        }
    }
}

/** Ink on paper: a filled call to action, in the hook buttons' language. */
@Composable
internal fun InkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    skin: HookButtonSkin = PLAIN_SKIN,
) {
    val palette = LogbookTheme.palette
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        // Explicit: M3's default is a fully rounded pill, the one shape the
        // system forbids.
        shape = LogbookShapes.soft,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.ink,
            contentColor = palette.paper,
            disabledContainerColor = palette.rule,
            disabledContentColor = palette.inkFaint,
        ),
    ) { InkButtonLabel(label = label, skin = skin) }
}

/** The same button, hollow — everything that is not the primary call. */
@Composable
internal fun InkOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    skin: HookButtonSkin = PLAIN_SKIN,
) {
    val palette = LogbookTheme.palette
    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = LogbookShapes.soft,
        border = BorderStroke(LogbookSpace.hairline, palette.ink),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = palette.ink,
            disabledContentColor = palette.inkFaint,
        ),
    ) { InkButtonLabel(label = label, skin = skin) }
}

@Composable
private fun InkButtonLabel(label: String, skin: HookButtonSkin) {
    Row(
        // The fill and the glyph are visual; the state has to be audible too,
        // and it merges into the button's own node.
        modifier = Modifier.semantics {
            skin.a11yState?.let { stateDescription = it }
        },
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 1.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (skin.glyph) {
            HookGlyph.CHECK -> Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(GLYPH_SIZE),
            )

            HookGlyph.CROSS -> Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                modifier = Modifier.size(GLYPH_SIZE),
            )

            HookGlyph.NONE -> Unit
        }
        Text(text = label.uppercase(), style = LogbookTheme.type.eyebrow)
        skin.note?.let {
            Text(text = it.uppercase(), style = LogbookTheme.type.eyebrow)
        }
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
        // Bare on paper: a cell at rest is indistinguishable from the read-only
        // table it sits in, and the ghost recedes by colour alone.
        skin = DenseFieldSkin.NAKED,
        numeric = true,
        placeholder = placeholder,
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
    onChange: (String) -> Unit,
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
        skin = DenseFieldSkin.NAKED,
        multiLine = true,
        placeholder = placeholder,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
    )
}

// ---- drawing helpers ------------------------------------------------------------------

/** A whole row made tappable, with the label a screen reader announces. */
private fun Modifier.clickableRow(label: String, onClick: () -> Unit): Modifier = this
    .clickable(onClick = onClick)
    .semantics { contentDescription = label }

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

// ---- metrics ---------------------------------------------------------------------------

/** The page margin: the mockups' 20dp of paper down each side. */
private val SCREEN_PADDING = 20.dp
private val SCREEN_TOP = 22.dp
private val SCREEN_BOTTOM = 40.dp

/** The air a section stands in. Bigger than any gap inside one, which is what groups it. */
private val SECTION_GAP = 30.dp
private val SECTION_UNDERLINE_GAP = 6.dp

/** The superset gutter — empty on a straight set, bracketed on a grouped one. */
private val GUTTER = 16.dp
private val BRACKET_STROKE = 1.5.dp
private val BRACKET_TICK = 6.dp

private val TITLE_TOP = 10.dp
private val TITLE_BOTTOM = 8.dp
private val LEGEND_TOP = 14.dp
private const val META_SEPARATOR = " · "

private val PLATE_DOT = 9.dp
private val PLATE_DOT_GAP = 8.dp
private const val PLATE_DOT_SLOT = "plate"

private val TALLY_WIDTH = 6.dp
private val TALLY_HEIGHT = 11.dp
private val TALLY_GAP = 3.dp

/** Marks round by 1dp — enough to read as drawn rather than as a screen artefact. */
private val MARK_SHAPE = RoundedCornerShape(1.dp)
private val MARK_SIZE = 11.dp

/** The set-number gutter, narrow enough to leave the value columns usable. */
private val SET_NUMBER_COLUMN = 34.dp

/** The tick column on a read-only day: the mark and nothing around it. */
private val MARK_COLUMN = 24.dp
private val TABLE_HEAD_GAP = 5.dp
private val TABLE_CELL_PADDING = 6.dp

private val MARGINALIA_RAIL = 2.dp
private val MARGINALIA_INSET = 10.dp
private val MARGINALIA_GLYPH_LIFT = 2.dp

private val GLYPH_SIZE = 14.dp
private val EYEBROW_GLYPH = 12.dp
private val EYEBROW_DOT = 8.dp
private val SPINNER_SIZE = 28.dp
private val EMPTY_DAY_PADDING = 48.dp

private const val QUARTER_TURN = 90f

/**
 * A button with nothing to report: no glyph, no note.
 *
 * `filled` is unread here — which of the two buttons was called is what decides
 * that — and is set true only because a plain button is the filled one.
 */
internal val PLAIN_SKIN = HookButtonSkin(filled = true, glyph = HookGlyph.NONE, note = null, a11yState = null)
