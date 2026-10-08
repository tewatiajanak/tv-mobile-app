package com.videobridge.phone.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videobridge.core.data.devices.DeviceItem
import com.videobridge.feature.auth.DevicesUiState
import com.videobridge.phone.R

const val DEVICES_LIST_TAG = "devices_list"

/** Phones and TVs on the account: rename, remove, connect another TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    uiState: DevicesUiState,
    onRename: (DeviceItem, String) -> Unit,
    onRemove: (DeviceItem) -> Unit,
    onConnectTv: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<DeviceItem?>(null) }
    var removing by remember { mutableStateOf<DeviceItem?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            if (uiState.failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.devices_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
            when {
                uiState.loading && uiState.devices.isEmpty() ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                uiState.devices.isEmpty() ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.devices_empty))
                    }

                else ->
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag(DEVICES_LIST_TAG),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.devices, key = { it.id }) { device ->
                            DeviceCard(device, onRename = { renaming = device }, onRemove = { removing = device })
                        }
                    }
            }
            Button(onClick = onConnectTv, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(stringResource(R.string.home_connect_tv))
            }
        }
    }

    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.devices_rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(MAX_DEVICE_NAME) }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(device, name)
                        renaming = null
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.devices_remove_title, device.name)) },
            text = {
                Text(stringResource(if (device.isThisDevice) R.string.devices_remove_this_body else R.string.devices_remove_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(device)
                        removing = null
                    },
                ) { Text(stringResource(R.string.devices_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val MAX_DEVICE_NAME = 60

@Composable
private fun DeviceCard(device: DeviceItem, onRename: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name, style = MaterialTheme.typography.titleMedium)
            val kind =
                when {
                    device.isThisDevice -> stringResource(R.string.devices_this_phone)
                    device.isTv -> stringResource(R.string.devices_tv)
                    else -> stringResource(R.string.devices_phone)
                }
            Text(
                text = listOfNotNull(kind, device.model?.takeIf { it != device.name }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onRename) { Text(stringResource(R.string.devices_rename)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.devices_remove)) }
            }
        }
    }
}
