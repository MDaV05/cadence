package com.cadence.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(container: AppContainer, onArtistClick: (String) -> Unit = {}) {
    val tracks by container.library.tracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val player = container.player

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
            val scope = rememberCoroutineScope()
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) scope.launch { container.library.syncAll() }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Your library is empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Grant access to your music files, or connect a server in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        val permission = if (Build.VERSION.SDK_INT >= 33)
                            Manifest.permission.READ_MEDIA_AUDIO
                        else Manifest.permission.READ_EXTERNAL_STORAGE
                        permLauncher.launch(permission)
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Grant audio access") }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(container, track, onArtistClick) { player.playNow(listOf(track.toTrack())) }
                }
            }
        }
    }
}

@Composable
fun TrackRow(
    container: AppContainer,
    track: TrackEntity,
    onArtistClick: (String) -> Unit = {},
    onClick: () -> Unit,
) {
    val art by produceState<String?>(null, track.id) {
        value = container.artResolver.urlFor(track)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = art,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val sub = when {
                track.sourceId == "local" -> listOfNotNull(track.artistName.ifBlank { null }, "Local")
                track.path != null -> listOfNotNull(track.artistName.ifBlank { null }, "Downloaded")
                else -> listOfNotNull(track.artistName.ifBlank { null }, "Stream")
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
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
