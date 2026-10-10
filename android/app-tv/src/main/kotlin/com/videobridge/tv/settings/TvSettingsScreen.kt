package com.videobridge.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.DownloaderChoice
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.formatBytes
import com.videobridge.tv.R

const val TV_SETTINGS_PANEL_TAG = "tv_settings_panel"
const val TV_SETTINGS_SIGN_OUT_TAG = "tv_setting_sign_out"
const val TV_SETTINGS_SIGN_OUT_CONFIRM_TAG = "tv_setting_sign_out_confirm"
const val TV_SETTINGS_SIGN_OUT_CANCEL_TAG = "tv_setting_sign_out_cancel"

/** Test tag of one choice, e.g. `tvSettingTag("OTHER_APP")`. */
fun tvSettingTag(id: String) = "tv_setting_$id"

private val PANEL_WIDTH = 400.dp
private val PANEL_COLOR = Color(0xFF171B22)
private val MUTED = Color(0xFF9CA3AF)
private val ACCENT = Color(0xFFFFB74D)
private const val SLIDE_MS = 220

/**
 * Settings as a side panel sliding in from the right, the way TV systems show them: one
 * column of rows, the chosen option of each group marked with a filled dot. Covers which app
 * plays videos (with the video apps installed on this TV), what opening a video does, where
 * downloads go (free space and whether each drive is connected), and signing out.
 * Back closes it.
 */
