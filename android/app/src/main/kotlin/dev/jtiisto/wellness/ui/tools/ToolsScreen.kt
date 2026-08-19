package dev.jtiisto.wellness.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.sync.DebugLogLogic
import dev.jtiisto.wellness.core.data.sync.ForceSyncCopy
import dev.jtiisto.wellness.core.ui.theme.DenseFieldSkin
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.bottomRule
import org.koin.androidx.compose.koinViewModel

@Composable
fun ToolsScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: ToolsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ToolsEvent.Share -> shareStagedFile(context, event.path, event.mimeType)
                is ToolsEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    ToolsContent(state = state, actions = ToolsActions(viewModel))
}

/**
 * The tab's callbacks, bundled.
 *
 * Fifteen separate lambda parameters on [ToolsContent] would be a wall nobody
 * reads; one object keeps the composable's signature legible and the wiring in
 * one place.
 */
class ToolsActions(private val viewModel: ToolsViewModel) {
    val ping: () -> Unit = viewModel::pingServer
    val forceSync: () -> Unit = viewModel::requestForceSync
    val confirmForceSync: () -> Unit = viewModel::confirmForceSync
    val export: () -> Unit = viewModel::exportAllData
    val shareLog: () -> Unit = viewModel::shareDebugLog
    val addProfile: () -> Unit = viewModel::addProfile
    val editProfile: (ServerProfileEntity) -> Unit = viewModel::editProfile
    val editorChanged: (String, String) -> Unit = viewModel::editorChanged
    val saveProfile: () -> Unit = viewModel::saveProfile
    val requestDelete: (ServerProfileEntity) -> Unit = viewModel::requestDelete
    val confirmDelete: (ServerProfileEntity) -> Unit = viewModel::confirmDelete
    val confirmDeleteActive: (ServerProfileEntity) -> Unit = viewModel::confirmDeleteActive
    val requestSwitch: (ServerProfileRow) -> Unit = viewModel::requestSwitch
    val confirmSwitch: (ServerProfileEntity?) -> Unit = viewModel::confirmSwitch
    val dismiss: () -> Unit = viewModel::dismissDialog
}

/**
 * Five sections on one page: the server in force, the two heavy actions, the
 * address book, the strap, and the log.
 *
 * The cards are gone — Logbook has one surface, so a group is a display-caps
 * head with a rule under it and the air around it. Nothing on this tab is
 * decorative: every line is either something the app is doing or something it is
 * about to be told to do.
 */
@Composable
private fun ToolsContent(state: ToolsUiState, actions: ToolsActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = SCREEN_PADDING,
            end = SCREEN_PADDING,
            top = LogbookSpace.group,
            bottom = LogbookSpace.group,
        ),
    ) {
        // Spacing is per section rather than on the list: the log lines below
        // are items too, and a list-wide gap would file every one of them as a
        // section of its own.
        item { ServerSection(state = state, onPing = actions.ping, modifier = sectionSpacing()) }

        item { ActionsSection(state = state, actions = actions, modifier = sectionSpacing()) }

        item { ServersSection(state = state, actions = actions, modifier = sectionSpacing()) }

        // Its own ViewModel and its own permission launchers: pairing is the one
        // thing on this tab that talks to hardware, and none of it belongs in
        // ToolsViewModel.
        item { StrapSection(modifier = sectionSpacing()) }

        item {
            DebugLogHead(
                onShare = actions.shareLog,
                modifier = Modifier.padding(bottom = LogbookSpace.grid * 2),
            )
        }

        debugLogItems(state.log)
    }

    ToolsDialogs(dialog = state.dialog, actions = actions)
}

/** The air under every section but the last, which the log's own lines follow. */
private fun sectionSpacing(): Modifier = Modifier.padding(bottom = SECTION_GAP)

