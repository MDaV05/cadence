package com.cadence.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.metadata.SyncedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(container: AppContainer) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val sleepLeft by player.sleepRemainingMs.collectAsStateWithLifecycle()
    val queueSnapshot by player.queueItems.collectAsStateWithLifecycle()
    val queueIdx by player.queueIndexFlow.collectAsStateWithLifecycle()
    var showSleepDialog by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<SyncedLine>>(emptyList()) }
    var currentLine by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary

    // Ambient tint from the current album art — background gradient only.
    // Controls stay on MaterialTheme colors so they're always visible.
    val context = LocalContext.current
    var accent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(state.title) {
        accent = null
        val mid = player.controller?.currentMediaItem?.mediaId ?: return@LaunchedEffect
        val url: String? = withContext(Dispatchers.IO) {
            runCatching {
                container.database.trackDao().byServerId(mid)?.let { container.artResolver.urlFor(it) }
            }.getOrNull()
        } ?: return@LaunchedEffect
        val result: coil.request.ImageResult? = runCatching {
            context.imageLoader.execute(
                coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
            )
        }.getOrNull()
        val drawable = (result as? coil.request.SuccessResult)?.drawable ?: return@LaunchedEffect
        val bitmap = runCatching { drawable.toBitmap() }.getOrNull() ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        accent = Color(palette.getVibrantColor(palette.getDominantColor(primary.toArgb())))
    }
    LaunchedEffect(state.isPlaying) {
        // Stop polling when paused — the previous while(true) recomposed 2.5x/sec forever.
        while (currentCoroutineContext().isActive && state.isPlaying) {
            position = player.controller?.currentPosition ?: 0
            // TIME_UNSET before prepare would render as garbage time text.
            duration = player.controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(400)
        }
    }

    LaunchedEffect(state.title, state.artist) {
        lyrics = emptyList(); currentLine = -1
        if (state.title.isBlank() && state.artist.isBlank()) return@LaunchedEffect
        // Read controller state here on the main thread — MediaController is
        // main-thread-only and the DB work below hops to IO.
        val mid = player.controller?.currentMediaItem?.mediaId
        val durSec = player.controller?.duration
            ?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000) ?: 0L
        lyrics = withContext(Dispatchers.IO) {
            // Cached lyrics first; only hit LRCLIB on an unchecked track.
            val entity = mid?.let { container.database.trackDao().byServerId(mid) }
            val cached = entity?.let { container.database.lyricsDao().byTrackId(it.id) }
            when {
                cached != null && cached.syncedLrc.isNotEmpty() ->
                    com.cadence.music.data.metadata.LrcLib.parse(cached.syncedLrc)
                cached != null -> emptyList() // checked previously: none available
                else -> {
                    val fetched = com.cadence.music.data.metadata.LrcLib.fetchBlocking(
                        state.artist, state.title, durSec,
                    )
                    if (entity != null) {
                        container.database.lyricsDao().upsert(
                            com.cadence.music.data.db.LyricsEntity(
                                trackId = entity.id,
                                syncedLrc = com.cadence.music.data.metadata.LrcLib.toLrcText(fetched),
                            )
                        )
                    }
                    fetched
                }
            }
        }
    }

    if (lyrics.isNotEmpty()) {
        LaunchedEffect(position) {
            currentLine = lyrics.indexOfLast { it.timeMs <= position }.takeIf { it >= 0 } ?: -1
        }
    }

    // Swipe left/right (or up/down per Settings) to change track. A pager gives
    // an animated cover transition and exactly one track per completed swipe —
    // the old drag detector fired next()/previous() on every frame past its
    // threshold, skipping several songs per flick.
    val gesture = container.prefs.trackGesture
    val horizontal = gesture != com.cadence.music.data.prefs.Prefs.TrackGesture.VERTICAL
    val pagerState = rememberPagerState(initialPage = 1) { 3 }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when (page) {
                0 -> player.previous()
                2 -> player.next()
            }
            if (page != 1) pagerState.scrollToPage(1)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf((accent ?: primary).copy(alpha = 0.10f), bg, bg),
                    )
                )
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // Cover is a fixed-size box in a full-width pager page: without a
        // centering wrapper it hugs the page's start edge.
        if (horizontal) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TrackArt(container, queueSnapshot.getOrNull(queueIdx + page - 1)?.mediaId, Modifier.size(280.dp))
                }
            }
        } else {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TrackArt(container, queueSnapshot.getOrNull(queueIdx + page - 1)?.mediaId, Modifier.size(280.dp))
                }
            }
        }
            Text(
                state.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                state.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Drag updates a local value; the seek fires once on release instead
            // of on every tick (which stuttered playback).
            SeekBar(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onSeekFinished = { fraction -> player.seekTo((fraction * duration).toLong()) },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Star toggle — server tracks only; reflects and updates the
                // Subsonic favorite state.
                val scope = rememberCoroutineScope()
                var currentTrack by remember(state.title) {
                    mutableStateOf<com.cadence.music.data.db.TrackEntity?>(null)
                }
                LaunchedEffect(state.title) {
                    val mid = player.controller?.currentMediaItem?.mediaId
                    currentTrack = mid?.let { mid2 ->
                        withContext(Dispatchers.IO) { container.database.trackDao().byServerId(mid2) }
                    }
                }
                if (currentTrack?.sourceId == "subsonic") {
                    val starred = currentTrack?.starred == true
                    IconButton(onClick = {
                        val t = currentTrack ?: return@IconButton
                        scope.launch {
                            container.library.toggleStar(t)
                            currentTrack = withContext(Dispatchers.IO) {
                                container.database.trackDao().byId(t.id)
                            }
                        }
                    }) {
                        Icon(
                            if (starred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Star",
                            Modifier.size(22.dp),
                            tint = if (starred) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(28.dp))
                }
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(
                        Icons.Filled.Shuffle, "Shuffle", Modifier.size(22.dp),
                        tint = if (state.shuffle) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(28.dp))
                IconButton(onClick = { player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat", Modifier.size(22.dp),
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(28.dp))
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(
                        Icons.Filled.Bedtime, "Sleep timer", Modifier.size(22.dp),
                        tint = if (sleepLeft != null) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sleepLeft?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        formatDuration(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = primary,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                Spacer(Modifier.size(16.dp))
                IconButton(onClick = { player.togglePlayPause() }) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/pause",
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(16.dp))
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (lyrics.isNotEmpty()) {
                    TextButton(onClick = { showQueue = false }) { Text("Lyrics") }
                }
                TextButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Queue")
                }
            }

            // Current lyric line preview under transport
            if (!showQueue && lyrics.isNotEmpty() && currentLine >= 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        lyrics[currentLine].text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = { showFullLyrics = true }) {
                        Text("Full screen", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (!showQueue && lyrics.isNotEmpty()) {
                Text(
                    "Lyrics available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            var queueQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                queueQuery,
                { queueQuery = it },
                label = { Text("Search queue") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val q = queueSnapshot
            // Visible entries keep their original queue index for jump/remove/move.
            val visible: List<Int> = if (queueQuery.isBlank()) q.indices.toList() else q.indices.filter { i ->
                q[i].mediaMetadata.title?.toString()?.contains(queueQuery, true) == true ||
                    q[i].mediaMetadata.artist?.toString()?.contains(queueQuery, true) == true
            }
            val rowHeightPx = with(LocalDensity.current) { 72.dp.toPx() }
            var dragIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            LazyColumn(Modifier.padding(bottom = 32.dp)) {
                itemsIndexed(visible, key = { _, original -> original }) { _, originalIndex ->
                    val item = q[originalIndex]
                    val dragging = dragIndex == originalIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .pointerInput(visible) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragIndex = originalIndex },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val current = dragIndex ?: return@detectDragGesturesAfterLongPress
                                        val moved = (dragOffset / rowHeightPx).roundToInt()
                                        val target = (current + moved).coerceIn(0, q.lastIndex)
                                        if (target != current) {
                                            player.moveQueueItem(current, target)
                                            dragOffset -= (target - current) * rowHeightPx
                                            dragIndex = target
                                        }
                                    },
                                    onDragEnd = { dragIndex = null; dragOffset = 0f },
                                    onDragCancel = { dragIndex = null; dragOffset = 0f },
                                )
                            }
                            .clickable { player.jumpTo(originalIndex); showQueue = false }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackArt(
                            container,
                            item.mediaId,
                            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                item.mediaMetadata.title?.toString() ?: "",
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (originalIndex == queueIdx) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            val artist = item.mediaMetadata.artist?.toString().orEmpty()
                            if (artist.isNotBlank()) {
                                Text(
                                    artist,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { player.removeFromQueue(originalIndex) }) {
                            Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showFullLyrics && lyrics.isNotEmpty()) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(currentLine) {
            if (currentLine >= 0) listState.animateScrollToItem(currentLine)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bg),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = { showFullLyrics = false }) {
                    Icon(Icons.Filled.Close, "Close lyrics")
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp, vertical = 64.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(lyrics) { i, line ->
                    Text(
                        line.text,
                        style = if (i == currentLine) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.bodyLarge,
                        color = when {
                            i == currentLine -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.seekTo(line.timeMs) },
                    )
                }
            }
        }
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            player.startSleepTimer(minutes)
                            showSleepDialog = false
                        }) { Text("$minutes minutes") }
                    }
                    if (sleepLeft != null) {
                        TextButton(onClick = {
                            player.cancelSleepTimer()
                            showSleepDialog = false
                        }) { Text("Cancel timer") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ showSleepDialog = false }) { Text("Close") } },
        )
    }
}

/** Album cover for a queue/media id; placeholder box while unresolved or missing. */
@Composable
internal fun TrackArt(container: AppContainer, mediaId: String?, modifier: Modifier = Modifier) {
    var model by remember(mediaId) { mutableStateOf<Any?>(null) }
    LaunchedEffect(mediaId) {
        model = null
        if (mediaId == null) return@LaunchedEffect
        model = withContext(Dispatchers.IO) {
            container.database.trackDao().byServerId(mediaId)?.let { container.artResolver.urlFor(it) }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
