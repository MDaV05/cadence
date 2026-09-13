package com.cadence.music.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
fun NowPlayingScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val sleepLeft by player.sleepRemainingMs.collectAsStateWithLifecycle()
    val queueSnapshot by player.queueItems.collectAsStateWithLifecycle()
    val queueIdx by player.queueIndexFlow.collectAsStateWithLifecycle()
    var showSleepDialog by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<SyncedLine>>(emptyList()) }
    var unsynced by remember { mutableStateOf<String?>(null) }
    var currentLine by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    // Layout skins: TURNTABLE (Analog), SPOTIFY, APPLE, and STANDARD.
    val skinLayout = com.cadence.music.ui.theme.LocalSkin.current.layout
    val turntable = skinLayout == com.cadence.music.ui.theme.SkinLayout.TURNTABLE
    val spotify = skinLayout == com.cadence.music.ui.theme.SkinLayout.SPOTIFY
    val apple = skinLayout == com.cadence.music.ui.theme.SkinLayout.APPLE

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
        lyrics = emptyList(); unsynced = null; currentLine = -1
        if (state.title.isBlank() && state.artist.isBlank()) return@LaunchedEffect
        // Read controller state here on the main thread — MediaController is
        // main-thread-only and the DB work below hops to IO.
        val mid = player.controller?.currentMediaItem?.mediaId
        val durSec = player.controller?.duration
            ?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000) ?: 0L
        val (fetched, raw) = withContext(Dispatchers.IO) {
            // Cached lyrics first; only hit LRCLIB on an unchecked track.
            val entity = mid?.let { container.database.trackDao().byServerId(mid) }
            val cached = entity?.let { container.database.lyricsDao().byTrackId(it.id) }
            when {
                cached != null && cached.syncedLrc.isNotEmpty() -> {
                    // Plain-text lyrics (user-typed or tag-less LRC) parse to
                    // nothing — keep the raw text for the static display path.
                    val parsed = com.cadence.music.data.metadata.LrcLib.parse(cached.syncedLrc)
                    if (parsed.isNotEmpty()) parsed to null
                    else emptyList<SyncedLine>() to cached.syncedLrc
                }
                cached != null -> emptyList<SyncedLine>() to null // checked previously: none available
                else -> {
                    val f = com.cadence.music.data.metadata.LrcLib.fetchBlocking(
                        state.artist, state.title, durSec,
                    )
                    if (entity != null) {
                        container.database.lyricsDao().upsert(
                            com.cadence.music.data.db.LyricsEntity(
                                trackId = entity.id,
                                syncedLrc = com.cadence.music.data.metadata.LrcLib.toLrcText(f),
                            )
                        )
                    }
                    f to null
                }
            }
        }
        lyrics = fetched; unsynced = raw
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

    // The DB row for the playing track, resolved by mediaId (serverId). Loaded
    // up front so the title block can offer album/artist navigation while the
    // transport row below reads the same state for star/download actions.
    var currentTrack by remember(state.title) {
        mutableStateOf<com.cadence.music.data.db.TrackEntity?>(null)
    }
    LaunchedEffect(state.title) {
        val mid = player.controller?.currentMediaItem?.mediaId
        currentTrack = mid?.let { mid2 ->
            withContext(Dispatchers.IO) { container.database.trackDao().byServerId(mid2) }
        }
    }

    val gradientColors = when {
        apple -> listOf(
            (accent ?: primary).copy(alpha = 0.55f),
            (accent ?: primary).copy(alpha = 0.22f),
            bg,
            bg,
        )
        spotify -> listOf(
            (accent ?: primary).copy(alpha = 0.35f),
            Color(0xFF121212),
            Color(0xFF121212),
        )
        else -> listOf((accent ?: primary).copy(alpha = 0.15f), bg, bg)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors)),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            if (spotify || apple) Icons.Filled.KeyboardArrowDown else Icons.Filled.Close,
                            contentDescription = "Close player",
                        )
                    }
                    if (spotify) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "PLAYING FROM ${if (currentTrack?.albumName?.isNotBlank() == true) "ALBUM" else "LIBRARY"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            currentTrack?.albumName?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (turntable) {
                            IconButton(onClick = { showQueue = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    "Queue",
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            IconButton(onClick = { showSleepDialog = true }) {
                                Icon(
                                    Icons.Filled.Bedtime,
                                    "Sleep timer",
                                    Modifier.size(22.dp),
                                    tint = if (sleepLeft != null) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
        // Cover is a fixed-size box in a full-width pager page: without a
        // centering wrapper it hugs the page's start edge.
        if (horizontal) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NpCover(container, queueSnapshot.getOrNull(queueIdx + page - 1)?.mediaId, skinLayout, state.isPlaying)
                }
            }
        } else {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NpCover(container, queueSnapshot.getOrNull(queueIdx + page - 1)?.mediaId, skinLayout, state.isPlaying)
                }
            }
        }
            val track = currentTrack
            val trackArtist = track?.artistName?.takeIf { it.isNotBlank() }
            val scope = rememberCoroutineScope()
            val starred = currentTrack?.starred == true

            if (spotify) {
                // Spotify Title Row: Title & Artist on left, Heart on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title.ifEmpty { "Nothing playing" },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            trackArtist ?: state.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable(enabled = trackArtist != null) { trackArtist?.let(onArtistClick) },
                        )
                    }
                    if (currentTrack != null) {
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
                                if (starred) "Unlike" else "Like",
                                Modifier.size(24.dp),
                                tint = if (starred) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SeekBar(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onSeekFinished = { fraction -> player.seekTo((fraction * duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
                }

                // Spotify Transport Row
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.toggleShuffle() }) {
                        Icon(
                            Icons.Filled.Shuffle, "Shuffle", Modifier.size(22.dp),
                            tint = if (state.shuffle) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { player.previous() }) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
                    }
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = if (state.isPlaying) "Pause" else "Play",
                                onClick = { player.togglePlayPause() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/pause",
                            Modifier.size(32.dp),
                            tint = Color.Black,
                        )
                    }
                    IconButton(onClick = { player.next() }) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
                    }
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repeat", Modifier.size(22.dp),
                            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Spotify bottom utilities
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSleepDialog = true }) {
                        Icon(
                            Icons.Filled.Bedtime, "Sleep timer", Modifier.size(22.dp),
                            tint = if (sleepLeft != null) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lyrics.isNotEmpty() || unsynced != null) {
                        TextButton(onClick = { showFullLyrics = true }) { Text("Lyrics") }
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic, "Queue", Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (apple) {
                // Apple Music layout: centered bold title + primary accent artist
                Text(
                    state.title.ifEmpty { "Nothing playing" },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    trackArtist ?: state.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(enabled = trackArtist != null) { trackArtist?.let(onArtistClick) },
                )

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
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.previous() }) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(40.dp))
                    }
                    Spacer(Modifier.size(28.dp))
                    IconButton(onClick = { player.togglePlayPause() }) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/pause",
                            Modifier.size(56.dp),
                            tint = primary,
                        )
                    }
                    Spacer(Modifier.size(28.dp))
                    IconButton(onClick = { player.next() }) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(40.dp))
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentTrack != null) {
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
                                if (starred) "Unlike" else "Like",
                                Modifier.size(22.dp),
                                tint = if (starred) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(20.dp))
                    }
                    IconButton(onClick = { player.toggleShuffle() }) {
                        Icon(
                            Icons.Filled.Shuffle, "Shuffle", Modifier.size(22.dp),
                            tint = if (state.shuffle) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(20.dp))
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repeat", Modifier.size(22.dp),
                            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lyrics.isNotEmpty() || unsynced != null) {
                        Spacer(Modifier.size(20.dp))
                        IconButton(onClick = { showFullLyrics = true }) {
                            Icon(
                                Icons.Filled.FormatQuote, "Lyrics", Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.size(20.dp))
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic, "Queue", Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    state.title.ifEmpty { "Nothing playing" },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                track?.albumName?.takeIf { it.isNotBlank() }?.let { albumName ->
                    Text(
                        albumName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { onAlbumClick(track.albumNorm) },
                    )
                }
                Text(
                    trackArtist ?: state.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(enabled = trackArtist != null) { trackArtist?.let(onArtistClick) },
                )

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
                    if (currentTrack != null) {
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
                                if (starred) "Unlike" else "Like",
                                Modifier.size(22.dp),
                                tint = if (starred) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(28.dp))
                        var downloadQueued by remember(state.title) { mutableStateOf(false) }
                        if (currentTrack?.path == null && currentTrack?.sourceId != "local" && !downloadQueued) {
                            IconButton(onClick = {
                                val t = currentTrack ?: return@IconButton
                                container.library.enqueueDownload(t)
                                downloadQueued = true
                                Toast.makeText(context, "Download queued", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Filled.Download,
                                    "Download",
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.size(28.dp))
                        }
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
                    if (turntable) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(
                                    role = androidx.compose.ui.semantics.Role.Button,
                                    onClickLabel = if (state.isPlaying) "Pause" else "Play",
                                    onClick = { player.togglePlayPause() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play/pause",
                                Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    } else {
                        IconButton(onClick = { player.togglePlayPause() }) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play/pause",
                                Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
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
                    if (lyrics.isNotEmpty() || unsynced != null) {
                        TextButton(onClick = { showFullLyrics = true }) { Text("Lyrics") }
                    }
                    if (!turntable) {
                        TextButton(onClick = { showQueue = true }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Queue")
                        }
                    }
                }
            }

            // Current lyric line preview under transport
            val unsyncedText = unsynced
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
            } else if (!showQueue && unsyncedText != null) {
                // Unsynced static block: no per-line timing, plain styling.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        unsyncedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                            Modifier.size(44.dp).clip(MaterialTheme.shapes.extraSmall),
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

    if (showFullLyrics && (lyrics.isNotEmpty() || unsynced != null)) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(currentLine) {
            if (currentLine >= 0) listState.animateScrollToItem(currentLine)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
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
                    overflow = TextOverflow.Ellipsis,
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
                if (lyrics.isEmpty()) {
                    unsynced?.let { raw ->
                        item {
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
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

/**
 * Album cover for a queue/media id; placeholder box while unresolved or
 * missing. extraSmall is the "content thumbnail" radius — skins keep real
 * artwork square-ish (PILL's full-round small is for chips/buttons).
 */
@Composable
internal fun TrackArt(
    container: AppContainer,
    mediaId: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
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
            .clip(shape)
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

/** Now Playing art: square cover, spinning vinyl in TURNTABLE, or rounded card in SPOTIFY/APPLE. */
@Composable
private fun NpCover(
    container: AppContainer,
    mediaId: String?,
    layout: com.cadence.music.ui.theme.SkinLayout,
    playing: Boolean,
) {
    when (layout) {
        com.cadence.music.ui.theme.SkinLayout.TURNTABLE -> VinylDisc(container, mediaId, playing)
        com.cadence.music.ui.theme.SkinLayout.SPOTIFY -> TrackArt(
            container,
            mediaId,
            Modifier.size(310.dp),
            RoundedCornerShape(8.dp),
        )
        com.cadence.music.ui.theme.SkinLayout.APPLE -> TrackArt(
            container,
            mediaId,
            Modifier.size(300.dp),
            RoundedCornerShape(16.dp),
        )
        com.cadence.music.ui.theme.SkinLayout.STANDARD -> TrackArt(
            container,
            mediaId,
            Modifier.size(280.dp),
            MaterialTheme.shapes.large,
        )
    }
}

/**
 * TURNTABLE skin art: the cover rendered as a spinning record — circular art
 * under etched groove rings, an accent hub label with a spindle hole, and a
 * fixed light sheen so the grooves read as reflective while the disc turns.
 * ~45°/s (one turn per 8s): calm enough to track the label, fast enough to
 * read as "playing". Pauses freeze wherever the disc stopped, like lifting
 * a real needle.
 */
@Composable
private fun VinylDisc(
    container: AppContainer,
    mediaId: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (currentCoroutineContext().isActive) {
            withFrameNanos { now ->
                spin = (spin + (now - last) / 1_000_000_000f * 45f) % 360f
                last = now
            }
        }
    }
    Box(modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(300.dp)
                .clip(CircleShape)
                .graphicsLayer { rotationZ = spin },
            contentAlignment = Alignment.Center,
        ) {
            TrackArt(container, mediaId, Modifier.matchParentSize(), CircleShape)
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2f
                for (g in floatArrayOf(0.975f, 0.94f, 0.90f, 0.86f, 0.81f, 0.76f, 0.70f, 0.64f)) {
                    drawCircle(Color.Black.copy(alpha = 0.09f), radius = r * g, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
            // Hub label — a small accent disc with the spindle hole at center.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        }
        // Sheen sits outside the rotating layer: light stays put, record turns.
        Box(
            Modifier
                .size(300.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f),
                        ),
                    )
                ),
        )
    }
}