@Composable
private fun ServerSection(state: ToolsUiState, onPing: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    LogbookSection(title = "Server", sub = state.activeNickname, modifier = modifier) {
        Text(text = state.baseUrl, style = LogbookTheme.type.meta, color = palette.inkSoft)

        InkOutlineButton(
            label = "Ping journal sync status",
            onClick = onPing,
            enabled = state.ping != ServerPing.InFlight,
            // The hook-button language: a control that is working says so in
            // mono rather than spinning. There is no second indicator to keep
            // in step with it.
            note = if (state.ping == ServerPing.InFlight) ToolsCopy.WORKING else null,
        )

        when (val ping = state.ping) {
            ServerPing.Idle, ServerPing.InFlight -> Unit

            is ServerPing.Reached -> Text(
                text = "lastModified: ${ping.lastModified ?: "(none)"}",
                style = LogbookTheme.type.meta,
                color = palette.ink,
            )

            // Ink and the mono bang. A server that cannot be reached is worth
            // noticing and is not an emergency, which is exactly the distance
            // between this and a red line.
            is ServerPing.Failed -> InkNotice(text = ping.message)
        }

        Text(
            text = "Build ${state.buildStamp}",
            style = LogbookTheme.type.meta,
            // Read when it matters (which build is this?) — prose floor, not ghost.
            color = palette.inkSoft,
        )
    }
}

@Composable
private fun ActionsSection(state: ToolsUiState, actions: ToolsActions, modifier: Modifier = Modifier) {
    val running = state.forceSync == ForceSyncUi.Running
    LogbookSection(title = "Actions", modifier = modifier) {
        InkButton(
            label = if (running) ForceSyncCopy.BUSY_BUTTON else ToolsCopy.FORCE_SYNC,
            onClick = actions.forceSync,
            // Off during a switch as well: the gate is already closed, so the
            // cycle would refuse every write it made and report counts for a
            // server the app is in the middle of leaving.
            enabled = !running && !state.switching,
            modifier = Modifier.fillMaxWidth(),
        )
        InkOutlineButton(
            label = ToolsCopy.EXPORT,
            onClick = actions.export,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ServersSection(state: ToolsUiState, actions: ToolsActions, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    LogbookSection(
        title = "Servers",
        modifier = modifier,
        trailing = {
            OutlinedIconButton(
                onClick = actions.addProfile,
                border = BorderStroke(LogbookSpace.hairline, palette.ink),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = palette.ink),
                modifier = Modifier.size(ADD_BUTTON_SIZE),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add server", modifier = Modifier.size(GLYPH_SIZE))
            }
        },
    ) {
        // The built-in row is always present, so an empty list means no *saved*
        // servers rather than nothing at all.
        if (state.servers.size <= 1) {
            Text(
                text = ToolsCopy.EMPTY_PROFILES,
                style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
                color = palette.inkSoft,
            )
        }
        state.servers.forEach { row ->
            ServerRow(row = row, switching = state.switching, actions = actions)
        }
        // The switch takes seconds — quiescing every writer, then a wipe — and
        // ends with the app closing. Saying so is the difference between a
        // deliberate shutdown and one that reads as a crash.
        if (state.switching) {
            Text(
                text = ToolsCopy.SWITCHING,
                style = LogbookTheme.type.body,
                color = palette.ink,
            )
        }
    }
}

/**
 * One server, and which one is in force.
 *
 * The active row is **ruled under in ink** — the nav bar's underline and the
 * range segments say "this one" the same way. A tinted fill would be a second
 * surface, and the tint was also the only thing distinguishing the row: the mono
 * `ACTIVE` label now says it in words as well.
 */
@Composable
private fun ServerRow(row: ServerProfileRow, switching: Boolean, actions: ToolsActions) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget)
            .bottomRule(
                color = if (row.isActive) palette.ink else palette.rule,
                thickness = if (row.isActive) LogbookSpace.sectionUnderline else LogbookSpace.hairline,
            )
            .clickable(enabled = !row.isActive && !switching) { actions.requestSwitch(row) }
            .padding(vertical = LogbookSpace.grid * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.nickname, style = LogbookTheme.type.body, color = palette.ink)
                if (row.isActive) {
                    Text(
                        text = ToolsCopy.ACTIVE_LABEL.uppercase(),
                        style = LogbookTheme.type.eyebrow,
                        color = palette.inkSoft,
                        modifier = Modifier.padding(start = LogbookSpace.grid * 2),
                    )
                }
            }
            Text(text = row.url, style = LogbookTheme.type.meta, color = palette.inkSoft)
        }
        // The built-in row has no id, so there is nothing to edit or delete —
        // it is the fallback the whole scheme rests on.
        if (!row.isBuiltIn) {
            val profile = ServerProfileEntity(
                id = requireNotNull(row.id),
                nickname = row.nickname,
                url = row.url,
                isActive = row.isActive,
            )
            IconButton(onClick = { actions.editProfile(profile) }, enabled = !switching) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit ${row.nickname}",
                    tint = palette.inkSoft,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
            // Deleting the active profile IS a switch, so it is off for the same
            // reason the rows are.
            IconButton(onClick = { actions.requestDelete(profile) }, enabled = !switching) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${row.nickname}",
                    tint = palette.inkSoft,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }
    }
}

