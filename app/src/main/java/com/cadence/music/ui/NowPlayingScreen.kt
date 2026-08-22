package com.cadence.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.metadata.SyncedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(container: AppContainer) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var artUrl by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<SyncedLine>>(emptyList()) }
    var currentLine by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }

    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary

    LaunchedEffect(state.isPlaying) {
        while (true) {
            position = player.controller?.currentPosition ?: 0
            duration = player.controller?.duration ?: 0
            delay(400)
        }
    }

    LaunchedEffect(state.title, state.artist) {
        artUrl = null; lyrics = emptyList(); currentLine = -1
        val mediaId = player.controller?.currentMediaItem?.mediaId ?: return@LaunchedEffect
        val track = withContext(Dispatchers.IO) {
            container.database.trackDao().byServerId(mediaId)
        } ?: return@LaunchedEffect
        artUrl = withContext(Dispatchers.IO) { container.artResolver.urlFor(track) }
        lyrics = withContext(Dispatchers.IO) {
            com.cadence.music.data.metadata.LrcLib.fetchBlocking(
                state.artist, state.title, duration / 1000,
            )
        }
    }

    if (lyrics.isNotEmpty()) {
        LaunchedEffect(position) {
            currentLine = lyrics.indexOfLast { it.timeMs <= position }.takeIf { it >= 0 } ?: -1
        }
    }

    // Track-change gesture: horizontal (default) or vertical per Settings.
    val gesture = container.prefs.trackGesture
    val dragModifier = Modifier.pointerInput(gesture) {
        detectDragGestures { change, amount ->
            change.consume()
            when (gesture) {
                com.cadence.music.data.prefs.Prefs.TrackGesture.VERTICAL -> {
                    if (amount.y < -60) { player.next() }
                    else if (amount.y > 60) { player.previous() }
                }
                else -> {
                    if (amount.x < -60) { player.next() }
                    else if (amount.x > 60) { player.previous() }
                }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(primary.copy(alpha = 0.10f), bg, bg),
                    )
                )
                .then(dragModifier)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
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

            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { player.seekTo((it * duration).toLong()) },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
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
                Text(
                    lyrics[currentLine].text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            val q = player.queue
            LazyColumn(Modifier.padding(bottom = 32.dp)) {
                items(q.size) { i ->
                    val item = q[i]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.jumpTo(i); showQueue = false }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.mediaMetadata.title?.toString() ?: "",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == player.queueIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { player.removeFromQueue(i) }) {
                            Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
