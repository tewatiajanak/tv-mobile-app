package com.videobridge.phone.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videobridge.core.model.Video
import com.videobridge.core.player.PlayerFactory
import com.videobridge.phone.R
import kotlinx.coroutines.delay

private const val SAVE_INTERVAL_MS = 15_000L
private const val MIN_RESUME_MS = 10_000L
private const val END_MARGIN_MS = 15_000L

/** Where to start: the saved position, unless it is near the start or the end. */
fun resumePosition(video: Video): Long {
    val duration = video.durationMs
    val nearEnd = duration != null && video.positionMs > duration - END_MARGIN_MS
    return if (video.positionMs < MIN_RESUME_MS || nearEnd) 0 else video.positionMs
}

/**
 * Dekho's own player on the phone. Plays [uri] (the link, or the downloaded file) straight from
 * where it lives, resumes where the viewer stopped, and saves that point every 15 seconds and
 * on leaving. Back returns to the library.
 */
// The buffering spinner setting is still marked unstable in Media3; the rest used here is stable.
@OptIn(UnstableApi::class)
@Composable
fun PhonePlayerScreen(
    video: Video,
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(text = stringResource(R.string.player_error), color = Color(0xFFFFB4AB), textAlign = TextAlign.Center)
                TextButton(onClick = onExit) { Text(stringResource(R.string.action_close)) }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    }
                },
                onRelease = { it.player = null },
            )
        }
    }
}
