package com.videobridge.tv.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.videobridge.core.data.downloads.DownloadStatus
import com.videobridge.core.model.Video
import com.videobridge.core.model.formatBytes
import com.videobridge.core.tvdesignsystem.TvKeyboard
import com.videobridge.core.tvdesignsystem.TvKeyboardType
import com.videobridge.feature.library.LibraryUiState
import com.videobridge.tv.R

const val TV_HOME_GREETING_TAG = "tv_home_greeting"
const val TV_MENU_TAG = "tv_menu"
const val TV_SEARCH_TAG = "tv_search"
const val TV_LIBRARY_TAG = "tv_library"
const val TV_ACTION_PLAY_TAG = "tv_action_play"

/** The download action that fits the video's state: Download, Stop download or Resume download. */
const val TV_ACTION_DOWNLOAD_TAG = "tv_action_download"
const val TV_ACTION_DELETE_TAG = "tv_action_delete"

/** Test tag of the card for one video. */
fun tvVideoTag(id: String) = "tv_video_$id"

private const val COLUMNS = 4
private const val PERCENT = 100
private const val POSTER_RATIO = 16f / 9f
private val MUTED = Color(0xFFB8BEC9)
private val ACCENT = Color(0xFFFFB74D)
private val WATCHED = Color(0xFFE50914)
private val ART = listOf(Color(0xFF0F766E), Color(0xFF1D4ED8), Color(0xFFB45309), Color(0xFF9D174D), Color(0xFF4338CA), Color(0xFF047857))

/**
 * The TV's home: the library as a grid of poster cards, with search and a menu button in the
 * header. Focus starts on the newest video, so one press of OK plays it; holding OK opens the
 * options (play, download, stop / resume, delete).
 */
@Composable
fun TvHomeScreen(
    displayName: String,
    library: LibraryUiState,
    downloads: Map<String, DownloadStatus>,
    askBeforePlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onPlay: (Video) -> Unit,
    onDownload: (Video) -> Unit,
    onPauseDownload: (Video) -> Unit,
    onResumeDownload: (Video) -> Unit,
    onRemoveDownload: (Video) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Video?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val visible = library.visibleVideos
    val hasCards = visible.isNotEmpty()
    // Also runs when the first videos arrive, and after the keyboard or the options close.
    LaunchedEffect(searching, actionsFor == null, hasCards) {
        if (!searching && actionsFor == null) (if (hasCards) firstCardFocus else searchFocus).requestFocus()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.logo_dekho),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.home_greeting, displayName),
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag(TV_HOME_GREETING_TAG),
                )
                if (library.query.isNotEmpty()) {
                    Text(text = "“${library.query}”", fontSize = 18.sp, color = ACCENT, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                }
                HeaderButton(
                    icon = R.drawable.ic_search,
                    label = stringResource(R.string.home_search),
                    onClick = { searching = true },
                    modifier = Modifier.focusRequester(searchFocus).testTag(TV_SEARCH_TAG),
                )
                Spacer(Modifier.width(12.dp))
                HeaderButton(
                    icon = R.drawable.ic_more_vert,
                    label = stringResource(R.string.home_menu),
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag(TV_MENU_TAG),
                )
            }
            if (library.offline) {
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(R.string.library_offline), fontSize = 18.sp, color = Color(0xFFFFB4AB))
            }
            Spacer(Modifier.height(8.dp))

            when {
                library.loading -> Unit

                library.videos.isEmpty() -> Message(stringResource(R.string.home_empty), stringResource(R.string.home_empty_hint))

                !hasCards -> Message(stringResource(R.string.library_no_results, library.query.trim()), null)

                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        modifier = Modifier.fillMaxSize().testTag(TV_LIBRARY_TAG),
                        // Padding so a focused card can grow without being clipped by the grid.
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        // Stable keys: a refresh that adds a video does not move focus.
                        itemsIndexed(visible, key = { _, video -> video.id }) { index, video ->
                            val status = downloads[video.id] ?: DownloadStatus.None
                            // The grid composes its cards after the screen's own effects have run,
                            // so the very first focus request is made from the first card itself,
                            // and only once: scrolling back up must not pull focus away.
                            if (index == 0 && !initialFocusDone) {
                                LaunchedEffect(Unit) {
                                    if (!searching && actionsFor == null) firstCardFocus.requestFocus()
                                    initialFocusDone = true
                                }
                            }
                            PosterCard(
                                video = video,
                                download = status,
                                onClick = { if (askBeforePlaying && status is DownloadStatus.None) actionsFor = video else onPlay(video) },
                                onLongClick = { actionsFor = video },
                                modifier = (
                                    if (index ==
                                        0
                                    ) {
                                        Modifier.focusRequester(firstCardFocus)
                                    } else {
                                        Modifier
                                    }
                                    ).testTag(tvVideoTag(video.id)),
                            )
                        }
                    }
            }
        }

        actionsFor?.let { video ->
            fun done(action: (Video) -> Unit) {
                actionsFor = null
                action(video)
            }
            VideoActions(
                video = video,
                download = downloads[video.id] ?: DownloadStatus.None,
                onPlay = { done(onPlay) },
                onDownload = { done(onDownload) },
                onPauseDownload = { done(onPauseDownload) },
                onResumeDownload = { done(onResumeDownload) },
                onRemoveDownload = { done(onRemoveDownload) },
                onDismiss = { actionsFor = null },
            )
        }

        AnimatedVisibility(
            visible = searching,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { searching = false }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = library.query.ifEmpty { stringResource(R.string.library_search) } + "▏",
                    fontSize = 22.sp,
                    modifier = Modifier.background(
                        Color(0xFF1B2228),
                        RoundedCornerShape(8.dp),
                    ).padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(6.dp))
                TvKeyboard(
                    type = TvKeyboardType.TEXT,
                    onCharacter = { onQueryChange(library.query + it) },
                    onBackspace = { onQueryChange(library.query.dropLast(1)) },
                    onAction = { searching = false },
                    onDismiss = { searching = false },
                    onExitUp = { searching = false },
                    onExitDown = { searching = false },
                )
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = body, fontSize = 20.sp, color = MUTED, textAlign = TextAlign.Center, modifier = Modifier.width(560.dp))
        }
    }
}

