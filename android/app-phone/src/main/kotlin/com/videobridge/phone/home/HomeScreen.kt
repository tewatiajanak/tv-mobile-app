package com.videobridge.phone.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.model.Video
import com.videobridge.core.model.formatBytes
import com.videobridge.feature.library.LibraryError
import com.videobridge.feature.library.LibraryUiState
import com.videobridge.phone.R

const val HOME_GREETING_TAG = "home_greeting"
const val HOME_MENU_TAG = "home_menu"
const val HOME_ADD_TAG = "home_add"
const val HOME_LIST_TAG = "home_list"
const val HOME_SEARCH_TAG = "home_search"
const val ADD_NAME_TAG = "add_name"
const val ADD_URL_TAG = "add_url"
const val ADD_SAVE_TAG = "add_save"
const val ADD_ERROR_TAG = "add_error"

private const val MAX_NAME = 200

/**
 * Home is the video library: a search box, the saved videos (by name, newest first) and a +
 * button. Account and device chores live in the menu at the top right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    displayName: String,
    library: LibraryUiState,
    downloads: Map<String, DownloadStatus>,
    onQueryChange: (String) -> Unit,
    onAdd: (name: String, url: String) -> Unit,
    onAddResultShown: () -> Unit,
    onOpen: (Video) -> Unit,
    onDownload: (Video) -> Unit,
    onPauseDownload: (Video) -> Unit,
    onResumeDownload: (Video) -> Unit,
    onRemoveDownload: (Video) -> Unit,
    onRemove: (Video) -> Unit,
    onConnectTv: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Video?>(null) }

    fun choose(action: () -> Unit) {
        menuOpen = false
        action()
    }
    // The form closes itself once the link is saved.
    LaunchedEffect(library.justAdded) {
        if (library.justAdded != null) {
            adding = false
            onAddResultShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_greeting, displayName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(HOME_GREETING_TAG),
                    )
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(HOME_MENU_TAG)) {
                        Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.home_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_connect_tv)) }, onClick = { choose(onConnectTv) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_devices)) }, onClick = { choose(onDevices) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_settings)) }, onClick = { choose(onSettings) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_sign_out)) }, onClick = { choose(onSignOut) })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    onAddResultShown()
                    adding = true
                },
                modifier = Modifier.testTag(HOME_ADD_TAG),
            ) { Text("+  " + stringResource(R.string.library_add)) }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (library.videos.isNotEmpty()) {
                OutlinedTextField(
                    value = library.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    leadingIcon = { Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (library.query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("✕") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(HOME_SEARCH_TAG),
                )
            }
            if (library.offline) {
                Text(
                    text = stringResource(R.string.library_offline),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val visible = library.visibleVideos
            when {
                library.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                library.videos.isEmpty() -> Message(stringResource(R.string.home_empty), stringResource(R.string.home_empty_hint))

                visible.isEmpty() -> Message(stringResource(R.string.library_no_results, library.query.trim()), null)

                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
                        // Room at the bottom so the + button never covers the last video.
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { video ->
                            VideoCard(
                                video = video,
                                download = downloads[video.id] ?: DownloadStatus.None,
                                onOpen = { onOpen(video) },
                                onDownload = { onDownload(video) },
                                onPauseDownload = { onPauseDownload(video) },
                                onResumeDownload = { onResumeDownload(video) },
                                onRemoveDownload = { onRemoveDownload(video) },
                                onRemove = { removing = video },
                            )
                        }
                    }
            }
        }
    }

    if (adding) {
        AddVideoDialog(
            busy = library.adding,
            error = library.addError,
            onSave = onAdd,
            onDismiss = {
                adding = false
                onAddResultShown()
            },
        )
    }
    removing?.let { video ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.library_remove_title)) },
            text = { Text(stringResource(R.string.library_remove_body, video.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(video)
                        removing = null
                    },
                ) { Text(stringResource(R.string.library_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Message(title: String, body: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AddVideoDialog(busy: Boolean, error: LibraryError?, onSave: (name: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.library_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.library_field_name)) },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(ADD_NAME_TAG),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.library_field_url)) },
                    enabled = !busy,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(ADD_URL_TAG),
                )
                // The clipboard is read only when asked: Android tells the user about every read.
                TextButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }, enabled = !busy) {
                    Text(stringResource(R.string.library_paste))
                }
                if (error != null) {
                    Text(text = errorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ADD_ERROR_TAG))
                }
            }
        },
        confirmButton = {
            // Both are required: the name is what the library shows instead of the link.
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = !busy && name.isNotBlank() && url.isNotBlank(),
                modifier = Modifier.testTag(ADD_SAVE_TAG),
            ) { Text(stringResource(R.string.library_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun errorText(error: LibraryError): String = when (error) {
    LibraryError.Unreachable -> stringResource(R.string.library_error_unreachable)
    LibraryError.InvalidLink -> stringResource(R.string.library_error_invalid)
    LibraryError.AlreadySaved -> stringResource(R.string.library_error_duplicate)
    LibraryError.LimitReached -> stringResource(R.string.library_error_limit)
    is LibraryError.Message -> error.text.ifBlank { stringResource(R.string.library_error_unexpected) }
}