@Composable
private fun DebugLogHead(onShare: () -> Unit, modifier: Modifier = Modifier) {
    LogbookSection(
        title = "Debug log",
        modifier = modifier,
        trailing = {
            InkOutlineButton(label = ToolsCopy.SHARE_LOG, onClick = onShare, quiet = true)
        },
    ) {
        Text(
            text = ToolsCopy.LOG_SPANS_SERVERS,
            style = LogbookTheme.type.body,
            color = LogbookTheme.palette.inkSoft,
        )
    }
}

@Composable
private fun ToolsDialogs(dialog: ToolsDialog?, actions: ToolsActions) {
    when (dialog) {
        null -> Unit

        ToolsDialog.ConfirmForceSync -> ConfirmDialog(
            title = ToolsCopy.FORCE_SYNC_CONFIRM_TITLE,
            body = ForceSyncCopy.CONFIRM_BODY,
            confirmLabel = ToolsCopy.CONTINUE_ACTION,
            onConfirm = actions.confirmForceSync,
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ForceSyncResult -> AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(if (dialog.success) "Force sync complete" else "Force sync") },
            text = { Text(dialog.message, style = LogbookTheme.type.body) },
            confirmButton = { TextButton(onClick = actions.dismiss) { Text("OK") } },
        )

        is ToolsDialog.EditProfile -> ProfileEditorDialog(dialog = dialog, actions = actions)

        is ToolsDialog.ConfirmDelete -> ConfirmDialog(
            title = ToolsCopy.DELETE_TITLE,
            body = ToolsCopy.deleteBody(dialog.profile.nickname),
            confirmLabel = ToolsCopy.DELETE_ACTION,
            onConfirm = { actions.confirmDelete(dialog.profile) },
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ConfirmDeleteActive -> ConfirmDialog(
            title = ToolsCopy.DELETE_TITLE,
            body = ToolsCopy.deleteActiveBody(dialog.profile.nickname),
            confirmLabel = ToolsCopy.DELETE_ACTION,
            onConfirm = { actions.confirmDeleteActive(dialog.profile) },
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ConfirmSwitch -> ConfirmDialog(
            title = ToolsCopy.SWITCH_TITLE,
            body = ToolsCopy.switchBody(dialog.nickname),
            confirmLabel = ToolsCopy.SWITCH_ACTION,
            onConfirm = { actions.confirmSwitch(dialog.target) },
            onDismiss = actions.dismiss,
        )
    }
}

/**
 * Add or rename a server.
 *
 * The fields are naked — Logbook's skin — so each one is its own mono-caps label
 * over the value and a hairline under it. A parse error is stated in ink behind
 * the mono bang: there is no error colour in this system, and the field the
 * message belongs to is the one that carries it.
 */
@Composable
private fun ProfileEditorDialog(dialog: ToolsDialog.EditProfile, actions: ToolsActions) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        title = { Text(if (dialog.profile == null) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3)) {
                LogbookField(
                    label = "Name",
                    value = dialog.nickname,
                    onValueChange = { actions.editorChanged(it, dialog.url) },
                )
                // The active profile's address is fixed: changing servers is the
                // switch flow's job, and only it wipes. Read-only here rather
                // than merely refused on save, so the rule is visible before
                // anything is typed.
                val addressLocked = dialog.profile?.isActive == true
                LogbookField(
                    label = "Address",
                    value = dialog.url,
                    onValueChange = { actions.editorChanged(dialog.nickname, it) },
                    placeholder = "https://host:9443/wellness",
                    enabled = !addressLocked,
                    readOnly = addressLocked,
                    error = dialog.error,
                    note = if (addressLocked) ToolsCopy.ACTIVE_URL_LOCKED else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = actions.saveProfile) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = actions.dismiss) { Text(ToolsCopy.CANCEL_ACTION) }
        },
    )
}

