package com.videobridge.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.downloads.DownloadStart
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.data.downloads.Downloads
import com.videobridge.core.data.downloads.InstalledDownloadApps
import com.videobridge.core.data.downloads.InstalledVideoApps
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.data.videos.VideosRepository
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.datastore.DeviceSettings
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.DownloaderChoice
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibraryError {
    data object Unreachable : LibraryError

    data object InvalidLink : LibraryError

    data object AlreadySaved : LibraryError

    data object LimitReached : LibraryError

    data class Message(val text: String) : LibraryError
}

/** A download for another app to do: the link, and the chosen app (null lets the device ask). */
data class ExternalDownload(val url: String, val packageName: String?)

data class LibraryUiState(
    /** True only until the first answer arrives; refreshes after that are silent. */
    val loading: Boolean = true,
    val videos: List<Video> = emptyList(),
    /** What the user typed in the search box; [visibleVideos] is filtered by it. */
    val query: String = "",
    /** The list could not be loaded or refreshed; [videos] may be stale. */
    val offline: Boolean = false,
    val adding: Boolean = false,
    val addError: LibraryError? = null,
    /** Set when a link was just saved, so the screen can close its form and say so. */
    val justAdded: Video? = null,
) {
    /** The videos whose name contains every word of the search, in any order. */
    val visibleVideos: List<Video>
        get() {
            val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
            return if (words.isEmpty()) videos else videos.filter { video -> words.all { it in video.title.lowercase() } }
        }
}

/**
 * The library on both phone and TV, plus this device's playback and download settings.
 * The backend is the source of truth for the library; nothing is cached yet.
 */
@HiltViewModel
class LibraryViewModel
@Inject
constructor(
    private val repository: VideosRepository,
    private val downloadsManager: Downloads,
    private val settingsStore: DeviceSettings,
    private val installedVideoApps: InstalledVideoApps,
    private val installedDownloadApps: InstalledDownloadApps,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** This device's choices: which player, how downloads start, where they are saved. */
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Download state per video id, live while a screen is showing it. */
    val downloads: StateFlow<Map<String, DownloadStatus>> =
        downloadsManager.statuses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val _downloadMessage = MutableStateFlow<DownloadStart?>(null)

    /** The outcome of the last "download" request, for the screen to announce once. */
    val downloadMessage: StateFlow<DownloadStart?> = _downloadMessage.asStateFlow()

    private val _externalDownload = MutableStateFlow<ExternalDownload?>(null)

    /** A download the screen must hand to another app, once. */
    val externalDownload: StateFlow<ExternalDownload?> = _externalDownload.asStateFlow()

    fun storageOptions(): List<StorageOption> = downloadsManager.storageOptions()

    /** Other video apps on this device, for the "play with" setting. */
    fun videoApps(): List<VideoApp> = installedVideoApps.installed()

    /** [packageName] names the other app to use; null with OTHER_APP lets the device ask. */
    fun setPlayer(value: PlayerChoice, packageName: String? = null) {
        viewModelScope.launch { settingsStore.setPlayer(value, packageName) }
    }

    /** Apps on this device that can be handed a link, for the "download with" setting. */
    fun downloadApps(): List<VideoApp> = installedDownloadApps.installed()

    /** [packageName] names the other app to use; null with OTHER_APP lets the device ask. */
    fun setDownloader(value: DownloaderChoice, packageName: String? = null) {
        viewModelScope.launch { settingsStore.setDownloader(value, packageName) }
    }

    fun setDownloadMode(value: DownloadMode) {
        viewModelScope.launch { settingsStore.setDownloadMode(value) }
    }

    fun setStorage(option: StorageOption) {
        viewModelScope.launch { settingsStore.setStorageId(option.id) }
    }

    fun download(video: Video) {
        val chosen = settings.value
        if (chosen.downloader == DownloaderChoice.OTHER_APP) {
            _externalDownload.value = ExternalDownload(video.sourceUrl, chosen.downloaderPackage)
            return
        }
        viewModelScope.launch { _downloadMessage.value = downloadsManager.start(video) }
    }

    fun pauseDownload(video: Video) {
        viewModelScope.launch { downloadsManager.pause(video.id) }
    }

    fun resumeDownload(video: Video) {
        viewModelScope.launch { downloadsManager.resume(video.id) }
    }

    fun removeDownload(video: Video) {
        viewModelScope.launch { downloadsManager.remove(video.id) }
    }

    fun externalDownloadShown() {
        _externalDownload.value = null
    }

    fun downloadMessageShown() {
        _downloadMessage.value = null
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value.take(MAX_QUERY_LENGTH)) }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    /**
     * Keeps the list fresh while a screen shows it, so a link saved on the phone appears on
     * the TV within a few seconds. The caller launches this in a scope tied to the screen.
     */
    suspend fun keepFresh(intervalMs: Long = REFRESH_INTERVAL_MS) {
        while (true) {
            load()
            delay(intervalMs)
        }
    }

    private suspend fun load() {
        val result = repository.list()
        _uiState.update {
            when (result) {
                is AppResult.Success -> it.copy(loading = false, videos = result.data, offline = false)
                is AppResult.Failure -> it.copy(loading = false, offline = true)
            }
        }
    }

    fun add(url: String, title: String? = null) {
        if (_uiState.value.adding || url.isBlank()) return
        _uiState.update { it.copy(adding = true, addError = null, justAdded = null) }
        viewModelScope.launch {
            when (val result = repository.add(url, title)) {
                is AppResult.Success ->
                    _uiState.update {
                        it.copy(adding = false, justAdded = result.data, videos = listOf(result.data) + it.videos)
                    }

                is AppResult.Failure -> _uiState.update { it.copy(adding = false, addError = result.error.toLibraryError()) }
            }
        }
    }

    fun clearAddResult() = _uiState.update { it.copy(addError = null, justAdded = null) }

    fun remove(video: Video) {
        // Gone from the screen at once; a failed delete brings it back on the next refresh.
        _uiState.update { it.copy(videos = it.videos - video) }
        viewModelScope.launch {
            downloadsManager.remove(video.id)
            if (repository.remove(video.id) is AppResult.Failure) load()
        }
    }

    /** Remembers where the viewer stopped. Outlives the player screen that calls it. */
    fun savePosition(video: Video, positionMs: Long, durationMs: Long?) {
        _uiState.update { state ->
            state.copy(
                videos =
                state.videos.map {
                    if (it.id == video.id) it.copy(positionMs = positionMs, durationMs = durationMs ?: it.durationMs) else it
                },
            )
        }
        viewModelScope.launch { repository.savePosition(video.id, positionMs, durationMs) }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 4_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_QUERY_LENGTH = 60
    }
}

fun AppError.toLibraryError(): LibraryError = when (this) {
    is AppError.Network -> LibraryError.Unreachable

    is AppError.Unknown -> LibraryError.Message("")

    is AppError.Api ->
        when (code) {
            "URL_NOT_ALLOWED", "VALIDATION_FAILED" -> LibraryError.InvalidLink
            "DUPLICATE_VIDEO" -> LibraryError.AlreadySaved
            "ENTITLEMENT_LIMIT" -> LibraryError.LimitReached
            else -> LibraryError.Message(message)
        }
}
