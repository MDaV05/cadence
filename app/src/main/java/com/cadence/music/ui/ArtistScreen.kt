package com.cadence.music.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.WriteConsentRequired
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.tags.primaryArtist
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(container: AppContainer, artistName: String, onAlbumClick: (String) -> Unit = {}) {
    val player = container.player
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentName by remember(artistName) { mutableStateOf(artistName) }
    var tracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var info by remember { mutableStateOf<com.cadence.music.data.metadata.ArtistInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pendingRename by remember { mutableStateOf<String?>(null) }
    var showBioEdit by remember { mutableStateOf(false) }
    var bioValue by remember { mutableStateOf("") }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val picturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.library.setArtistPicture(currentName, uri)
            toast(if (ok) "Picture updated" else "Couldn't set picture")
            if (ok) info = container.library.artistDisplayInfo(currentName)
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                pendingRename?.let { name ->
                    try {
                        val ok = container.library.renameArtistTracks(currentName, name)
                        toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                        if (ok) {
                            showRename = false
                            currentName = primaryArtist(name.trim()).ifBlank { name.trim() }
                        }
                    } catch (e: WriteConsentRequired) {
                        toast("Couldn't complete — permission denied")
                    }
                }
            } else {
                toast("Not changed — permission denied")
            }
            pendingRename = null
        }
    }

    fun doRename(name: String) {
        scope.launch {
            try {
                val ok = container.library.renameArtistTracks(currentName, name)
                toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                if (ok) {
                    showRename = false
                    currentName = primaryArtist(name.trim()).ifBlank { name.trim() }
                }
            } catch (e: WriteConsentRequired) {
                pendingRename = name
                consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }

    LaunchedEffect(currentName) {
        loading = true; error = false
        try {
            tracks = container.library.tracksByArtist(currentName)
            info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Override-aware read; a fetch miss stores nothing so the next
                // visit retries instead of serving a poisoned all-null row.
                container.library.artistDisplayInfo(currentName) ?: run {
                    val fetched =
                        com.cadence.music.data.metadata.Wikipedia.artistInfoBlocking(currentName)
                    if (fetched?.bio != null || fetched?.imageUrl != null) {
                        container.database.artistInfoDao().upsert(
                            com.cadence.music.data.db.ArtistInfoEntity(
                                name = currentName, bio = fetched?.bio, imageUrl = fetched?.imageUrl,
                            )
                        )
                    }
                    fetched
                }
            }
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

    val allLocal = tracks.isNotEmpty() && tracks.all { it.sourceId == "local" }

    val albums = remember(tracks) {
        // byArtist is ordered by albumName; groupBy preserves first-seen order.
        tracks.groupBy { it.albumNorm }
    }

    Scaffold { padding ->
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't load this artist.", color = MaterialTheme.colorScheme.error)
            }
            else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = info?.imageUrl,
                        contentDescription = currentName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(currentName, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${albums.size} albums • ${tracks.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (allLocal) {
                        IconButton(onClick = {
                            renameValue = currentName
                            showRename = true
                        }) {
                            Icon(Icons.Filled.Edit, "Rename artist")
                        }
                    }
                    IconButton(onClick = {
                        bioValue = info?.bio.orEmpty()
                        showBioEdit = true
                    }) {
                        Icon(Icons.Filled.Description, "Edit bio")
                    }
                    IconButton(onClick = { picturePicker.launch("image/*") }) {
                        Icon(Icons.Filled.AddPhotoAlternate, "Set picture")
                    }
                    IconButton(onClick = { player.shuffleAll(tracks.map { it.toTrack() }) }) {
                        Icon(Icons.Filled.Shuffle, "Shuffle artist", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            info?.bio?.let { bio ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Albums", style = MaterialTheme.typography.titleMedium)
                }
                items(albums.entries.toList(), key = { "album:${it.key}" }) { (norm, albumTracks) ->
                    AlbumCell(
                        container,
                        albumNorm = norm,
                        displayName = albumTracks.first().albumName,
                        tracks = albumTracks,
                    ) {
                        if (it.isNotBlank()) onAlbumClick(it)
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("All songs", style = MaterialTheme.typography.titleMedium)
            }
            items(tracks, key = { "song:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { track ->
                TrackRow(container, track) { player.playNow(listOf(track.toTrack())) }
            }
            if (tracks.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No songs found for this artist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename artist") },
            text = {
                OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = { TextButton(onClick = { doRename(renameValue) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showBioEdit) {
        AlertDialog(
            onDismissRequest = { showBioEdit = false },
            title = { Text("Edit bio") },
            text = {
                OutlinedTextField(bioValue, { bioValue = it }, label = { Text("Bio") }, minLines = 3)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.library.setArtistBio(currentName, bioValue)
                        info = container.library.artistDisplayInfo(currentName)
                        showBioEdit = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showBioEdit = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlbumCell(
    container: AppContainer,
    albumNorm: String,
    displayName: String,
    tracks: List<TrackEntity>,
    onClick: (String) -> Unit,
) {
    val art by produceState<String?>(null, albumNorm) {
        value = tracks.firstOrNull()?.let { container.artResolver.urlFor(it) }
    }
    Column(Modifier.clickable { onClick(albumNorm) }) {
        AsyncImage(
            model = art,
            contentDescription = displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
