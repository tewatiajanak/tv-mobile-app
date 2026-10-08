package com.videobridge.phone.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.model.Video
import com.videobridge.core.model.formatBytes
import com.videobridge.phone.R

/** Test tag of the ⋮ button on one video's card. */
fun videoMenuTag(id: String) = "video_menu_$id"

private const val PERCENT = 100
private const val POSTER_RATIO = 16f / 9f
private val ART = listOf(Color(0xFF0F766E), Color(0xFF1D4ED8), Color(0xFFB45309), Color(0xFF9D174D), Color(0xFF4338CA), Color(0xFF047857))
private val PROGRESS = Color(0xFFE50914)

@Composable
fun downloadText(status: DownloadStatus): String? = when (status) {
    DownloadStatus.None -> null

    is DownloadStatus.Running ->
        status.fraction?.let { stringResource(R.string.download_status_running, (it * PERCENT).toInt()) }
            ?: stringResource(R.string.download_status_running_unknown)

    is DownloadStatus.Paused ->
        status.fraction?.let { stringResource(R.string.download_status_paused_at, (it * PERCENT).toInt()) }
            ?: stringResource(R.string.download_status_paused)

    DownloadStatus.Failed -> stringResource(R.string.download_status_failed)

    is DownloadStatus.Done -> "✓ " + stringResource(R.string.download_status_done)
}

/**
 * The artwork of a card, in the style of streaming apps: the picture the link advertises when
 * there is one, otherwise a coloured backdrop with the video's initial. A frame is never
 * grabbed from the video itself.
 */
@Composable
fun Poster(video: Video, modifier: Modifier = Modifier) {
    val base = ART[(video.id.hashCode() and Int.MAX_VALUE) % ART.size]
    Box(modifier = modifier.background(Brush.linearGradient(listOf(base, Color(0xFF0B1220)))), contentAlignment = Alignment.Center) {
        Text(
            text = video.title.trim().take(1).uppercase(),
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.18f),
        )
        if (video.thumbnailUrl != null) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A poster card: artwork on top with a play button, format badge and a thin progress line
 * (red = watched, as on streaming apps); the name and details below; ⋮ for actions.
 */
@Composable
fun VideoCard(
    video: Video,
    download: DownloadStatus,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    fun choose(action: () -> Unit) {
        menuOpen = false
        action()
    }
    Column(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO).clip(RoundedCornerShape(12.dp))) {
            Poster(video, Modifier.fillMaxSize())
            Box(
                modifier = Modifier.align(Alignment.Center).size(44.dp).background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            video.format?.let { Badge(it, Modifier.align(Alignment.TopEnd)) }
            if (download is DownloadStatus.Done) Badge("✓", Modifier.align(Alignment.TopStart))
            val transfer = (download as? DownloadStatus.Running)?.fraction ?: (download as? DownloadStatus.Paused)?.fraction
            val fraction = transfer ?: video.progress
            if (fraction != null) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                    // Red is "watched"; a download in progress is shown in the app's own colour.
                    Box(
                        modifier =
                        Modifier.fillMaxHeight().fillMaxWidth(fraction).background(
                            if (transfer != null) MaterialTheme.colorScheme.primary else PROGRESS,
                        ),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details =
                    listOfNotNull(
                        video.sizeBytes?.let(::formatBytes),
                        downloadText(download)
                            ?: video.progress?.let { stringResource(R.string.library_resume_hint, (it * PERCENT).toInt()) },
                    ).joinToString("  ·  ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(videoMenuTag(video.id))) {
                    Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.home_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Only what makes sense for the download's current state is offered.
                    when (download) {
                        DownloadStatus.None ->
                            DropdownMenuItem(text = {
                                Text(stringResource(R.string.video_action_download))
                            }, onClick = { choose(onDownload) })

                        is DownloadStatus.Running ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.video_action_pause_download)) },
                                onClick = { choose(onPauseDownload) },
                            )

                        is DownloadStatus.Paused, DownloadStatus.Failed ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.video_action_resume_download)) },
                                onClick = { choose(onResumeDownload) },
                            )

                        is DownloadStatus.Done -> Unit
                    }
                    if (download !is DownloadStatus.None) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.video_action_remove_download)) },
                            onClick = { choose(onRemoveDownload) },
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_remove)) }, onClick = { choose(onRemove) })
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(
            6.dp,
        ).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