@Composable
fun TvSettingsScreen(
    settings: AppSettings,
    storage: List<StorageOption>,
    apps: List<VideoApp>,
    downloadApps: List<VideoApp>,
    phoneMasked: String,
    onPlayer: (PlayerChoice, String?) -> Unit,
    onDownloader: (DownloaderChoice, String?) -> Unit,
    onDownloadMode: (DownloadMode) -> Unit,
    onStorage: (StorageOption) -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    var confirmingSignOut by remember { mutableStateOf(false) }
    // Starts hidden and is shown at once, so the panel slides in when the screen opens.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    BackHandler { if (confirmingSignOut) confirmingSignOut = false else onClose() }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0B0E13))) {
        // The dimmed side: just branding, nothing focusable, so focus cannot leave the panel.
        Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp)) {
            Image(
                painter = painterResource(R.drawable.logo_dekho),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                alpha = 0.5f,
            )
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.settings_back_hint), fontSize = 16.sp, color = Color(0xFF6B7280))
        }
        AnimatedVisibility(
            visibleState = shown,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(SLIDE_MS)) { it } + fadeIn(tween(SLIDE_MS)),
        ) {
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
            Column(
                modifier =
                Modifier
                    .width(PANEL_WIDTH)
                    .fillMaxHeight()
                    .background(PANEL_COLOR)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 27.dp)
                    .testTag(TV_SETTINGS_PANEL_TAG),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = stringResource(R.string.settings_title), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)

                Section(stringResource(R.string.settings_player))
                Item(
                    id = PlayerChoice.DEKHO.name,
                    title = stringResource(R.string.settings_player_dekho),
                    detail = stringResource(R.string.settings_player_dekho_hint),
                    selected = settings.player == PlayerChoice.DEKHO,
                    onClick = { onPlayer(PlayerChoice.DEKHO, null) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                Item(
                    id = PlayerChoice.OTHER_APP.name,
                    title = stringResource(R.string.settings_player_other),
                    detail = stringResource(R.string.settings_player_other_hint),
                    selected = settings.player == PlayerChoice.OTHER_APP,
                    onClick = { onPlayer(PlayerChoice.OTHER_APP, settings.playerPackage) },
                )
                if (settings.player == PlayerChoice.OTHER_APP) {
                    // Which other app: one installed on this TV, or let the TV ask each time.
                    Section(stringResource(R.string.settings_player_choose_app))
                    Item("app_ask", stringResource(R.string.settings_player_ask), null, settings.playerPackage == null, {
                        onPlayer(PlayerChoice.OTHER_APP, null)
                    })
                    apps.forEach { app ->
                        Item("app_${app.packageName}", app.label, null, settings.playerPackage == app.packageName, {
                            onPlayer(PlayerChoice.OTHER_APP, app.packageName)
                        })
                    }
                    if (apps.isEmpty()) Note(stringResource(R.string.settings_player_no_apps))
                }

                Section(stringResource(R.string.settings_downloader))
                Item(
                    id = "downloader_DEKHO",
                    title = stringResource(R.string.settings_downloader_dekho),
                    detail = stringResource(R.string.settings_downloader_dekho_hint),
                    selected = settings.downloader == DownloaderChoice.DEKHO,
                    onClick = { onDownloader(DownloaderChoice.DEKHO, null) },
                )
                Item(
                    id = "downloader_OTHER_APP",
                    title = stringResource(R.string.settings_downloader_other),
                    detail = stringResource(R.string.settings_downloader_other_hint),
                    selected = settings.downloader == DownloaderChoice.OTHER_APP,
                    onClick = { onDownloader(DownloaderChoice.OTHER_APP, settings.downloaderPackage) },
                )
                if (settings.downloader == DownloaderChoice.OTHER_APP) {
                    // Which other app: one installed on this TV, or let the TV ask each time.
                    Section(stringResource(R.string.settings_downloader_choose_app))
                    Item("downloader_ask", stringResource(R.string.settings_player_ask), null, settings.downloaderPackage == null, {
                        onDownloader(DownloaderChoice.OTHER_APP, null)
                    })
                    downloadApps.forEach { app ->
                        Item("downloader_app_${app.packageName}", app.label, null, settings.downloaderPackage == app.packageName, {
                            onDownloader(DownloaderChoice.OTHER_APP, app.packageName)
                        })
                    }
                    if (downloadApps.isEmpty()) Note(stringResource(R.string.settings_downloader_no_apps))
                }

                Section(stringResource(R.string.settings_download))
                Item(
                    DownloadMode.MANUAL.name,
                    stringResource(R.string.settings_download_manual),
                    stringResource(R.string.video_long_press_hint),
                    settings.downloadMode == DownloadMode.MANUAL,
                    { onDownloadMode(DownloadMode.MANUAL) },
                )
                Item(
                    DownloadMode.ASK.name,
                    stringResource(
                        R.string.settings_download_ask,
                    ),
                    null,
                    settings.downloadMode == DownloadMode.ASK,
                    {
                        onDownloadMode(DownloadMode.ASK)
                    },
                )
                Item(
                    DownloadMode.ALWAYS.name,
                    stringResource(R.string.settings_download_always),
                    null,
                    settings.downloadMode == DownloadMode.ALWAYS,
                    {
                        onDownloadMode(DownloadMode.ALWAYS)
                    },
                )

                Section(stringResource(R.string.settings_storage))
                storage.forEachIndexed { index, option ->
                    Item(
                        id = "storage_$index",
                        title = stringResource(
                            if (option.isRemovable) R.string.settings_storage_removable else R.string.settings_storage_internal,
                        ),
                        detail =
                        if (option.isConnected) {
                            stringResource(
                                R.string.settings_storage_free,
                                formatBytes(option.freeBytes),
                                formatBytes(option.totalBytes),
                            )
                        } else {
                            stringResource(R.string.settings_storage_disconnected)
                        },
                        selected = settings.storageId == option.id || (settings.storageId == null && index == 0),
                        onClick = { onStorage(option) },
                        enabled = option.isConnected,
                    )
                }
                if (storage.none { it.isRemovable }) Note(stringResource(R.string.settings_no_removable))
                Note(stringResource(R.string.settings_storage_note))

                Section(stringResource(R.string.home_signed_in_as, phoneMasked))
                if (confirmingSignOut) {
                    // One accidental OK on the remote must not sign the TV out: Cancel has focus.
                    val cancelFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
                    Note(stringResource(R.string.home_sign_out_confirm))
                    Item(
                        id = "sign_out_cancel",
                        title = stringResource(R.string.action_cancel),
                        detail = null,
                        selected = null,
                        onClick = { confirmingSignOut = false },
                        modifier = Modifier.focusRequester(cancelFocus).testTag(TV_SETTINGS_SIGN_OUT_CANCEL_TAG),
                    )
                    Item(
                        id = "sign_out_confirm",
                        title = stringResource(R.string.home_sign_out),
                        detail = null,
                        selected = null,
                        onClick = onSignOut,
                        modifier = Modifier.testTag(TV_SETTINGS_SIGN_OUT_CONFIRM_TAG),
                    )
                } else {
                    Item(
                        id = "sign_out",
                        title = stringResource(R.string.settings_sign_out),
                        detail = null,
                        selected = null,
                        onClick = { confirmingSignOut = true },
                        modifier = Modifier.testTag(TV_SETTINGS_SIGN_OUT_TAG),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = ACCENT,
        modifier = Modifier.padding(top = 14.dp, start = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text = text, fontSize = 14.sp, color = MUTED, modifier = Modifier.padding(horizontal = 4.dp))
}

/** One row. [selected] null means a plain action (no dot); true/false a choice in a group. */
@Composable
private fun Item(
    id: String,
    title: String,
    detail: String?,
    selected: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        // Every row is tagged by its id (the sign-out rows' constants above follow the same scheme).
        modifier = Modifier.fillMaxWidth().testTag(tvSettingTag(id)).then(modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors =
        ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFE5E7EB),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0xFF6B7280),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Text(text = if (selected) "●" else "○", fontSize = 16.sp, modifier = Modifier.width(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(text = detail, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
