package com.cadence.music.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.WriteConsentRequired
import com.cadence.music.data.db.ArtistTile
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.metadata.ListenBrainz
import com.cadence.music.data.stats.ArtistPlays
import com.cadence.music.data.stats.ListeningStats
import com.cadence.music.data.stats.computeStats
import com.cadence.music.data.stats.formatListenMinutes
import com.cadence.music.data.stats.mergeRecentPlays
import com.cadence.music.data.tags.artistCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    container: AppContainer,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val player = container.player
    val tracks by container.library.tracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val albumGroups by container.library.albumGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by container.library.artistTiles()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { player.shuffleAll(tracks.map { it.toTrack() }) },
                    icon = { Icon(Icons.Filled.Shuffle, null) },
                    text = { Text("Shuffle all") },
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // Collapsing-style large header
            Text(
                "Library",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )

            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                listOf("Songs", "Albums", "Artists", "Stats").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        modifier = Modifier.padding(horizontal = 20.dp),
                        text = { Text(label) },
                    )
                }
            }

            when (tab) {
                0 -> songsTab(container, onArtistClick, onAlbumClick, player)
                1 -> albumsTab(albumGroups, container, onAlbumClick)
                2 -> artistsTab(artists, onArtistClick)
                3 -> statsTab(container, onArtistClick)
            }
        }
    }
}

