package com.videobridge.phone.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.designsystem.VideoBridgeTheme
import com.videobridge.core.model.Video
import com.videobridge.feature.auth.AuthError
import com.videobridge.feature.auth.AuthMode
import com.videobridge.feature.auth.AuthUiState
import com.videobridge.feature.library.LibraryError
import com.videobridge.feature.library.LibraryUiState
import com.videobridge.phone.home.ADD_ERROR_TAG
import com.videobridge.phone.home.ADD_NAME_TAG
import com.videobridge.phone.home.ADD_SAVE_TAG
import com.videobridge.phone.home.ADD_URL_TAG
import com.videobridge.phone.home.HOME_ADD_TAG
import com.videobridge.phone.home.HOME_GREETING_TAG
import com.videobridge.phone.home.HOME_MENU_TAG
import com.videobridge.phone.home.HOME_SEARCH_TAG
import com.videobridge.phone.home.HomeScreen
import com.videobridge.phone.home.videoMenuTag
import com.videobridge.phone.settings.SettingsScreen
import com.videobridge.phone.settings.settingTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(AuthUiState())
    private var submits = 0

    private fun show(sessionEnded: Boolean = false) {
        composeRule.setContent {
            VideoBridgeTheme {
                AuthScreen(
                    uiState = state,
                    sessionEnded = sessionEnded,
                    onNameChange = { state = state.copy(name = it) },
                    onPhoneChange = { state = state.copy(phone = it) },
                    onPasswordChange = { state = state.copy(password = it) },
                    onTogglePasswordVisible = { state = state.copy(passwordVisible = !state.passwordVisible) },
                    onModeChange = { state = state.copy(mode = it) },
                    onSubmit = { submits++ },
                )
            }
        }
    }

    @Test
    fun `Create account stays disabled until name, number and password are all filled`() {
        show()
        composeRule.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag(AUTH_NAME_TAG).performTextInput("Janak")
        composeRule.onNodeWithTag(AUTH_PHONE_TAG).performTextInput("9876543210")
        composeRule.onNodeWithTag(AUTH_SUBMIT_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag(AUTH_PASSWORD_TAG).performTextInput("1")
        composeRule.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, submits)
    }

    @Test
    fun `the eye icon reveals and hides the password`() {
        state = state.copy(password = "secret1")
        show()
        composeRule.onNodeWithTag(AUTH_PASSWORD_TAG).assertTextContains("•••••••")

        composeRule.onNodeWithContentDescription("Show password").performClick()
        composeRule.onNodeWithTag(AUTH_PASSWORD_TAG).assertTextContains("secret1")

        composeRule.onNodeWithContentDescription("Hide password").performClick()
        composeRule.onNodeWithTag(AUTH_PASSWORD_TAG).assertTextContains("•••••••")
    }

    @Test
    fun `sign-in mode has no name field`() {
        show()
        composeRule.onNodeWithText("I already have an account").performScrollTo().performClick()

        assertEquals(AuthMode.SIGN_IN, state.mode)
        composeRule.onNodeWithTag(AUTH_NAME_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(AUTH_PHONE_TAG).assertIsDisplayed()
    }

    @Test
    fun `errors are shown in words`() {
        state = state.copy(error = AuthError.WrongCredentials)
        show()

        composeRule.onNodeWithTag(AUTH_ERROR_TAG).assertTextContains("Wrong mobile number or password.")
    }

    private fun showHome(
        library: LibraryUiState,
        chosen: MutableList<String> = mutableListOf(),
        downloads: Map<String, DownloadStatus> = emptyMap(),
    ) {
        composeRule.setContent {
            VideoBridgeTheme {
                HomeScreen(
                    displayName = "Janak",
                    library = library,
                    downloads = downloads,
                    onQueryChange = { chosen += "query|$it" },
                    onAdd = { name, url -> chosen += "add|$name|$url" },
                    onAddResultShown = {},
                    onOpen = { chosen += "open|${it.id}" },
                    onDownload = { chosen += "download|${it.id}" },
                    onPauseDownload = { chosen += "pause|${it.id}" },
                    onResumeDownload = { chosen += "resume|${it.id}" },
                    onRemoveDownload = { chosen += "undownload|${it.id}" },
                    onRemove = { chosen += "remove|${it.id}" },
                    onConnectTv = { chosen += "connect" },
                    onDevices = { chosen += "devices" },
                    onSettings = { chosen += "settings" },
                    onSignOut = { chosen += "signout" },
                )
            }
        }
    }

    private val videos =
        listOf(
            Video("v1", "My Holiday 2024", "https://cdn.example.com/a.mp4", "cdn.example.com", 734_003_200, "MP4", 30_000, 60_000),
            Video("v2", "Second", "https://x.example/b.mkv", "x.example", null, "MKV"),
        )

    @Test
    fun `home shows the name at the top and keeps account actions in the menu`() {
        val chosen = mutableListOf<String>()
        showHome(LibraryUiState(loading = false), chosen)
        composeRule.onNodeWithTag(HOME_GREETING_TAG).assertTextContains("Hi, Janak")
        composeRule.onNodeWithText("No videos yet").assertIsDisplayed()
        composeRule.onNodeWithText("Connect a TV").assertDoesNotExist()

        composeRule.onNodeWithTag(HOME_MENU_TAG).performClick()
        composeRule.onNodeWithText("My devices").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(listOf("settings"), chosen)
    }

    @Test
    fun `cards show the name, format, size and progress - never the link - and open on tap`() {
        val chosen = mutableListOf<String>()
        showHome(LibraryUiState(loading = false, videos = videos), chosen, mapOf("v2" to DownloadStatus.Running(0.4f)))

        composeRule.onNodeWithText("700 MB  ·  Watched 50%").assertIsDisplayed()
        composeRule.onNodeWithText("MP4").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading 40%").assertIsDisplayed()
        composeRule.onNodeWithText("cdn.example.com", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("My Holiday 2024").performClick()
        assertEquals(listOf("open|v1"), chosen)
    }

    @Test
    fun `the card menu offers what fits the download - start, stop, resume, delete - and asks before removing the video`() {
        val chosen = mutableListOf<String>()
        var downloads by mutableStateOf<Map<String, DownloadStatus>>(emptyMap())
        composeRule.setContent {
            VideoBridgeTheme {
                HomeScreen(
                    displayName = "Janak",
                    library = LibraryUiState(loading = false, videos = videos),
                    downloads = downloads,
                    onQueryChange = {},
                    onAdd = { _, _ -> },
                    onAddResultShown = {},
                    onOpen = {},
                    onDownload = { chosen += "download|${it.id}" },
                    onPauseDownload = { chosen += "pause|${it.id}" },
                    onResumeDownload = { chosen += "resume|${it.id}" },
                    onRemoveDownload = { chosen += "undownload|${it.id}" },
                    onRemove = { chosen += "remove|${it.id}" },
                    onConnectTv = {},
                    onDevices = {},
                    onSettings = {},
                    onSignOut = {},
                )
            }
        }

        fun menu(item: String) {
            composeRule.onNodeWithTag(videoMenuTag("v1")).performClick()
            composeRule.onNodeWithText(item).performClick()
        }
        menu("Download")
        downloads = mapOf("v1" to DownloadStatus.Running(0.3f))
        composeRule.onNodeWithTag(videoMenuTag("v1")).performClick()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithText("Stop download").performClick()
        downloads = mapOf("v1" to DownloadStatus.Paused(0.3f))
        composeRule.onNodeWithText("Stopped at 30%", substring = true).assertIsDisplayed()
        menu("Resume download")
        downloads = mapOf("v1" to DownloadStatus.Done("file:///x.mp4"))
        composeRule.onNodeWithTag(videoMenuTag("v1")).performClick()
        composeRule.onNodeWithText("Stop download").assertDoesNotExist()
        composeRule.onNodeWithText("Delete download").performClick()
        assertEquals(listOf("download|v1", "pause|v1", "resume|v1", "undownload|v1"), chosen)

        menu("Remove")
        composeRule.onNodeWithText("Remove this video?").assertIsDisplayed()
        assertEquals(4, chosen.size)
        composeRule.onAllNodesWithText("Remove").onLast().performClick()
        assertEquals("remove|v1", chosen.last())
    }

    @Test
    fun `search filters by name and says when nothing matches`() {
        val chosen = mutableListOf<String>()
        showHome(LibraryUiState(loading = false, videos = videos, query = "zzz"), chosen)

        composeRule.onNodeWithText("No video matches “zzz”.").assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("a")
        assertTrue(chosen.single().startsWith("query|"))
    }

    @Test
    fun `adding asks for the name first and needs both name and link`() {
        val chosen = mutableListOf<String>()
        showHome(LibraryUiState(loading = false, addError = LibraryError.AlreadySaved), chosen)

        composeRule.onNodeWithTag(HOME_ADD_TAG).performClick()
        composeRule.onNodeWithTag(ADD_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(ADD_URL_TAG).performTextInput("https://x.example/v.mp4")
        composeRule.onNodeWithTag(ADD_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(ADD_NAME_TAG).performTextInput("Goa trip")
        composeRule.onNodeWithTag(ADD_SAVE_TAG).assertIsEnabled().performClick()

        assertEquals(listOf("add|Goa trip|https://x.example/v.mp4"), chosen)
        composeRule.onNodeWithTag(ADD_ERROR_TAG).assertTextContains("This link is already in your library.")
    }

    @Test
    fun `settings show the current choices and report a new one`() {
        val chosen = mutableListOf<String>()
        val storage =
            listOf(
                StorageOption(
                    "/internal",
                    isRemovable = false,
                    isConnected = true,
                    freeBytes = 5L * 1024 * 1024 * 1024,
                    totalBytes =
                    64L * 1024 * 1024 * 1024,
                ),
                StorageOption("/usb", isRemovable = true, isConnected = false, freeBytes = 0, totalBytes = 0),
            )
        composeRule.setContent {
            VideoBridgeTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    storage = storage,
                    apps = listOf(VideoApp("org.videolan.vlc", "VLC")),
                    downloadApps = listOf(VideoApp("com.dv.adm", "ADM")),
                    onPlayer = { choice, app -> chosen += listOfNotNull(choice.name, app).joinToString("|") },
                    onDownloader = { choice, app -> chosen += listOfNotNull("downloader", choice.name, app).joinToString("|") },
                    onDownloadMode = { chosen += it.name },
                    onStorage = { chosen += it.id },
                    onClose = {},
                )
            }
        }
        composeRule.onNodeWithTag(settingTag("DEKHO")).assertIsSelected()
        composeRule.onNodeWithTag(settingTag("MANUAL")).assertIsSelected()
        composeRule.onNodeWithText("5.0 GB free of 64.0 GB").assertExists()
        composeRule.onNodeWithText("Not connected").assertExists()

        composeRule.onNodeWithTag(settingTag("OTHER_APP")).performClick()
        composeRule.onNodeWithTag(settingTag("downloader_DEKHO")).performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag(settingTag("downloader_OTHER_APP")).performScrollTo().performClick()
        composeRule.onNodeWithTag(settingTag("ASK")).performScrollTo().performClick()
        // A drive that is not plugged in cannot be chosen.
        composeRule.onNodeWithTag(settingTag("storage_1")).performScrollTo().assertIsNotEnabled()

        assertEquals(listOf("OTHER_APP", "downloader|OTHER_APP", "ASK"), chosen)
    }
}
