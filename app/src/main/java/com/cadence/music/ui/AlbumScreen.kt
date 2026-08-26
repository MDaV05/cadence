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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cadence.music.AppContainer

@Composable
fun AlbumScreen(container: AppContainer, albumName: String) {
    val player = container.player
    val context = androidx.compose.ui.platform.LocalContext.current
    var tracks by remember { mutableStateOf<List<com.cadence.music.data.db.TrackEntity>>(emptyList()) }
    var art by remember { mutableStateOf<String?>(null) }

    fun downloadAll() {
        tracks.filter { it.sourceId == "subsonic" && it.path == null }
            .forEach { com.cadence.music.data.downloads.DownloadWorker.enqueue(context, it.id) }
    }

    LaunchedEffect(albumName) {
        tracks = container.library.tracksByAlbum(albumName)
        art = tracks.firstOrNull()?.let { container.artResolver.urlFor(it) }
    }

    Scaffold { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(albumName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${tracks.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        IconButton(onClick = { player.playNow(tracks.map { it.toTrack() }) }) {
                            Icon(Icons.Filled.PlayArrow, "Play album")
                        }
                        IconButton(onClick = { player.shuffleAll(tracks.map { it.toTrack() }) }) {
                            Icon(Icons.Filled.Shuffle, "Shuffle album")
                        }
                        IconButton(onClick = { downloadAll() }) {
                            Icon(Icons.Filled.Download, "Download album")
                        }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { player.playNow(tracks.map { it.toTrack() }, index) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${track.trackNumber.takeIf { it > 0 } ?: ""}",
                        modifier = Modifier.padding(end = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(track.title, modifier = Modifier.weight(1f), maxLines = 1)
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
