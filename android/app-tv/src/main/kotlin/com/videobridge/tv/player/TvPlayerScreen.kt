package com.videobridge.tv.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.videobridge.core.model.Video
import com.videobridge.core.player.PlayerFactory
import com.videobridge.tv.R
import kotlinx.coroutines.delay

const val TV_PLAYER_ERROR_TAG = "tv_player_error"

private const val SAVE_INTERVAL_MS = 15_000L

// Starting again from the very beginning or the very end of a video is not a useful "resume".
private const val MIN_RESUME_MS = 10_000L
private const val END_MARGIN_MS = 15_000L

/** Where to start: the saved position, unless it is near the start or the end. */
fun resumePosition(video: Video): Long {
    val duration = video.durationMs
    val nearEnd = duration != null && video.positionMs > duration - END_MARGIN_MS
    return if (video.positionMs < MIN_RESUME_MS || nearEnd) 0 else video.positionMs
}

/**
 * Plays one video full screen, straight from its source (never through the backend). The
 * standard Media3 controls handle the remote: OK shows them and plays/pauses, Left/Right seek.
 * Where the viewer stopped is saved every 15 seconds and on leaving. Back returns to the library.
 */
// The buffering spinner setting is still marked unstable in Media3; the rest used here is stable.
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    video: Video,
    /** The link, or the downloaded file. */
    uri: String,
    playerFactory: PlayerFactory,
    onSavePosition: (positionMs: Long, durationMs: Long?) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val player =
        remember(video.id, uri) {
            playerFactory.create(context).apply {
                setMediaItem(MediaItem.fromUri(uri), resumePosition(video))
                playWhenReady = true
                prepare()
            }
        }

    fun save() {
        val position = player.currentPosition
        // Nothing is saved for a video that never started (wrong link, unsupported format).
        if (position > 0 && !failed) onSavePosition(position, player.duration.takeIf { it != C.TIME_UNSET && it > 0 })
    }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failed = true
                }
            }
        player.addListener(listener)
        onDispose {
            save()
            player.removeListener(listener)
            // Released at once: low-memory TVs cannot afford a player lingering in the background.
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(SAVE_INTERVAL_MS)
            if (player.isPlaying) save()
        }
    }
    BackHandler(onBack = onExit)

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(text = video.title, fontSize = 24.sp, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.player_error),
                    fontSize = 20.sp,
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(620.dp).padding(horizontal = 16.dp).testTag(TV_PLAYER_ERROR_TAG),
                )
                Spacer(Modifier.height(12.dp))
                Text(text = stringResource(R.string.player_error_back), fontSize = 18.sp, color = Color(0xFFB8BEC9))
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                },
                onRelease = { it.player = null },
            )
        }
    }
}
