package com.videobridge.phone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.videobridge.core.data.auth.AuthState
import com.videobridge.core.data.downloads.DownloadStart
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.designsystem.VideoBridgeTheme
import com.videobridge.core.model.Video
import com.videobridge.core.player.PlayerFactory
import com.videobridge.feature.auth.AuthViewModel
import com.videobridge.feature.auth.ConnectTvViewModel
import com.videobridge.feature.auth.DevicesViewModel
import com.videobridge.feature.auth.SessionViewModel
import com.videobridge.feature.library.LibraryViewModel
import com.videobridge.phone.auth.AuthScreen
import com.videobridge.phone.devices.ConnectTvScreen
import com.videobridge.phone.devices.DevicesScreen
import com.videobridge.phone.home.HomeScreen
import com.videobridge.phone.player.PhonePlayerScreen
import com.videobridge.phone.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val sessionViewModel: SessionViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val devicesViewModel: DevicesViewModel by viewModels()
    private val connectTvViewModel: ConnectTvViewModel by viewModels()

    @Inject
    lateinit var playerFactory: PlayerFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VideoBridgeTheme {
                val authState by sessionViewModel.authState.collectAsStateWithLifecycle()
                when (val state = authState) {
                    // A few milliseconds while the saved session is read; no sign-in form flashes.
                    AuthState.Unknown ->
                        Surface(Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }

                    is AuthState.LoggedOut -> {
                        val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
                        AuthScreen(
                            uiState = uiState,
                            sessionEnded = state.sessionEnded,
                            onNameChange = authViewModel::onNameChange,
                            onPhoneChange = authViewModel::onPhoneChange,
                            onPasswordChange = authViewModel::onPasswordChange,
                            onTogglePasswordVisible = authViewModel::onTogglePasswordVisible,
                            onModeChange = authViewModel::onModeChange,
                            onSubmit = authViewModel::submit,
                        )
                    }

                    is AuthState.LoggedIn -> SignedIn(state)
                }
            }
        }
    }

    private enum class Screen { HOME, DEVICES, CONNECT_TV, SETTINGS }

    /** A plain switch between the signed-in screens is enough for now. */
    @Composable
    private fun SignedIn(state: AuthState.LoggedIn) {
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        var scannerFailed by rememberSaveable { mutableStateOf(false) }
        var playing by remember { mutableStateOf<Pair<Video, String>?>(null) }
        var asking by remember { mutableStateOf<Video?>(null) }
        val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
        val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
        val downloadMessage by libraryViewModel.downloadMessage.collectAsStateWithLifecycle()

        val externalDownload by libraryViewModel.externalDownload.collectAsStateWithLifecycle()

        LaunchedEffect(externalDownload) {
            externalDownload?.let {
                downloadInAnotherApp(it.url, it.packageName)
                libraryViewModel.externalDownloadShown()
            }
        }

        LaunchedEffect(downloadMessage) {
            downloadMessage?.let {
                Toast.makeText(this@MainActivity, downloadMessageRes(it), Toast.LENGTH_LONG).show()
                libraryViewModel.downloadMessageShown()
            }
        }

        fun play(video: Video) {
            // A finished download plays from the device, with no network needed.
            val downloaded = (downloads[video.id] as? DownloadStatus.Done)?.uri
            // A downloaded file always plays in Dekho: other apps cannot read this app's folder.
            if (downloaded != null || settings.player == PlayerChoice.DEKHO) {
                playing = video to (downloaded ?: video.sourceUrl)
            } else {
                playInAnotherApp(video.sourceUrl, settings.playerPackage)
            }
        }

        fun open(video: Video) {
            val alreadyHandled = (downloads[video.id] ?: DownloadStatus.None) !is DownloadStatus.None
            when {
                settings.downloadMode == DownloadMode.ASK && !alreadyHandled -> asking = video

                else -> {
                    if (settings.downloadsWhenOpened && !alreadyHandled) libraryViewModel.download(video)
                    play(video)
                }
            }
        }

        fun openConnectTv() {
            connectTvViewModel.reset()
            scannerFailed = false
            screen = Screen.CONNECT_TV
        }

        playing?.let { (video, uri) ->
            PhonePlayerScreen(
                video = video,
                uri = uri,
                playerFactory = playerFactory,
                onSavePosition = { position, duration -> libraryViewModel.savePosition(video, position, duration) },
                onExit = { playing = null },
            )
            return
        }
        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        when (screen) {
            Screen.HOME -> {
                val library by libraryViewModel.uiState.collectAsStateWithLifecycle()
                // Reloads whenever Home comes back, e.g. after sharing a link from another app.
                LifecycleResumeEffect(Unit) {
                    libraryViewModel.refresh()
                    onPauseOrDispose { }
                }
                HomeScreen(
                    displayName = state.displayName,
                    library = library,
                    downloads = downloads,
                    onQueryChange = libraryViewModel::onQueryChange,
                    onAdd = { name, url -> libraryViewModel.add(url, name) },
                    onAddResultShown = libraryViewModel::clearAddResult,
                    onOpen = ::open,
                    onDownload = libraryViewModel::download,
                    onPauseDownload = libraryViewModel::pauseDownload,
                    onResumeDownload = libraryViewModel::resumeDownload,
                    onRemoveDownload = libraryViewModel::removeDownload,
                    onRemove = libraryViewModel::remove,
                    onConnectTv = ::openConnectTv,
                    onDevices = { screen = Screen.DEVICES },
                    onSettings = { screen = Screen.SETTINGS },
                    onSignOut = sessionViewModel::signOut,
                )
            }

            Screen.SETTINGS ->
                SettingsScreen(
                    settings = settings,
                    storage = remember { libraryViewModel.storageOptions() },
                    apps = remember { libraryViewModel.videoApps() },
                    downloadApps = remember { libraryViewModel.downloadApps() },
                    onPlayer = libraryViewModel::setPlayer,
                    onDownloader = libraryViewModel::setDownloader,
                    onDownloadMode = libraryViewModel::setDownloadMode,
                    onStorage = libraryViewModel::setStorage,
                    onClose = { screen = Screen.HOME },
                )

            Screen.DEVICES -> {
                val uiState by devicesViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { devicesViewModel.refresh() }
                DevicesScreen(
                    uiState = uiState,
                    onRename = devicesViewModel::rename,
                    onRemove = devicesViewModel::remove,
                    onConnectTv = ::openConnectTv,
                    onRetry = devicesViewModel::refresh,
                    onClose = { screen = Screen.HOME },
                )
            }

            Screen.CONNECT_TV -> {
                val uiState by connectTvViewModel.uiState.collectAsStateWithLifecycle()
                ConnectTvScreen(
                    uiState = uiState,
                    scannerFailed = scannerFailed,
                    onCodeChange = connectTvViewModel::onCodeChange,
                    onSubmitCode = connectTvViewModel::submitCode,
                    onScan = {
                        // Google's scanner UI: no camera permission needed. Without Play services
                        // (or if it fails) the typed code still works.
                        GmsBarcodeScanning
                            .getClient(this)
                            .startScan()
                            .addOnSuccessListener { barcode -> barcode.rawValue?.let(connectTvViewModel::onScanned) }
                            .addOnFailureListener { scannerFailed = true }
                    },
                    onTvNameChange = connectTvViewModel::onTvNameChange,
                    onApprove = connectTvViewModel::approve,
                    onReject = connectTvViewModel::reject,
                    onClose = { screen = Screen.HOME },
                )
            }
        }

        asking?.let { video ->
            AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text(video.title) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            asking = null
                            play(video)
                        },
                    ) { Text(stringResource(R.string.video_action_play)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            asking = null
                            libraryViewModel.download(video)
                        },
                    ) { Text(stringResource(R.string.video_action_download)) }
                },
            )
        }
    }

    private fun downloadMessageRes(result: DownloadStart): Int = when (result) {
        DownloadStart.STARTED -> R.string.download_started
        DownloadStart.ALREADY_THERE -> R.string.download_already
        DownloadStart.STORAGE_NOT_CONNECTED -> R.string.download_no_storage
        DownloadStart.NOT_ENOUGH_SPACE -> R.string.download_no_space
        DownloadStart.NOT_DOWNLOADABLE -> R.string.download_not_possible
    }

    /**
     * "Another video app": hand the link to the app chosen in Settings, or let the device ask
     * when none was chosen (or the chosen one has since been uninstalled).
     */
    private fun playInAnotherApp(url: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
        try {
            startActivity(Intent(intent).setPackage(packageName))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.library_no_player, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * "Another app" as the downloader: hand the link to the app chosen in Settings, or let the
     * device ask when none was chosen (or the chosen one has since been uninstalled).
     */
    private fun downloadInAnotherApp(url: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val opened =
            listOf(Intent(intent).setPackage(packageName), intent).any {
                try {
                    startActivity(it)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        Toast.makeText(this, if (opened) R.string.download_handed_over else R.string.download_no_app, Toast.LENGTH_LONG).show()
    }
}