fun formatStreamTag(
    prefs: com.cadence.music.data.prefs.Prefs,
    sourceId: String,
    serverId: String,
): String {
    val typeName = sourceId.lowercase()
    val entryId = com.cadence.music.data.entryIdOf(serverId)
    val entry = entryId?.let { prefs.entry(it) }
    val customName = entry?.customName?.trim()?.ifBlank { null }
        ?: (if (sourceId == "telegram") entry?.user?.trim()?.takeIf { it != "Telegram Music" && it.isNotBlank() } else null)
    return if (prefs.showServerNameInStreamTag && customName != null) {
        "Stream ($customName/$typeName)"
    } else {
        "Stream ($typeName)"
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun TrackRow(
    container: AppContainer,
    track: TrackEntity,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    isDownloading: Boolean = false,
    onClick: () -> Unit,
) {
    val art by produceState<String?>(null, track.id) {
        value = container.artResolver.urlFor(track)
    }
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { showSheet = true })
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
                else -> listOfNotNull(track.artistName.ifBlank { null }, formatStreamTag(container.prefs, track.sourceId, track.serverId))
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
        // A running download replaces the duration with a live spinner — the
        // trigger point must show feedback, not a dead icon.
        if (isDownloading) {
            CircularProgressIndicator(Modifier.size(18.dp))
        } else {
            Text(
                formatDuration(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSheet) {
        TrackActionsSheet(
            container = container,
            track = track,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    container: AppContainer,
    track: TrackEntity,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(true) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    var pendingDelete by remember { mutableStateOf(false) }

    // Featuring names beyond the primary artist that actually own a library
    // page. The raw tag is authoritative when present; blank counts as absent.
    val extraArtists by produceState<List<String>>(emptyList(), track.id) {
        value = withContext(Dispatchers.IO) {
            val raw = track.artistRaw?.takeIf { it.isNotBlank() } ?: track.artistName
            val candidates = artistCandidates(raw).filter { !it.equals(track.artistName, true) }
            if (candidates.isEmpty()) emptyList()
            else container.library.artistsWithPages(candidates)
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun close() {
        sheetVisible = false
        onDismiss()
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.library.setTrackCover(track.id, uri)
            toast(if (ok) "Cover updated" else "Couldn't set cover")
            if (ok) close()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    if (pendingDelete) {
                        val ok = container.library.deleteLocalFile(track)
                        toast(if (ok) "File deleted" else "Couldn't delete file")
                        if (ok) close()
                    } else {
                        pendingEdit?.let { e ->
                            val ok = container.library.updateTrackMetadata(track.id, e.title, e.artist, e.album, e.genre)
                            toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                            if (ok) {
                                showEdit = false
                                close()
                            }
                        }
                    }
                } catch (e: WriteConsentRequired) {
                    toast("Couldn't complete — permission denied")
                } finally {
                    pendingEdit = null
                    pendingDelete = false
                }
            } else {
                toast("Not changed — permission denied")
                pendingEdit = null
                pendingDelete = false
            }
        }
    }

    fun saveEdit(t: String, a: String, al: String, g: String) {
        scope.launch {
            try {
                val ok = container.library.updateTrackMetadata(track.id, t, a, al, g)
                toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                if (ok) {
                    showEdit = false
                    close()
                }
            } catch (e: WriteConsentRequired) {
                pendingEdit = PendingEdit(t, a, al, g)
                consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }

    fun deleteLocal() {
        scope.launch {
            try {
                val ok = container.library.deleteLocalFile(track)
                toast(if (ok) "File deleted" else "Couldn't delete file")
                if (ok) close()
            } catch (e: WriteConsentRequired) {
                pendingDelete = true
                consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }

    fun deleteDownloaded() {
        scope.launch {
            val dl = container.database.downloadDao().byTrack(track.sourceId, track.serverId)
            if (dl != null) container.library.deleteDownload(dl, track)
            else container.database.trackDao().setPath(track.id, null)
            toast("Download deleted")
            close()
        }
    }

    if (sheetVisible) {
        val playlists by container.library.playlists()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        var showNew by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { close() }) {
            if (track.albumName.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Go to album: ${track.albumName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Filled.Album, null) },
                    modifier = Modifier.clickable {
                        onAlbumClick(track.albumNorm)
                        close()
                    },
                )
            }
            if (track.artistName.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Go to artist: ${track.artistName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    modifier = Modifier.clickable {
                        onArtistClick(track.artistName)
                        close()
                    },
                )
            }
            extraArtists.forEach { name ->
                ListItem(
                    headlineContent = { Text("Go to artist: $name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Featuring") },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    modifier = Modifier.clickable {
                        onArtistClick(name)
                        close()
                    },
                )
            }
            if (showNew) {
                NewPlaylistDialog(
                    onCreate = { name ->
                        showNew = false
                        scope.launch {
                            val id = container.library.createPlaylist(name)
                            if (id > 0) container.library.addToPlaylist(id, track.id)
                            close()
                        }
                    },
                    onDismiss = { showNew = false },
                )
            } else {
                if (track.sourceId != "local" && track.path == null) {
                    ListItem(
                        headlineContent = { Text("Download for offline") },
                        leadingContent = { Icon(Icons.Filled.Download, null) },
                        modifier = Modifier.clickable {
                            container.library.enqueueDownload(track)
                            close()
                        },
                    )
                }
                if (track.sourceId == "local") {
                    ListItem(
                        headlineContent = { Text("Edit metadata") },
                        leadingContent = { Icon(Icons.Filled.Edit, null) },
                        modifier = Modifier.clickable {
                            sheetVisible = false
                            showEdit = true
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text("Set custom cover") },
                    leadingContent = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                    modifier = Modifier.clickable { coverPicker.launch("image/*") },
                )
                if (track.sourceId == "local" || track.path != null) {
                    ListItem(
                        headlineContent = { Text("Delete file") },
                        leadingContent = { Icon(Icons.Filled.Delete, null) },
                        modifier = Modifier.clickable {
                            if (track.sourceId == "local") {
                                sheetVisible = false
                                showDeleteConfirm = true
                            } else {
                                deleteDownloaded()
                            }
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text("Play next") },
                    leadingContent = { Icon(Icons.Filled.SkipNext, null) },
                        modifier = Modifier.clickable {
                            container.player.playNext(track.toTrack())
                            close()
                        },
                )
                ListItem(
                    headlineContent = { Text("Add to queue") },
                    leadingContent = { Icon(Icons.Filled.PlaylistAdd, null) },
                        modifier = Modifier.clickable {
                            container.player.addToQueue(track.toTrack())
                            close()
                        },
                )
                Text(
                    "Add to playlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                ListItem(
                    headlineContent = { Text("New playlist…") },
                    leadingContent = { Icon(Icons.Filled.Add, null) },
                    modifier = Modifier.clickable { showNew = true },
                )
                LazyColumn(Modifier.padding(bottom = 32.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = {
                                Text(if (p.trackCount == 1) "1 song" else "${p.trackCount} songs")
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    container.library.addToPlaylist(p.id, track.id)
                                    close()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showEdit) {
        EditMetadataDialog(
            container = container,
            track = track,
            onDismiss = { showEdit = false; onDismiss() },
            onSave = { t, a, al, g -> saveEdit(t, a, al, g) },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; onDismiss() },
            title = { Text("Delete from device?") },
            text = { Text("Removes the file and its library entry.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deleteLocal()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false; onDismiss() }) { Text("Cancel") } },
        )
    }
}

private data class PendingEdit(val title: String, val artist: String, val album: String, val genre: String)

@Composable
private fun EditMetadataDialog(
    container: AppContainer,
    track: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artistName) }
    var album by remember { mutableStateOf(track.albumName) }
    var genre by remember { mutableStateOf("") }
    var showLyrics by remember { mutableStateOf(false) }
    // Genre lives only in the file (no DB column); read the current value once.
    LaunchedEffect(track.id) {
        genre = withContext(Dispatchers.IO) {
            runCatching {
                val uri = track.path?.let { android.net.Uri.parse(it) } ?: return@runCatching ""
                container.library.readFileGenre(uri)
            }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true)
                TextButton(onClick = { showLyrics = true }) { Text("Edit lyrics") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, artist, album, genre) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (showLyrics) {
        LyricsEditorDialog(
            container = container,
            track = track,
            onDismiss = { showLyrics = false },
        )
    }
}

@Composable
private fun LyricsEditorDialog(container: AppContainer, track: TrackEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        text = withContext(Dispatchers.IO) {
            container.database.lyricsDao().byTrackId(track.id)?.syncedLrc.orEmpty()
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics") },
        text = {
            OutlinedTextField(
                text, { text = it },
                label = { Text(if (loaded) "Plain text" else "Loading…") },
                enabled = loaded,
                minLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    container.library.saveUserLyrics(track.id, text)
                    Toast.makeText(context, "Lyrics saved", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyLibrary(granted: Boolean, deniedForever: Boolean, onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val title = when {
            deniedForever -> "Audio access is turned off"
            granted -> "No music found"
            else -> "Your library is empty"
        }
        val subtitle = when {
            deniedForever -> "Allow Cadence to read your music files in system settings, or connect a server in Settings."
            granted -> "No audio files were found on this device, or connect a server in Settings."
            else -> "Grant access to your music files, or connect a server in Settings."
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!granted) {
            Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (deniedForever) "Open settings" else "Grant audio access")
            }
        }
    }
}

@Composable
private fun songsTab(
    container: AppContainer,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    player: com.cadence.music.playback.PlayerConnection,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var audioGranted by remember { mutableStateOf(isGranted(context, audioPermission())) }
    var audioAsked by rememberSaveable { mutableStateOf(false) }
    var deniedForever by rememberSaveable { mutableStateOf(false) }
    var notifAsked by rememberSaveable { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
        if (!granted) {
            context.hostActivity()?.let { deniedForever = permanentlyDenied(it, audioPermission()) }
        } else {
            container.library.launchSync()
        }
        askNotifications()
    }

    // Ask once per session on first entry; never nag again until re-entry.
    LaunchedEffect(Unit) {
        when {
            audioGranted -> askNotifications()
            !audioAsked -> {
                audioAsked = true
                audioLauncher.launch(audioPermission())
            }
        }
    }

    // Return-from-Settings grants/denies must be reflected; remembered state goes stale.
    LifecycleResumeEffect(Unit) {
        audioGranted = isGranted(context, audioPermission())
        context.hostActivity()?.let {
            deniedForever = !audioGranted && permanentlyDenied(it, audioPermission())
        } ?: run { if (audioGranted) deniedForever = false }
        onPauseOrDispose { }
    }

    var sort by remember { mutableStateOf(container.prefs.songSort) }
    var ascending by remember { mutableStateOf(container.prefs.songSortAscending) }
    var sortMenu by remember { mutableStateOf(false) }
    // cachedIn lives INSIDE remember: called inline during composition it would
    // return a new Flow instance every recomposition, restarting the pager
    // collection (and its refresh) in a loop — refresh would never settle.
    val pagingItems = remember(sort, ascending) {
        container.library.tracksPaged(sort, ascending).cachedIn(scope)
    }.collectAsLazyPagingItems()
    val songCount by container.library.observeTrackCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    // Running-download keys collected once at the list level, never per item.
    val downloadRows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runningDownloads = remember(downloadRows) {
        downloadRows.filter { it.download.status == "running" }
            .mapTo(mutableSetOf()) { "${it.download.sourceId}:${it.download.trackServerId}" }
    }

    // Empty gate on the mode-aware count + pager state (never on the initial 0
    // while still Loading — the spinner below covers that).
    if (songCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading) {
        EmptyLibrary(audioGranted, deniedForever) {
            if (!audioGranted) {
                if (deniedForever) openAppSettings(context) else audioLauncher.launch(audioPermission())
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$songCount song" + if (songCount == 1) "" else "s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(onClick = { sortMenu = true }) {
                Icon(Icons.Filled.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                val sortLabel = when (sort) {
                    com.cadence.music.data.prefs.Prefs.SongSort.TITLE -> "Title"
                    com.cadence.music.data.prefs.Prefs.SongSort.ARTIST -> "Artist"
                    com.cadence.music.data.prefs.Prefs.SongSort.ALBUM -> "Album"
                    com.cadence.music.data.prefs.Prefs.SongSort.DURATION -> "Duration"
                    com.cadence.music.data.prefs.Prefs.SongSort.RECENTLY_ADDED -> "Recently added"
                    com.cadence.music.data.prefs.Prefs.SongSort.RECENTLY_PLAYED -> "Recently played"
                    com.cadence.music.data.prefs.Prefs.SongSort.MOST_PLAYED -> "Most played"
                }
                Text(
                    sortLabel + if (ascending) " ↑" else " ↓",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Sort by $sortLabel, ${if (ascending) "ascending" else "descending"}"
                    },
                )
            }
            androidx.compose.material3.DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                listOf(
                    com.cadence.music.data.prefs.Prefs.SongSort.TITLE to "Title",
                    com.cadence.music.data.prefs.Prefs.SongSort.ARTIST to "Artist",
                    com.cadence.music.data.prefs.Prefs.SongSort.ALBUM to "Album",
                    com.cadence.music.data.prefs.Prefs.SongSort.DURATION to "Duration",
                    com.cadence.music.data.prefs.Prefs.SongSort.RECENTLY_ADDED to "Recently added",
                    com.cadence.music.data.prefs.Prefs.SongSort.RECENTLY_PLAYED to "Recently played",
                    com.cadence.music.data.prefs.Prefs.SongSort.MOST_PLAYED to "Most played",
                ).forEach { (s, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (s == sort) "✓ $label" else label) },
                        onClick = {
                            sort = s
                            container.prefs.songSort = s
                        },
                    )
                }
                HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (ascending) "Ascending ✓" else "Descending ✓") },
                    onClick = {
                        ascending = !ascending
                        container.prefs.songSortAscending = ascending
                    },
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.id },
            ) { i ->
                pagingItems[i]?.let { track ->
                    TrackRow(
                        container,
                        track,
                        onArtistClick,
                        onAlbumClick,
                        isDownloading = "${track.sourceId}:${track.serverId}" in runningDownloads,
                    ) { player.playNow(listOf(track.toTrack())) }
                }
            }
            when (pagingItems.loadState.refresh) {
                is LoadState.Loading -> if (pagingItems.itemCount == 0) {
                    item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    } }
                }
                is LoadState.Error -> if (pagingItems.itemCount == 0) {
                    item { Text(
                        "Couldn't load songs.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp),
                    ) }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun albumsTab(
    albums: List<com.cadence.music.data.db.AlbumGroup>,
    container: AppContainer,
    onAlbumClick: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(albums, key = { it.norm }) { album ->
            val art by produceState<String?>(null, album.norm) {
                value = container.library.tracksByAlbumNorm(album.norm).firstOrNull()
                    ?.let { container.artResolver.urlFor(it) }
            }
            Column(Modifier.clickable { if (album.norm.isNotBlank()) onAlbumClick(album.norm) }) {
                AsyncImage(
                    model = art,
                    contentDescription = album.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Text(
                    album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    album.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun artistsTab(tiles: List<ArtistTile>, onArtistClick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(tiles, key = { it.name }) { tile ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(tile.name) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (tile.imageUrl == null) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tile.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    AsyncImage(
                        model = tile.imageUrl,
                        contentDescription = tile.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Text(
                    text = tile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Everything the Stats tab renders, loaded once on entry: local aggregation plus an
 * optional ListenBrainz merge. Merge honesty: the play total is never local+LB added
 * together — when LB stats are present they replace the local count and [playsFromLb]
 * labels the source. Any LB failure falls back silently to the pure-local view.
 */
private data class StatsData(
    val stats: ListeningStats,
    val topArtists: List<ArtistPlays>,
    val recents: List<Pair<Long, Pair<String, String>>>, // (timestampMs, (artist, title))
    val totalPlays: Long,
    val playsFromLb: Boolean,
)

@Composable
private fun statsTab(container: AppContainer, onArtistClick: (String) -> Unit) {
    val data by produceState<StatsData?>(null) {
        value = withContext(Dispatchers.IO) {
            val dao = container.database.trackDao()
            val local = computeStats(dao.playRows(), System.currentTimeMillis())
            val localTop = dao.topArtists()
            val localRecents = dao.recentlyPlayed()
                .map { (it.lastPlayed ?: 0L) to (it.artistName to it.title) }
            var lbTotal: Long? = null
            var lbTop: List<ArtistPlays>? = null
            var lbRecents: List<Pair<Long, Pair<String, String>>> = emptyList()
            val token = container.prefs.listenBrainzToken?.trim()
            if (!token.isNullOrEmpty()) {
                val verdict = runCatching { ListenBrainz.validateBlocking(token) }.getOrNull()
                if (verdict != null && verdict.first) {
                    val user = verdict.second.orEmpty()
                    val lbStats = runCatching { ListenBrainz.lbStatsBlocking(user) }.getOrNull()
                    if (lbStats != null) {
                        lbTotal = lbStats.totalListens
                        if (lbStats.topArtists.isNotEmpty()) lbTop = lbStats.topArtists
                    }
                    lbRecents = runCatching { ListenBrainz.recentListensBlocking(user) }
                        .getOrDefault(emptyList())
                        .map { (it.listenedAtSec * 1000) to (it.artist to it.title) }
                }
            }
            StatsData(
                stats = local,
                topArtists = lbTop ?: localTop,
                recents = mergeRecentPlays(localRecents, lbRecents, cap = 6),
                totalPlays = lbTotal ?: local.totalPlays.toLong(),
                playsFromLb = lbTotal != null,
            )
        }
    }
    val d = data
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        if (d == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }
        if (d.totalPlays == 0L && d.topArtists.isEmpty() && d.recents.isEmpty()) {
            item {
                Text(
                    "Play something — your listening stats will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            return@LazyColumn
        }
        val s = d.stats
        item {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(20.dp),
            ) {
                Text(
                    "${formatListenMinutes(s.totalMinutes)} listened",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    if (d.playsFromLb) "${d.totalPlays} plays"
                    else "${d.totalPlays} plays · ${s.uniquePlayed} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    if (d.playsFromLb) "on ListenBrainz" else "from your library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    "${s.playsLast7Days} plays this week · ${s.weekStreak}-week streak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (d.topArtists.isNotEmpty()) {
            item { StatsSectionHeader("Top artists") }
            itemsIndexed(d.topArtists) { i, artist ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onArtistClick(artist.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        "${artist.plays} plays",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (d.recents.isNotEmpty()) {
            item { StatsSectionHeader("Recently played") }
            itemsIndexed(d.recents) { _, recent ->
                val (artist, title) = recent.second
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = artist.isNotBlank()) { onArtistClick(artist) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
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
