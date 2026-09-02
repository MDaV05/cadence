package com.cadence.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.db.PlaylistTrackRow
import com.cadence.music.data.db.PlaylistWithCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Playlist artwork: the user's uploaded photo if set, otherwise the first
 * track's album art, else a placeholder glyph.
 */
@Composable
fun PlaylistCover(
    container: AppContainer,
    playlistId: Long,
    coverPath: String?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val fallback by produceState<String?>(null, playlistId, coverPath) {
        if (coverPath != null) return@produceState
        value = withContext(Dispatchers.IO) {
            container.library.playlistFirstTrack(playlistId)?.let { container.artResolver.urlFor(it) }
        }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val model: Any? = coverPath?.let { File(it) } ?: fallback
        if (model != null) {
            AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Downscales the picked image to ≤1024px JPEG under filesDir/playlist_covers. */
suspend fun savePlaylistCover(context: Context, playlistId: Long, uri: Uri): String {
    val out = File(File(context.filesDir, "playlist_covers").apply { mkdirs() }, "playlist_$playlistId.jpg")
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val decode = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1024)
        }
        context.contentResolver.openInputStream(uri)!!.use { ins ->
            BitmapFactory.decodeStream(ins, null, decode)?.let { bmp ->
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
    }
    return out.absolutePath
}

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(p.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            PlaylistCover(container, p.id, p.coverPath, 56.dp, 12.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(p.name, maxLines = 1)
                                Text(
                                    if (p.trackCount == 1) "1 song" else "${p.trackCount} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { renameTarget = p }) {
                                Icon(Icons.Filled.Edit, "Rename ${p.name}")
                            }
                            IconButton(onClick = { deleteTarget = p }) {
                                Icon(Icons.Filled.Delete, "Delete ${p.name}")
                            }
                        }
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
                    scope.launch {
                        // Drop the uploaded cover file along with the row.
                        container.library.playlist(p.id)?.coverPath?.let { File(it).delete() }
                        container.library.deletePlaylist(p.id)
                    }
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
    val context = LocalContext.current
    var name by remember(playlistId) { mutableStateOf("") }
    var coverPath by remember(playlistId) { mutableStateOf<String?>(null) }
    var trackCount by remember(playlistId) { mutableStateOf(0) }
    var tracks by remember(playlistId) { mutableStateOf(listOf<PlaylistTrackRow>()) }
    var coverMenu by remember { mutableStateOf(false) }

    suspend fun reload() {
        val playlist = container.library.playlist(playlistId)
        name = playlist?.name ?: "Playlist"
        coverPath = playlist?.coverPath
        tracks = container.library.playlistTracksWithRows(playlistId)
        trackCount = tracks.size
    }

    LaunchedEffect(playlistId) { reload() }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            container.library.setPlaylistCover(playlistId, savePlaylistCover(context, playlistId, uri))
            reload()
        }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    PlaylistCover(container, playlistId, coverPath, 96.dp, 20.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            if (trackCount == 1) "1 song" else "$trackCount songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box {
                            TextButton(onClick = { coverMenu = true }) {
                                Text(if (coverPath == null) "Add cover photo" else "Change cover")
                            }
                            DropdownMenu(expanded = coverMenu, onDismissRequest = { coverMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Choose photo…") },
                                    onClick = {
                                        coverMenu = false
                                        pickCover.launch("image/*")
                                    },
                                )
                                if (coverPath != null) {
                                    DropdownMenuItem(
                                        text = { Text("Remove photo") },
                                        onClick = {
                                            coverMenu = false
                                            scope.launch {
                                                File(coverPath!!).delete()
                                                container.library.setPlaylistCover(playlistId, null)
                                                reload()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        "No songs yet — long-press songs in your library to add them here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
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
                                reload()
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
