package com.cadence.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.db.PlaylistTrackRow
import com.cadence.music.data.db.PlaylistWithCount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(container: AppContainer, onOpen: (Long) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val playlists by container.library.playlists()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, "New playlist")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Playlists",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            if (playlists.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap + to create one, then long-press any song to add it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn {
                    items(playlists, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = {
                                Text(if (p.trackCount == 1) "1 song" else "${p.trackCount} songs")
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renameTarget = p }) {
                                        Icon(Icons.Filled.Edit, "Rename ${p.name}")
                                    }
                                    IconButton(onClick = { deleteTarget = p }) {
                                        Icon(Icons.Filled.Delete, "Delete ${p.name}")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onOpen(p.id) },
                        )
                    }
                }
            }
        }
    }

    if (showNew) {
        NewPlaylistDialog(
            onCreate = { name ->
                showNew = false
                if (name.isNotBlank()) scope.launch { container.library.createPlaylist(name) }
            },
            onDismiss = { showNew = false },
        )
    }

    renameTarget?.let { p ->
        NewPlaylistDialog(
            title = "Rename playlist",
            initialName = p.name,
            confirmLabel = "Rename",
            onCreate = { name ->
                renameTarget = null
                if (name.isNotBlank()) scope.launch { container.library.renamePlaylist(p.id, name) }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This removes the playlist and its ${p.trackCount} songs from it. The songs themselves stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { container.library.deletePlaylist(p.id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun NewPlaylistDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "New playlist",
    initialName: String = "",
    confirmLabel: String = "Create",
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton({ onCreate(name) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(container: AppContainer, playlistId: Long, onBack: () -> Unit = {}) {
    val player = container.player
    val scope = rememberCoroutineScope()
    var name by remember(playlistId) { mutableStateOf("") }
    var tracks by remember(playlistId) { mutableStateOf(listOf<PlaylistTrackRow>()) }

    LaunchedEffect(playlistId) {
        name = container.library.playlist(playlistId)?.name ?: "Playlist"
        tracks = container.library.playlistTracksWithRows(playlistId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { container.library.enqueueDownloads(tracks.map { it.track }) }) {
                        Icon(Icons.Filled.Download, "Download playlist")
                    }
                    IconButton(onClick = {
                        if (tracks.isNotEmpty()) player.playNow(tracks.map { it.track.toTrack() })
                    }) {
                        Icon(Icons.Filled.PlayArrow, "Play all", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
    ) { padding ->
        if (tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Empty playlist", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Long-press songs in your library to add them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(tracks, key = { _, entry -> entry.row.id }) { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                player.playNow(
                                    tracks.map { it.track.toTrack() },
                                    startIndex = index,
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.track.title, maxLines = 1)
                            Text(
                                entry.track.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            formatDuration(entry.track.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = {
                            scope.launch {
                                container.library.removeFromPlaylist(entry.row.id)
                                tracks = container.library.playlistTracksWithRows(playlistId)
                            }
                        }) {
                            Icon(Icons.Filled.Close, "Remove", Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
