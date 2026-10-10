package com.videobridge.tv.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Surface
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.data.downloads.StorageOption
import com.videobridge.core.data.downloads.VideoApp
import com.videobridge.core.datastore.AppSettings
import com.videobridge.core.datastore.DownloaderChoice
import com.videobridge.core.datastore.PlayerChoice
import com.videobridge.core.model.Video
import com.videobridge.core.tvdesignsystem.TV_KEYBOARD_TAG
import com.videobridge.core.tvdesignsystem.VideoBridgeTvTheme
import com.videobridge.core.tvdesignsystem.tvKeyTag
import com.videobridge.feature.auth.AuthMode
import com.videobridge.feature.auth.AuthUiState
import com.videobridge.feature.auth.TvPairingUiState
import com.videobridge.feature.library.LibraryUiState
import com.videobridge.tv.home.TV_ACTION_DOWNLOAD_TAG
import com.videobridge.tv.home.TV_ACTION_PLAY_TAG
import com.videobridge.tv.home.TV_HOME_GREETING_TAG
import com.videobridge.tv.home.TV_MENU_TAG
import com.videobridge.tv.home.TV_SEARCH_TAG
import com.videobridge.tv.home.TvHomeScreen
import com.videobridge.tv.home.tvVideoTag
import com.videobridge.tv.pairing.TV_PAIR_CODE_TAG
import com.videobridge.tv.pairing.TV_PAIR_PASSWORD_TAG
import com.videobridge.tv.pairing.TV_PAIR_STATUS_TAG
import com.videobridge.tv.pairing.TvPairingScreen
import com.videobridge.tv.player.resumePosition
import com.videobridge.tv.settings.TV_SETTINGS_PANEL_TAG
import com.videobridge.tv.settings.TV_SETTINGS_SIGN_OUT_CANCEL_TAG
import com.videobridge.tv.settings.TV_SETTINGS_SIGN_OUT_CONFIRM_TAG
import com.videobridge.tv.settings.TV_SETTINGS_SIGN_OUT_TAG
import com.videobridge.tv.settings.TvSettingsScreen
import com.videobridge.tv.settings.tvSettingTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A TV-sized screen: on Robolectric's default phone-sized one the form is clipped.
// Every interaction is a remote-control key press; nothing here uses touch.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class TvAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(AuthUiState())
    private var submits = 0

    private fun show() {
        composeRule.setContent {
            VideoBridgeTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    TvAuthScreen(
                        uiState = state,
                        sessionEnded = false,
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
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) { composeRule.onRoot().performKeyInput { pressKey(key) } }

    private fun keyboard() = composeRule.onNodeWithTag(TV_KEYBOARD_TAG)

    @Test
    fun `the first field has focus, and selecting it opens the keyboard on q`() {
        show()
        composeRule.onNodeWithTag(TV_AUTH_NAME_TAG).assertIsFocused()
        keyboard().assertDoesNotExist()

        press(Key.DirectionCenter)

        keyboard().assertExists()
        composeRule.onNodeWithTag(tvKeyTag("q")).assertIsFocused()
    }

    @Test
    fun `keys type into the field being edited`() {
        show()
        press(Key.DirectionCenter)

        press(Key.DirectionCenter) // q
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // w

        assertEquals("qw", state.name)
    }

    @Test
    fun `shift capitalises one letter, backspace deletes, symbols switches layout`() {
        show()
        press(Key.DirectionCenter)

        press(Key.DirectionDown, 3) // q -> a -> z -> shift
        composeRule.onNodeWithTag(tvKeyTag("shift")).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionUp, 3) // back to the q row, now upper case
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals("Qq", state.name)

        press(Key.DirectionDown, 3)
        press(Key.DirectionRight, 3) // shift -> symbols -> space -> backspace
        composeRule.onNodeWithTag(tvKeyTag("backspace")).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("Q", state.name)

        press(Key.DirectionLeft, 2)
        press(Key.DirectionCenter) // symbols
        composeRule.onNodeWithTag(tvKeyTag("@")).assertExists()
        composeRule.onNodeWithTag(tvKeyTag("q")).assertDoesNotExist()
    }

    @Test
    fun `Down past the last keyboard row closes it and moves to the next field`() {
        show()
        press(Key.DirectionCenter)

        press(Key.DirectionDown, 3) // q row -> a row -> z row -> action row (last)
        keyboard().assertExists()
        press(Key.DirectionDown)

        keyboard().assertDoesNotExist()
        composeRule.onNodeWithTag(TV_AUTH_PHONE_TAG).assertIsFocused()
    }

    @Test
    fun `Up past the first keyboard row closes it and returns to the field, then Up goes to the field above`() {
        show()
        press(Key.DirectionDown) // name -> phone
        composeRule.onNodeWithTag(TV_AUTH_PHONE_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        composeRule.onNodeWithTag(tvKeyTag("1")).assertIsFocused()

        press(Key.DirectionUp) // already on the top row

        keyboard().assertDoesNotExist()
        composeRule.onNodeWithTag(TV_AUTH_PHONE_TAG).assertIsFocused()

        press(Key.DirectionUp)
        composeRule.onNodeWithTag(TV_AUTH_NAME_TAG).assertIsFocused()
    }

    @Test
    fun `the number pad types digits, and Down from the last field lands on the buttons`() {
        state = state.copy(mode = AuthMode.SIGN_IN)
        show()
        composeRule.onNodeWithTag(TV_AUTH_PHONE_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter) // 1
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // 2
        assertEquals("12", state.phone)

        press(Key.DirectionDown, 5) // 1-row -> 4 -> 7 -> + -> Done -> out
        composeRule.onNodeWithTag(TV_AUTH_PASSWORD_TAG).assertIsFocused()

        press(Key.DirectionCenter)
        press(Key.DirectionCenter) // q
        press(Key.DirectionDown, 4) // out of the keyboard below the last field
        keyboard().assertDoesNotExist()
        composeRule.onNodeWithTag(TV_AUTH_SUBMIT_TAG).assertIsFocused().assertIsEnabled()

        press(Key.DirectionCenter)
        assertEquals(1, submits)
    }

    @Test
    fun `Back closes the keyboard and returns to the field`() {
        show()
        press(Key.DirectionCenter)

        press(Key.Back)

        keyboard().assertDoesNotExist()
        composeRule.onNodeWithTag(TV_AUTH_NAME_TAG).assertIsFocused()
    }

    @Test
    fun `Next jumps to the next field with the keyboard still open, Done on the last field lands on the buttons`() {
        state = state.copy(name = "J", phone = "9876543210", password = "1")
        show()
        press(Key.DirectionCenter) // edit name
        press(Key.DirectionDown, 3)
        press(Key.DirectionRight, 4) // shift -> symbols -> space -> backspace -> Next
        composeRule.onNodeWithTag(tvKeyTag("done")).assertIsFocused().assertTextContains("Next")

        press(Key.DirectionCenter) // -> phone, number pad
        keyboard().assertExists()
        composeRule.onNodeWithTag(tvKeyTag("1")).assertIsFocused()

        press(Key.DirectionDown, 4) // 1 -> 4 -> 7 -> + -> Next
        press(Key.DirectionCenter) // -> password, letters
        composeRule.onNodeWithTag(tvKeyTag("q")).assertIsFocused()

        press(Key.DirectionDown, 3)
        press(Key.DirectionRight, 4)
        composeRule.onNodeWithTag(tvKeyTag("done")).assertTextContains("Done")
        press(Key.DirectionCenter)

        keyboard().assertDoesNotExist()
        composeRule.onNodeWithTag(TV_AUTH_SUBMIT_TAG).assertIsFocused()
    }

    @Test
    fun `the action stays disabled until every field is filled`() {
        show()
        composeRule.onNodeWithTag(TV_AUTH_SUBMIT_TAG).assertIsNotEnabled()

        state = state.copy(name = "Janak", phone = "9876543210", password = "1")

        composeRule.onNodeWithTag(TV_AUTH_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun `the Show button reveals and hides the password`() {
        state = state.copy(password = "secret1")
        show()
        composeRule.onNodeWithTag(TV_AUTH_PASSWORD_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(TV_AUTH_PASSWORD_TAG).assertTextContains("•••••••")

        press(Key.DirectionDown, 2) // name -> phone -> password
        press(Key.DirectionRight) // Show
        composeRule.onNodeWithTag(TV_AUTH_EYE_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        composeRule.onNodeWithTag(TV_AUTH_PASSWORD_TAG).assertTextContains("secret1")

        press(Key.DirectionCenter)
        composeRule.onNodeWithTag(TV_AUTH_PASSWORD_TAG).assertTextContains("•••••••")
    }

    @Test
    fun `resume starts from the saved point unless it is at the very start or end`() {
        val video = Video("v", "t", "https://x.example/v.mp4", "x.example", durationMs = 600_000)

        assertEquals(0, resumePosition(video.copy(positionMs = 4_000)))
        assertEquals(240_000, resumePosition(video.copy(positionMs = 240_000)))
        assertEquals(0, resumePosition(video.copy(positionMs = 595_000)))
        assertEquals(50_000, resumePosition(video.copy(positionMs = 50_000, durationMs = null)))
    }

    private fun showHome(
        library: LibraryUiState,
        events: MutableList<String>,
        ask: Boolean = false,
        downloads: Map<String, DownloadStatus> = mapOf("v1" to DownloadStatus.Done("file:///x.mp4")),
    ) {
        composeRule.setContent {
            VideoBridgeTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    TvHomeScreen(
                        displayName = "Janak",
                        library = library,
                        downloads = downloads,
                        askBeforePlaying = ask,
                        onQueryChange = { events += "query|$it" },
                        onPlay = { events += "play|${it.id}" },
                        onDownload = { events += "download|${it.id}" },
                        onPauseDownload = { events += "pause|${it.id}" },
                        onResumeDownload = { events += "resume|${it.id}" },
                        onRemoveDownload = { events += "undownload|${it.id}" },
                        onOpenSettings = { events += "settings" },
                    )
                }
            }
        }
    }

    private val twoVideos =
        LibraryUiState(
            loading = false,
            videos =
            listOf(
                Video("v2", "Newest", "https://x.example/2.mp4", "x.example", 734_003_200, "MP4"),
                Video("v1", "Older", "https://x.example/1.mkv", "x.example", null, "MKV", 30_000, 120_000),
            ),
        )

    @Test
    fun `the library opens on the newest video, shows size and download state, and OK plays`() {
        val events = mutableListOf<String>()
        showHome(twoVideos, events)

        composeRule.onNodeWithTag(TV_HOME_GREETING_TAG).assertTextContains("Hi, Janak")
        composeRule.onNodeWithTag(tvVideoTag("v2")).assertIsFocused()
        composeRule.onNodeWithText("700 MB").assertExists()
        composeRule.onNodeWithText("MP4").assertExists()
        composeRule.onNodeWithText("✓ Downloaded").assertExists()
        composeRule.onNodeWithText("x.example", substring = true).assertDoesNotExist()

        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("play|v1"), events)
    }

    @Test
    fun `the header has search and a menu button instead of Sign out`() {
        val events = mutableListOf<String>()
        showHome(twoVideos, events)
        composeRule.onNodeWithText("Sign out").assertDoesNotExist()

        press(Key.DirectionUp) // from the grid to the header
        press(Key.DirectionRight, 2) // to the right-most button
        composeRule.onNodeWithTag(TV_MENU_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("settings"), events)

        press(Key.DirectionLeft)
        composeRule.onNodeWithTag(TV_SEARCH_TAG).assertIsFocused()
        press(Key.DirectionCenter) // opens the keyboard on q
        press(Key.DirectionCenter)
        assertEquals("query|q", events.last())
    }

    @Test
    fun `when set to ask, OK offers Play or Download for a video that is not downloaded`() {
        val events = mutableListOf<String>()
        showHome(twoVideos, events, ask = true)

        press(Key.DirectionCenter) // v2, not downloaded
        composeRule.onNodeWithTag(TV_ACTION_PLAY_TAG).assertIsFocused()
        press(Key.DirectionDown)
        composeRule.onNodeWithTag(TV_ACTION_DOWNLOAD_TAG).assertIsFocused()
        press(Key.DirectionCenter)

        assertEquals(listOf("download|v2"), events)
        composeRule.onNodeWithTag(TV_ACTION_PLAY_TAG).assertDoesNotExist()
    }

    @Test
    fun `holding OK opens the options without playing - the release of that same press is ignored`() {
        val events = mutableListOf<String>()
        showHome(twoVideos, events, downloads = mapOf("v2" to DownloadStatus.Running(0.4f)))

        // Hold OK: one press, then auto-repeats, then the release.
        composeRule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(700)
        }
        composeRule.onNodeWithTag(TV_ACTION_PLAY_TAG).assertIsFocused()
        composeRule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }

        assertEquals(emptyList<String>(), events)
        composeRule.onNodeWithTag(TV_ACTION_PLAY_TAG).assertIsFocused()

        // A running download offers Stop, and there is time to choose it.
        press(Key.DirectionDown)
        composeRule.onNodeWithTag(TV_ACTION_DOWNLOAD_TAG).assertIsFocused().assertTextContains("Stop download")
        press(Key.DirectionCenter)
        assertEquals(listOf("pause|v2"), events)
    }

    @Test
    fun `a stopped download offers Resume and Delete`() {
        val events = mutableListOf<String>()
        showHome(twoVideos, events, downloads = mapOf("v2" to DownloadStatus.Paused(0.4f)))
        composeRule.onNodeWithText("Stopped at 40%", substring = true).assertExists()

        composeRule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(700)
            keyUp(Key.DirectionCenter)
        }
        composeRule.onNodeWithTag(TV_ACTION_DOWNLOAD_TAG).assertTextContains("Resume download")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf("resume|v2"), events)
    }

    private fun showSettings(settings: AppSettings, events: MutableList<String>) {
        val storage =
            listOf(
                StorageOption(
                    "/internal",
                    isRemovable = false,
                    isConnected = true,
                    freeBytes = 5L * 1024 * 1024 * 1024,
                    totalBytes =
                    8L * 1024 * 1024 * 1024,
                ),
                StorageOption("/usb", isRemovable = true, isConnected = false, freeBytes = 0, totalBytes = 0),
            )
        composeRule.setContent {
            VideoBridgeTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    TvSettingsScreen(
                        settings = settings,
                        storage = storage,
                        apps = listOf(VideoApp("org.videolan.vlc", "VLC")),
                        downloadApps = listOf(VideoApp("com.esaba.downloader", "Downloader")),
                        phoneMasked = "+91******3210",
                        onPlayer = { choice, app -> events += listOfNotNull(choice.name, app).joinToString("|") },
                        onDownloader = { choice, app -> events += listOfNotNull("downloader", choice.name, app).joinToString("|") },
                        onDownloadMode = { events += it.name },
                        onStorage = { events += it.id },
                        onSignOut = { events += "signout" },
                        onClose = { events += "close" },
                    )
                }
            }
        }
    }

    @Test
    fun `settings open as a side panel with the current choices marked, and block an unplugged drive`() {
        val events = mutableListOf<String>()
        showSettings(AppSettings(), events)

        composeRule.onNodeWithTag(TV_SETTINGS_PANEL_TAG).assertExists()
        composeRule.onNodeWithTag(tvSettingTag("DEKHO")).assertIsFocused().assertTextContains("●")
        composeRule.onNodeWithTag(tvSettingTag("OTHER_APP")).assertTextContains("○")
        composeRule.onNodeWithText("5.0 GB free of 8.0 GB").assertExists()
        composeRule.onNodeWithTag(tvSettingTag("storage_1")).assertIsNotEnabled().assertTextContains("Not connected")
        // The installed apps are offered only once "Another video app" is the choice.
        composeRule.onNodeWithText("VLC").assertDoesNotExist()

        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf("OTHER_APP"), events)
    }

    @Test
    fun `with another app chosen, the installed video apps are listed and one can be picked`() {
        val events = mutableListOf<String>()
        showSettings(AppSettings(player = PlayerChoice.OTHER_APP), events)

        composeRule.onNodeWithTag(tvSettingTag("app_ask")).assertTextContains("●")
        press(Key.DirectionDown, 3) // Dekho -> Another app -> Ask every time -> VLC
        composeRule.onNodeWithTag(tvSettingTag("app_org.videolan.vlc")).assertIsFocused()
        press(Key.DirectionCenter)

        assertEquals(listOf("OTHER_APP|org.videolan.vlc"), events)
    }

    @Test
    fun `with another downloader chosen, the apps that can take a link are listed and one can be picked`() {
        val events = mutableListOf<String>()
        showSettings(AppSettings(downloader = DownloaderChoice.OTHER_APP), events)

        composeRule.onNodeWithTag(tvSettingTag("downloader_DEKHO")).assertTextContains("○")
        composeRule.onNodeWithTag(tvSettingTag("downloader_ask")).assertTextContains("●")
        composeRule.onNodeWithTag(tvSettingTag("downloader_app_com.esaba.downloader")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("downloader|OTHER_APP|com.esaba.downloader"), events)
    }

    @Test
    fun `signing out from settings needs a second, deliberate press`() {
        val events = mutableListOf<String>()
        showSettings(AppSettings(), events)

        composeRule.onNodeWithTag(TV_SETTINGS_SIGN_OUT_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(TV_SETTINGS_SIGN_OUT_CANCEL_TAG).assertIsFocused()
        press(Key.DirectionCenter) // an accidental second OK cancels
        assertEquals(emptyList<String>(), events)

        composeRule.onNodeWithTag(TV_SETTINGS_SIGN_OUT_TAG).performSemanticsAction(SemanticsActions.OnClick)
        press(Key.DirectionDown)
        composeRule.onNodeWithTag(TV_SETTINGS_SIGN_OUT_CONFIRM_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("signout"), events)
    }

    @Test
    fun `the pairing screen shows the code, the countdown and what the phone is doing`() {
        var pairing by mutableStateOf<TvPairingUiState>(TvPairingUiState.Loading)
        var usePassword = 0
        composeRule.setContent {
            VideoBridgeTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    TvPairingScreen(uiState = pairing, onUsePassword = { usePassword++ })
                }
            }
        }
        composeRule.onNodeWithTag(TV_PAIR_STATUS_TAG).assertTextContains("Getting a code…")

        pairing = TvPairingUiState.ShowingCode(code = "0427", qrPayload = "videobridge://pair?c=0427", secondsLeft = 252)
        composeRule.onNodeWithTag(TV_PAIR_CODE_TAG).assertTextContains("0427")
        composeRule.onNodeWithTag(TV_PAIR_STATUS_TAG).assertTextContains("New code in 4:12")

        pairing = (pairing as TvPairingUiState.ShowingCode).copy(confirmOnPhone = true)
        composeRule.onNodeWithTag(TV_PAIR_STATUS_TAG).assertTextContains("tap Connect on your phone", substring = true)

        composeRule.onNodeWithTag(TV_PAIR_PASSWORD_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, usePassword)
    }
}
