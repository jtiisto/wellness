package dev.jtiisto.wellness.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.sync.DebugLogLogic
import dev.jtiisto.wellness.core.ui.theme.DenseFieldSkin
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessDenseField
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.data.sync.ForceSyncCopy
import org.koin.androidx.compose.koinViewModel
import kotlin.system.exitProcess

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
                // The switch has committed. Ending the task and the process is
                // the whole point: nothing in memory is valid for the new
                // server, and the user reopens into a clean boot.
                ToolsEvent.CloseApp -> {
                    context.findActivity()?.finishAffinity()
                    exitProcess(0)
                }
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

@Composable
private fun ToolsContent(state: ToolsUiState, actions: ToolsActions) {
    val palette = WellnessTheme.palette
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = WellnessSpace.lg),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        item {
            ServerStatusCard(
                state = state,
                onPing = actions.ping,
                modifier = Modifier.padding(start = WellnessSpace.md, end = WellnessSpace.md, top = 12.dp),
            )
        }

        item {
            ActionsCard(state = state, actions = actions, modifier = Modifier.padding(horizontal = WellnessSpace.md))
        }

        item {
            ServersCard(state = state, actions = actions, modifier = Modifier.padding(horizontal = WellnessSpace.md))
        }

        // Its own ViewModel and its own permission launchers: pairing is the one
        // thing on this tab that talks to hardware, and none of it belongs in
        // ToolsViewModel.
        item {
            StrapSection(modifier = Modifier.padding(horizontal = WellnessSpace.md))
        }

        item {
            HorizontalDivider(color = palette.line, modifier = Modifier.padding(top = WellnessSpace.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WellnessSpace.md, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Debug log", style = WellnessTheme.type.title, color = palette.textPrimary)
                TextButton(onClick = actions.shareLog, colors = WellnessDefaults.accentTextButtonColors()) {
                    Text(ToolsCopy.SHARE_LOG, style = WellnessTheme.type.label)
                }
            }
            Text(
                text = ToolsCopy.LOG_SPANS_SERVERS,
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
                modifier = Modifier.padding(horizontal = WellnessSpace.md),
            )
        }

        debugLogItems(state.log)
    }

    ToolsDialogs(dialog = state.dialog, actions = actions)
}

@Composable
private fun ServerStatusCard(state: ToolsUiState, onPing: () -> Unit, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    ToolsCard(modifier = modifier) {
        Text("Server", style = WellnessTheme.type.title, color = palette.textPrimary)
        Text(
            text = state.activeNickname,
            style = WellnessTheme.type.body,
            color = palette.textPrimary,
        )
        Text(
            text = state.baseUrl,
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
            fontFamily = FontFamily.Monospace,
        )
        Button(
            onClick = onPing,
            enabled = state.ping != ServerPing.InFlight,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentButtonColors(),
        ) {
            Text("Ping journal sync status", style = WellnessTheme.type.label)
        }
        when (val ping = state.ping) {
            ServerPing.Idle -> Unit
            ServerPing.InFlight -> CircularProgressIndicator(
                color = WellnessTheme.accent.fill,
                trackColor = palette.line,
            )
            is ServerPing.Reached -> Text(
                text = "lastModified: ${ping.lastModified ?: "(none)"}",
                style = WellnessTheme.type.secondary,
                color = palette.textPrimary,
                fontFamily = FontFamily.Monospace,
            )
            is ServerPing.Failed -> Text(
                text = ping.message,
                style = WellnessTheme.type.secondary,
                color = palette.error,
            )
        }
        Text(
            text = "Build ${state.buildStamp}",
            style = WellnessTheme.type.secondary,
            color = palette.textFaint,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ActionsCard(state: ToolsUiState, actions: ToolsActions, modifier: Modifier = Modifier) {
    val running = state.forceSync == ForceSyncUi.Running
    ToolsCard(modifier = modifier) {
        Button(
            onClick = actions.forceSync,
            enabled = !running,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (running) ForceSyncCopy.BUSY_BUTTON else ToolsCopy.FORCE_SYNC,
                style = WellnessTheme.type.label,
            )
        }
        OutlinedButton(
            onClick = actions.export,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentOutlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(ToolsCopy.EXPORT, style = WellnessTheme.type.label)
        }
    }
}

@Composable
private fun ServersCard(state: ToolsUiState, actions: ToolsActions, modifier: Modifier = Modifier) {
    val palette = WellnessTheme.palette
    ToolsCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Servers", style = WellnessTheme.type.title, color = palette.textPrimary)
            IconButton(onClick = actions.addProfile, colors = WellnessDefaults.accentTonalIconButtonColors()) {
                Icon(Icons.Filled.Add, contentDescription = "Add server", modifier = Modifier.size(18.dp))
            }
        }
        // The built-in row is always present, so an empty list means no *saved*
        // servers rather than nothing at all.
        if (state.servers.size <= 1) {
            Text(
                text = ToolsCopy.EMPTY_PROFILES,
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
            )
        }
        state.servers.forEach { row ->
            ServerRow(row = row, actions = actions)
        }
    }
}