/** A round icon button. The label is read out by screen readers; the icon is what is seen. */
@Composable
private fun HeaderButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        colors =
        ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2A303B),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun downloadText(status: DownloadStatus): String? = when (status) {
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
 * A poster card in the style of streaming apps: artwork fills the card, the name sits on a dark
 * fade at the bottom, a thin line shows progress (red = watched), and focus is a white frame
 * with a slight lift. The artwork is the picture the link advertises, or a coloured backdrop
 * with the video's initial; a frame is never grabbed from the video.
 */
@Composable
private fun PosterCard(
    video: Video,
    download: DownloadStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().aspectRatio(POSTER_RATIO),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors =
        ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF161B22),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF161B22),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val base = ART[(video.id.hashCode() and Int.MAX_VALUE) % ART.size]
            Box(
                modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(base, Color(0xFF0B1220)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = video.title.trim().take(1).uppercase(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.16f),
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
            video.format?.let { Badge(it, Modifier.align(Alignment.TopEnd)) }
            if (download is DownloadStatus.Done) Badge("✓", Modifier.align(Alignment.TopStart))
            Column(
                modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 9.dp),
            ) {
                Text(text = video.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details =
                    listOfNotNull(
                        video.sizeBytes?.let(::formatBytes),
                        downloadText(download)
                            ?: video.progress?.let { stringResource(R.string.library_resume_hint, (it * PERCENT).toInt()) },
                    ).joinToString("  ·  ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        fontSize = 12.sp,
                        color = Color(0xFFD1D5DB),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val transfer = (download as? DownloadStatus.Running)?.fraction ?: (download as? DownloadStatus.Paused)?.fraction
            val fraction = transfer ?: video.progress
            if (fraction != null) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction).background(if (transfer != null) ACCENT else WATCHED))
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

/**
 * What can be done with one video. Play has focus.
 *
 * This opens while OK is still held down (it is the long-press menu), so the release of that
 * same press must not count as "OK on Play". Nothing is accepted until OK has been pressed
 * afresh: held-key repeats and the first release are swallowed.
 */
@Composable
private fun VideoActions(
    video: Video,
    download: DownloadStatus,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { playFocus.requestFocus() }
    BackHandler(onBack = onDismiss)
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                when {
                    !isSelect || armed -> false

                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                        armed = true
                        false
                    }

                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(420.dp).background(Color(0xFF1F242D), RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = video.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
            downloadText(download)?.let { Text(text = it, fontSize = 16.sp, color = MUTED) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().focusRequester(playFocus).testTag(TV_ACTION_PLAY_TAG)) {
                Text(text = stringResource(R.string.video_action_play), fontSize = 20.sp)
            }
            // Only what makes sense for the download's current state is offered.
            when (download) {
                DownloadStatus.None -> Action(R.string.video_action_download, TV_ACTION_DOWNLOAD_TAG, onDownload)

                is DownloadStatus.Running -> Action(R.string.video_action_pause_download, TV_ACTION_DOWNLOAD_TAG, onPauseDownload)

                is DownloadStatus.Paused, DownloadStatus.Failed -> Action(
                    R.string.video_action_resume_download,
                    TV_ACTION_DOWNLOAD_TAG,
                    onResumeDownload,
                )

                is DownloadStatus.Done -> Unit
            }
            if (download !is DownloadStatus.None) Action(R.string.video_action_remove_download, TV_ACTION_DELETE_TAG, onRemoveDownload)
            Action(R.string.action_cancel, "tv_action_cancel", onDismiss)
        }
    }
}

@Composable
private fun Action(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(text = stringResource(label), fontSize = 20.sp)
    }
}
