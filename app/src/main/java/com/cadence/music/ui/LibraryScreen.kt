package com.cadence.music.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.downloads.DownloadWorker

@Composable
fun LibraryScreen(container: AppContainer, onArtistClick: (String) -> Unit = {}) {
    val tracks by container.library.tracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val player = container.player
    val context = LocalContext.current

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { player.shuffleAll(tracks.map { it.toTrack() }) },
                icon = { Icon(Icons.Filled.Shuffle, null) },
                text = { Text("Shuffle all") },
            )
        }
    ) { padding ->
        if (tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No music found", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a server or rescan in Settings",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(tracks, key = { it.id }) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.playNow(listOf(track.toTrack())) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val art by produceState<String?>(null, track.id) {
                            value = container.artResolver.urlFor(track)
                        }
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            val sub = when {
                                track.sourceId == "local" -> listOfNotNull(
                                    track.artistName.ifBlank { null },
                                    "Local",
                                )
                                track.path != null -> listOfNotNull(
                                    track.artistName.ifBlank { null },
                                    "Downloaded",
                                )
                                else -> listOfNotNull(
                                    track.artistName.ifBlank { null },
                                    "Stream",
                                )
                            }
                            Text(
                                sub.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.clickable {
                                    if (track.artistName.isNotBlank()) onArtistClick(track.artistName)
                                },
                            )
                        }
                        if (track.sourceId == "subsonic" && track.path == null) {
                            IconButton(onClick = { DownloadWorker.enqueue(context, track.id) }) {
                                Icon(Icons.Filled.Download, "Download")
                            }
                        }
                        Text(
                            formatDuration(track.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun TrackEntity.toTrack() = com.cadence.music.data.source.Track(
    key = serverId,
    sourceId = sourceId,
    title = title,
    artist = artistName,
    album = albumName,
    albumKey = albumKey,
    durationMs = durationMs,
    localPath = path,
)
