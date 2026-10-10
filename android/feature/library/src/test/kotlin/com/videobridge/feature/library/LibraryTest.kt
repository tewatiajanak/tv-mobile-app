package com.videobridge.feature.library

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.downloads.DownloadStart
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.data.downloads.Downloads
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.data.videos.VideosRepository
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.datastore.DeviceSettings
import com.videobridge.core.datastore.DownloadMode
import com.videobridge.core.datastore.DownloaderChoice
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.Video
import com.videobridge.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareTextParserTest {
    @Test
    fun `finds the link inside a WhatsApp-style message and drops trailing punctuation`() {
        assertEquals(
            listOf("https://cdn.example.com/a/movie.mp4?sig=a%2Bb"),
            ShareTextParser.extractUrls("Watch this!! https://cdn.example.com/a/movie.mp4?sig=a%2Bb. 😀"),
        )
        assertEquals(listOf("http://192.168.1.10:8080/m.mkv"), ShareTextParser.extractUrls("(http://192.168.1.10:8080/m.mkv)"))
    }

    @Test
    fun `returns every distinct link in order`() {
        val text = "one https://a.example/1.mp4 two HTTPS://b.example/2.mp4\nagain https://a.example/1.mp4"

        assertEquals(listOf("https://a.example/1.mp4", "HTTPS://b.example/2.mp4"), ShareTextParser.extractUrls(text))
    }

    @Test
    fun `ignores text without a web link`() {
        listOf(null, "", "no link here", "javascript:alert(1)", "file:///sdcard/a.mp4", "https://").forEach {
            assertTrue(ShareTextParser.extractUrls(it).isEmpty())
        }
    }

    @Test
    fun `falls back to the subject when the text has no link`() {
        assertEquals("https://x.example/v.mp4", ShareTextParser.firstUrl("My video", "https://x.example/v.mp4"))
        assertNull(ShareTextParser.firstUrl("nothing", "nothing"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeVideosRepository : VideosRepository {
        var stored = listOf(Video("v1", "First", "https://x.example/1.mp4", "x.example"))
        var listFails = false
        var addResult: AppResult<Video>? = null
        var removeFails = false
        var lists = 0
        val removed = mutableListOf<String>()

        override suspend fun list(): AppResult<List<Video>> {
            lists++
            return if (listFails) AppResult.Failure(AppError.Network) else AppResult.Success(stored)
        }

        override suspend fun add(url: String, title: String?): AppResult<Video> =
            addResult ?: AppResult.Success(Video("new", title ?: "New", url, "x.example"))

        override suspend fun remove(id: String): AppResult<Unit> {
            removed += id
            return if (removeFails) AppResult.Failure(AppError.Network) else AppResult.Success(Unit)
        }

        override suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?): AppResult<Unit> = AppResult.Success(Unit)
    }

    private val repository = FakeVideosRepository()
    private class FakeDownloads : Downloads {
        val started = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val paused = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        var startResult = DownloadStart.STARTED

        override fun storageOptions() =
            listOf(StorageOption("/internal", isRemovable = false, isConnected = true, freeBytes = 10, totalBytes = 20))

        override suspend fun start(video: Video): DownloadStart {
            started += video.id
            return startResult
        }

        override suspend fun remove(videoId: String) {
            removed += videoId
        }

        override suspend fun pause(videoId: String) {
            paused += videoId
        }

        override suspend fun resume(videoId: String) {
            resumed += videoId
        }

        override fun statuses() = MutableStateFlow(mapOf("v1" to DownloadStatus.Running(0.5f)))
    }

    private class FakeSettings : DeviceSettings {
        override val settings = MutableStateFlow(AppSettings())

        override suspend fun setPlayer(value: PlayerChoice, packageName: String?) =
            settings.update { it.copy(player = value, playerPackage = packageName) }

        override suspend fun setDownloader(value: DownloaderChoice, packageName: String?) =
            settings.update { it.copy(downloader = value, downloaderPackage = packageName) }

        override suspend fun setDownloadMode(value: DownloadMode) = settings.update { it.copy(downloadMode = value) }

        override suspend fun setStorageId(value: String) = settings.update { it.copy(storageId = value) }
    }

    private val downloads = FakeDownloads()

    // Built on first use, after MainDispatcherRule has replaced Dispatchers.Main: the view model
    // starts collecting settings in its constructor.
    private val viewModel by lazy {
        LibraryViewModel(
            repository,
            downloads,
            FakeSettings(),
            { listOf(VideoApp("org.videolan.vlc", "VLC")) },
            { listOf(VideoApp("com.dv.adm", "ADM")) },
        )
    }

    @Test
    fun `loads the library, and keeps the last list when a refresh fails`() {
        assertTrue(viewModel.uiState.value.loading)
        viewModel.refresh()
        assertEquals(listOf("First"), viewModel.uiState.value.videos.map { it.title })
        assertFalse(viewModel.uiState.value.loading)

        repository.listFails = true
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.offline)
        assertEquals(1, viewModel.uiState.value.videos.size)
    }

    @Test
    fun `keepFresh picks up a link saved on another device`() = runTest {
        val job = launch { viewModel.keepFresh(4_000) }
        runCurrent()
        assertEquals(1, viewModel.uiState.value.videos.size)

        repository.stored = listOf(Video("v2", "From the phone", "https://x.example/2.mp4", "x.example")) + repository.stored
        advanceTimeBy(4_001)

        assertEquals("From the phone", viewModel.uiState.value.videos.first().title)
        job.cancel()
    }

    @Test
    fun `adding puts the new video on top and reports it`() {
        viewModel.refresh()

        viewModel.add(" https://x.example/2.mp4 ", "Second")

        assertEquals(listOf("Second", "First"), viewModel.uiState.value.videos.map { it.title })
        assertEquals("Second", viewModel.uiState.value.justAdded?.title)
        viewModel.clearAddResult()
        assertNull(viewModel.uiState.value.justAdded)
    }

    @Test
    fun `each way saving can fail has its own message`() {
        val cases =
            listOf(
                AppError.Network to LibraryError.Unreachable,
                AppError.Api("URL_NOT_ALLOWED", "x", 400) to LibraryError.InvalidLink,
                AppError.Api("DUPLICATE_VIDEO", "x", 409) to LibraryError.AlreadySaved,
                AppError.Api("ENTITLEMENT_LIMIT", "x", 403) to LibraryError.LimitReached,
            )
        cases.forEach { (failure, expected) ->
            repository.addResult = AppResult.Failure(failure)
            viewModel.add("https://x.example/2.mp4")
            assertEquals(expected, viewModel.uiState.value.addError)
            assertNull(viewModel.uiState.value.justAdded)
        }
    }

    @Test
    fun `nothing is sent for an empty link`() {
        viewModel.add("   ")

        assertFalse(viewModel.uiState.value.adding)
        assertNull(viewModel.uiState.value.addError)
    }

    @Test
    fun `removing hides the video at once, and a failed removal brings it back`() {
        viewModel.refresh()
        val video = viewModel.uiState.value.videos.single()

        repository.removeFails = true
        viewModel.remove(video)

        assertEquals(listOf("v1"), repository.removed)
        assertEquals(listOf(video), viewModel.uiState.value.videos)

        repository.removeFails = false
        repository.stored = emptyList()
        viewModel.remove(video)
        assertTrue(viewModel.uiState.value.videos.isEmpty())
    }

    @Test
    fun `search matches every word of the name in any order, ignoring case`() {
        repository.stored =
            listOf(
                Video("a", "Goa Trip Day 1", "https://x.example/a.mp4", "x.example"),
                Video("b", "Wedding highlights", "https://x.example/b.mp4", "x.example"),
                Video("c", "Trip to Manali", "https://x.example/c.mp4", "x.example"),
            )
        viewModel.refresh()

        viewModel.onQueryChange("trip")
        assertEquals(listOf("a", "c"), viewModel.uiState.value.visibleVideos.map { it.id })
        viewModel.onQueryChange("  day GOA ")
        assertEquals(listOf("a"), viewModel.uiState.value.visibleVideos.map { it.id })
        viewModel.onQueryChange("x.example")
        assertTrue("links are not searched", viewModel.uiState.value.visibleVideos.isEmpty())
        viewModel.onQueryChange("")
        assertEquals(3, viewModel.uiState.value.visibleVideos.size)
    }

    @Test
    fun `settings changes are kept, and a download request reports its outcome once`() {
        viewModel.setPlayer(PlayerChoice.OTHER_APP)
        viewModel.setDownloadMode(DownloadMode.ASK)
        viewModel.setStorage(viewModel.storageOptions().single())
        assertEquals(AppSettings(PlayerChoice.OTHER_APP, null, DownloadMode.ASK, "/internal"), viewModel.settings.value)

        assertEquals("VLC", viewModel.videoApps().single().label)
        viewModel.setPlayer(PlayerChoice.OTHER_APP, "org.videolan.vlc")
        assertEquals("org.videolan.vlc", viewModel.settings.value.playerPackage)

        viewModel.refresh()
        val video = viewModel.uiState.value.videos.single()
        downloads.startResult = DownloadStart.STORAGE_NOT_CONNECTED
        viewModel.download(video)
        assertEquals(listOf("v1"), downloads.started)
        assertEquals(DownloadStart.STORAGE_NOT_CONNECTED, viewModel.downloadMessage.value)
        viewModel.downloadMessageShown()
        assertNull(viewModel.downloadMessage.value)
    }

    @Test
    fun `with another downloader chosen, a download is handed to that app instead of started here`() {
        viewModel.refresh()
        val video = viewModel.uiState.value.videos.single()
        assertEquals("ADM", viewModel.downloadApps().single().label)

        viewModel.setDownloader(DownloaderChoice.OTHER_APP, "com.dv.adm")
        viewModel.setDownloadMode(DownloadMode.ALWAYS)
        viewModel.download(video)

        assertEquals(ExternalDownload(video.sourceUrl, "com.dv.adm"), viewModel.externalDownload.value)
        assertEquals(emptyList<String>(), downloads.started)
        // Opening a video must not jump to the other app every time.
        assertEquals(false, viewModel.settings.value.downloadsWhenOpened)
        viewModel.externalDownloadShown()
        assertNull(viewModel.externalDownload.value)

        viewModel.setDownloader(DownloaderChoice.DEKHO)
        viewModel.download(video)
        assertEquals(listOf("v1"), downloads.started)
        assertEquals(true, viewModel.settings.value.downloadsWhenOpened)
    }

    @Test
    fun `removing a video also removes its download`() {
        viewModel.refresh()

        viewModel.remove(viewModel.uiState.value.videos.single())

        assertEquals(listOf("v1"), downloads.removed)
    }

    @Test
    fun `a download can be stopped and continued`() {
        viewModel.refresh()
        val video = viewModel.uiState.value.videos.single()

        viewModel.pauseDownload(video)
        viewModel.resumeDownload(video)

        assertEquals(listOf("v1"), downloads.paused)
        assertEquals(listOf("v1"), downloads.resumed)
    }
}