@Composable
private fun ServerRow(row: ServerProfileRow, actions: ToolsActions) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WellnessSpace.touchTarget)
            .clip(WellnessShape.input)
            .clickable(enabled = !row.isActive) { actions.requestSwitch(row) }
            .background(if (row.isActive) accent.wash else palette.card)
            .padding(horizontal = WellnessSpace.sm, vertical = WellnessSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.nickname + if (row.isActive) " · active" else "",
                style = WellnessTheme.type.body,
                color = if (row.isActive) accent.text else palette.textPrimary,
            )
            Text(
                text = row.url,
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
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
            IconButton(onClick = { actions.editProfile(profile) }) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit ${row.nickname}",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { actions.requestDelete(profile) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${row.nickname}",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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
            destructive = false,
            onConfirm = actions.confirmForceSync,
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ForceSyncResult -> AlertDialog(
            onDismissRequest = actions.dismiss,
            containerColor = WellnessTheme.palette.card,
            title = { Text(if (dialog.success) "Force sync complete" else "Force sync") },
            text = { Text(dialog.message, style = WellnessTheme.type.body) },
            confirmButton = {
                TextButton(onClick = actions.dismiss, colors = WellnessDefaults.accentTextButtonColors()) {
                    Text("OK")
                }
            },
        )

        is ToolsDialog.EditProfile -> ProfileEditorDialog(dialog = dialog, actions = actions)

        is ToolsDialog.ConfirmDelete -> ConfirmDialog(
            title = ToolsCopy.DELETE_TITLE,
            body = ToolsCopy.deleteBody(dialog.profile.nickname),
            confirmLabel = ToolsCopy.DELETE_ACTION,
            destructive = true,
            onConfirm = { actions.confirmDelete(dialog.profile) },
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ConfirmDeleteActive -> ConfirmDialog(
            title = ToolsCopy.DELETE_TITLE,
            body = ToolsCopy.deleteActiveBody(dialog.profile.nickname),
            confirmLabel = ToolsCopy.DELETE_ACTION,
            destructive = true,
            onConfirm = { actions.confirmDeleteActive(dialog.profile) },
            onDismiss = actions.dismiss,
        )

        is ToolsDialog.ConfirmSwitch -> ConfirmDialog(
            title = ToolsCopy.SWITCH_TITLE,
            body = ToolsCopy.switchBody(dialog.nickname),
            confirmLabel = ToolsCopy.SWITCH_ACTION,
            destructive = true,
            onConfirm = { actions.confirmSwitch(dialog.target) },
            onDismiss = actions.dismiss,
        )
    }
}

@Composable
private fun ProfileEditorDialog(dialog: ToolsDialog.EditProfile, actions: ToolsActions) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        containerColor = WellnessTheme.palette.card,
        title = { Text(if (dialog.profile == null) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm)) {
                WellnessDenseField(
                    value = dialog.nickname,
                    onValueChange = { actions.editorChanged(it, dialog.url) },
                    label = "Name",
                    skin = DenseFieldSkin.OUTLINED,
                )
                // The active profile's address is fixed: changing servers is the
                // switch flow's job, and only it wipes. Read-only here rather
                // than merely refused on save, so the rule is visible before
                // anything is typed.
                val addressLocked = dialog.profile?.isActive == true
                WellnessDenseField(
                    value = dialog.url,
                    onValueChange = { actions.editorChanged(dialog.nickname, it) },
                    label = "Address",
                    placeholder = "https://host:9443/wellness",
                    skin = DenseFieldSkin.OUTLINED,
                    enabled = !addressLocked,
                    readOnly = addressLocked,
                    isError = dialog.error != null,
                    supportingText = dialog.error ?: if (addressLocked) ToolsCopy.ACTIVE_URL_LOCKED else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.saveProfile, colors = WellnessDefaults.accentTextButtonColors()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.dismiss, colors = WellnessDefaults.accentTextButtonColors()) {
                Text(ToolsCopy.CANCEL_ACTION)
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = WellnessTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text(title) },
        text = { Text(body, style = WellnessTheme.type.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) palette.error else WellnessTheme.accent.text)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = WellnessDefaults.accentTextButtonColors()) {
                Text(ToolsCopy.CANCEL_ACTION)
            }
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.debugLogItems(entries: List<DebugLogEntity>) {
    if (entries.isEmpty()) {
        item {
            Text(
                text = "No entries in the last hour.",
                style = WellnessTheme.type.secondary,
                color = WellnessTheme.palette.textSecondary,
                modifier = Modifier.padding(horizontal = WellnessSpace.md),
            )
        }
        return
    }
    items(items = entries, key = DebugLogEntity::id) { entry ->
        Text(
            text = DebugLogLogic.formatDumpLine(entry),
            style = WellnessTheme.type.secondary,
            color = WellnessTheme.palette.textSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = WellnessSpace.md, vertical = 2.dp),
        )
    }
}

/**
 * `inline` for the same reason Compose's own layout wrappers are.
 *
 * `internal` so the strap section is the same card as every other one on this
 * tab rather than a near-copy of it.
 */
@Composable
internal inline fun ToolsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = WellnessTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(WellnessSpace.hairline, palette.line, WellnessShape.card)
            .padding(WellnessSpace.md),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        content()
    }
}