/**
 * A naked field with the label and the message the skin deliberately does not
 * draw.
 *
 * [DenseFieldSkin.NAKED] is bare by definition — it ignores `label`,
 * `supportingText` and `isError` — so a Logbook form states them itself. The
 * error rides the field's **own** semantics node: announced only from a sibling
 * line, it is an error the form never tells a screen-reader user about.
 */
@Composable
private fun LogbookField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    error: String? = null,
    note: String? = null,
) {
    val palette = LogbookTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
        WellnessDenseField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .bottomRule(palette.ruleStrong)
                .fieldSemantics(label, error),
            enabled = enabled,
            readOnly = readOnly,
            skin = DenseFieldSkin.NAKED,
            placeholder = placeholder,
        )
        error?.let { InkNotice(text = it, modifier = Modifier.padding(top = LogbookSpace.grid)) }
        if (error == null) {
            note?.let {
                Text(
                    text = it,
                    style = LogbookTheme.type.body,
                    color = palette.inkSoft,
                    modifier = Modifier.padding(top = LogbookSpace.grid),
                )
            }
        }
    }
}

/**
 * An input's spoken identity, with its live error riding the same node.
 *
 * The drawn error sits below the field; a reader focusing the field hears the
 * field's node alone, so the message has to be on it. The naked skin draws
 * neither label nor error, which is exactly why this is the form's job — the
 * same shape `JournalConfigScreen` uses.
 */
private fun Modifier.fieldSemantics(description: String, errorMessage: String?): Modifier =
    semantics {
        contentDescription = description
        errorMessage?.let { error(it) }
    }

/**
 * One confirmation, one shape.
 *
 * The destructive verb keeps its own ink: Logbook has no error token, and the
 * body above the button already says exactly what is destroyed — which was
 * always the part that had to be read.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = LogbookTheme.type.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ToolsCopy.CANCEL_ACTION) } },
    )
}

private fun LazyListScope.debugLogItems(entries: List<DebugLogEntity>) {
    if (entries.isEmpty()) {
        item {
            Text(
                text = "No entries in the last hour.",
                style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
                color = LogbookTheme.palette.inkSoft,
            )
        }
        return
    }
    items(items = entries, key = DebugLogEntity::id) { entry ->
        Text(
            text = DebugLogLogic.formatDumpLine(entry),
            style = LogbookTheme.type.meta,
            color = LogbookTheme.palette.inkSoft,
            modifier = Modifier.padding(vertical = LOG_LINE_PADDING),
        )
    }
}

private val SCREEN_PADDING = 20.dp
private val SECTION_GAP = 22.dp
private val GLYPH_SIZE = 18.dp
private val ADD_BUTTON_SIZE = 36.dp
private val LOG_LINE_PADDING = 2.dp
