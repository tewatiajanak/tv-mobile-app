package com.videobridge.phone.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.formatBytes
import com.videobridge.phone.R

/** Test tag of one choice, e.g. `settingTag("OTHER_APP")`. */
fun settingTag(id: String) = "setting_$id"

/** This phone's choices: which player, what opening a video does, where downloads go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    storage: List<StorageOption>,
    apps: List<VideoApp>,
    onPlayer: (PlayerChoice, String?) -> Unit,
    onDownloadMode: (DownloadMode) -> Unit,
    onStorage: (StorageOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section(stringResource(R.string.settings_player))
            Choice(
                PlayerChoice.DEKHO.name,
                stringResource(R.string.settings_player_dekho),
                stringResource(R.string.settings_player_dekho_hint),
                settings.player == PlayerChoice.DEKHO,
            ) { onPlayer(PlayerChoice.DEKHO, null) }
            Choice(
                PlayerChoice.OTHER_APP.name,
                stringResource(R.string.settings_player_other),
                stringResource(R.string.settings_player_other_hint),
                settings.player == PlayerChoice.OTHER_APP,
            ) { onPlayer(PlayerChoice.OTHER_APP, settings.playerPackage) }

            if (settings.player == PlayerChoice.OTHER_APP) {
                // Which other app: one installed on this phone, or let the phone ask each time.
                Section(stringResource(R.string.settings_player_choose_app))
                Choice("app_ask", stringResource(R.string.settings_player_ask), null, settings.playerPackage == null) {
                    onPlayer(PlayerChoice.OTHER_APP, null)
                }
                apps.forEach { app ->
                    Choice("app_${app.packageName}", app.label, null, settings.playerPackage == app.packageName) {
                        onPlayer(PlayerChoice.OTHER_APP, app.packageName)
                    }
                }
                if (apps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_player_no_apps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            Section(stringResource(R.string.settings_download))
            Choice(
                DownloadMode.MANUAL.name,
                stringResource(R.string.settings_download_manual),
                stringResource(R.string.settings_download_manual_hint),
                settings.downloadMode == DownloadMode.MANUAL,
            ) { onDownloadMode(DownloadMode.MANUAL) }
            Choice(DownloadMode.ASK.name, stringResource(R.string.settings_download_ask), null, settings.downloadMode == DownloadMode.ASK) {
                onDownloadMode(DownloadMode.ASK)
            }
            Choice(
                DownloadMode.ALWAYS.name,
                stringResource(R.string.settings_download_always),
                null,
                settings.downloadMode == DownloadMode.ALWAYS,
            ) { onDownloadMode(DownloadMode.ALWAYS) }

            Section(stringResource(R.string.settings_storage))
            storage.forEachIndexed { index, option ->
                val chosen = settings.storageId == option.id || (settings.storageId == null && index == 0)
                Choice(
                    id = "storage_$index",
                    title = stringResource(
                        if (option.isRemovable) R.string.settings_storage_removable else R.string.settings_storage_internal,
                    ),
                    hint =
                    if (option.isConnected) {
                        stringResource(R.string.settings_storage_free, formatBytes(option.freeBytes), formatBytes(option.totalBytes))
                    } else {
                        stringResource(R.string.settings_storage_disconnected)
                    },
                    selected = chosen,
                    enabled = option.isConnected,
                ) { onStorage(option) }
            }
            Text(
                text = stringResource(R.string.settings_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(id: String, title: String, hint: String?, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(settingTag(id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row is the control; the radio button only shows the state.
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
