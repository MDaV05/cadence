package com.cadence.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.metadata.SyncedLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingScreen(container: AppContainer) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var artUrl by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<List<SyncedLine>>(emptyList()) }
    var currentLine by remember { mutableIntStateOf(-1) }
    var showLyrics by remember { mutableStateOf(true) }
    var showQueue by remember { mutableStateOf(false) }
    var queueVersion by remember { mutableIntStateOf(0) }

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
        val track = withContext(Dispatchers.IO) { container.database.trackDao().byServerId(mediaId) }
            ?: return@LaunchedEffect
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Text(
                state.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                state.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { player.seekTo((it * duration).toLong()) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(position), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous")
                }
                IconButton(onClick = { player.togglePlayPause() }) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/pause",
                    )
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Filled.SkipNext, "Next")
                }
            }

            if (showQueue) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                val q = player.queue
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).weight(1f, fill = false),
                ) {
                    items(q.size) { i ->
                        val item = q[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { player.jumpTo(i) }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
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

            TextButton(onClick = { showQueue = !showQueue }) {
                Text(if (showQueue) "Hide queue" else "Up next / lyrics")
            }

            if (!showQueue && lyrics.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .weight(1f, fill = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(lyrics) { line ->
                        val active = lyrics.getOrNull(currentLine) == line
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
