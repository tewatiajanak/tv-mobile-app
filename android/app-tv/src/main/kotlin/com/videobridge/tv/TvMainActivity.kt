package com.videobridge.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import com.videobridge.core.data.auth.AuthState
import com.videobridge.core.data.downloads.DownloadStart
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.Video
import com.videobridge.core.player.PlayerFactory
import com.videobridge.core.tvdesignsystem.VideoBridgeTvTheme
import com.videobridge.feature.auth.AuthMode
import com.videobridge.feature.auth.AuthViewModel
import com.videobridge.feature.auth.SessionViewModel
import com.videobridge.feature.auth.TvPairingViewModel
import com.videobridge.feature.library.LibraryViewModel
import com.videobridge.tv.auth.TvAuthScreen
import com.videobridge.tv.home.TvHomeScreen
import com.videobridge.tv.pairing.TvPairingScreen
import com.videobridge.tv.player.TvPlayerScreen
import com.videobridge.tv.settings.TvSettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {
    private val sessionViewModel: SessionViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private val pairingViewModel: TvPairingViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()

    @Inject
    lateinit var playerFactory: PlayerFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On a TV most people already have an account (made on the phone), so start on sign-in.
        if (savedInstanceState == null) authViewModel.onModeChange(AuthMode.SIGN_IN)
        setContent {
            VideoBridgeTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    val authState by sessionViewModel.authState.collectAsStateWithLifecycle()
                    when (val state = authState) {
                        AuthState.Unknown -> Unit
                        is AuthState.LoggedOut -> SignedOut(state)
                        is AuthState.LoggedIn -> SignedIn(state)
                    }
                }
            }
        }
    }

    @Composable
    private fun SignedOut(state: AuthState.LoggedOut) {
        // Pairing with the phone is the main way in; typing is the fallback.
        var usePassword by rememberSaveable { mutableStateOf(false) }
        if (usePassword) {
            BackHandler { usePassword = false }
            val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
            TvAuthScreen(
                uiState = uiState,
                sessionEnded = state.sessionEnded,
                onNameChange = authViewModel::onNameChange,
                onPhoneChange = authViewModel::onPhoneChange,
                onPasswordChange = authViewModel::onPasswordChange,
                onTogglePasswordVisible = authViewModel::onTogglePasswordVisible,
                onModeChange = authViewModel::onModeChange,
                onSubmit = authViewModel::submit,
            )
        } else {
            val pairingState by pairingViewModel.uiState.collectAsStateWithLifecycle()
            // Polls only while this screen is on; leaving it cancels the loop.
            LaunchedEffect(Unit) { pairingViewModel.run() }
            TvPairingScreen(uiState = pairingState, onUsePassword = { usePassword = true })
        }
    }

    @Composable
    private fun SignedIn(state: AuthState.LoggedIn) {
        val library by libraryViewModel.uiState.collectAsStateWithLifecycle()
        val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
        val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
        val downloadMessage by libraryViewModel.downloadMessage.collectAsStateWithLifecycle()
        var playing by remember { mutableStateOf<Pair<Video, String>?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        val externalDownload by libraryViewModel.externalDownload.collectAsStateWithLifecycle()

        LaunchedEffect(externalDownload) {
            externalDownload?.let {
                downloadInAnotherApp(it.url, it.packageName)
                libraryViewModel.externalDownloadShown()
            }
        }

        LaunchedEffect(downloadMessage) {
            downloadMessage?.let {
                Toast.makeText(this@TvMainActivity, downloadMessageRes(it), Toast.LENGTH_LONG).show()
                libraryViewModel.downloadMessageShown()
            }
        }

        fun play(video: Video) {
            val status = downloads[video.id] ?: DownloadStatus.None
            if (settings.downloadsWhenOpened && status is DownloadStatus.None) libraryViewModel.download(video)
            // A finished download plays from the drive, with no network needed.
            val downloaded = (status as? DownloadStatus.Done)?.uri
            // A downloaded file always plays in Dekho: other apps cannot read this app's folder.
            if (downloaded != null || settings.player == PlayerChoice.DEKHO) {
                playing = video to (downloaded ?: video.sourceUrl)
            } else {
                playInAnotherApp(video.sourceUrl, settings.playerPackage)
            }
        }

        val nowPlaying = playing
        when {
            nowPlaying != null ->
                TvPlayerScreen(
                    video = nowPlaying.first,
                    uri = nowPlaying.second,
                    playerFactory = playerFactory,
                    onSavePosition = { position, duration -> libraryViewModel.savePosition(nowPlaying.first, position, duration) },
                    onExit = { playing = null },
                )

            showSettings ->
                TvSettingsScreen(
                    settings = settings,
                    // Read each time Settings opens, so a drive plugged in meanwhile shows up.
                    storage = remember { libraryViewModel.storageOptions() },
                    phoneMasked = state.phoneMasked,
                    apps = remember { libraryViewModel.videoApps() },
                    downloadApps = remember { libraryViewModel.downloadApps() },
                    onPlayer = libraryViewModel::setPlayer,
                    onDownloader = libraryViewModel::setDownloader,
                    onDownloadMode = libraryViewModel::setDownloadMode,
                    onStorage = libraryViewModel::setStorage,
                    onSignOut = sessionViewModel::signOut,
                    onClose = { showSettings = false },
                )

            else -> {
                // Polls only while the library is on screen: a link saved on the phone shows up
                // here within a few seconds.
                LaunchedEffect(Unit) { libraryViewModel.keepFresh() }
                TvHomeScreen(
                    displayName = state.displayName,
                    library = library,
                    downloads = downloads,
                    askBeforePlaying = settings.downloadMode == DownloadMode.ASK,
                    onQueryChange = libraryViewModel::onQueryChange,
                    onPlay = ::play,
                    onDownload = libraryViewModel::download,
                    onPauseDownload = libraryViewModel::pauseDownload,
                    onResumeDownload = libraryViewModel::resumeDownload,
                    onRemoveDownload = libraryViewModel::removeDownload,
                    onOpenSettings = { showSettings = true },
                )
            }
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
