package com.cadence.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.launch

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
    val artists by container.library.artistNames()
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

            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                listOf("Songs", "Albums", "Artists").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label) },
                    )
                }
            }

            when (tab) {
                0 -> songsTab(container, onArtistClick, player)
                1 -> albumsTab(albumGroups, container, onAlbumClick)
                2 -> artistsTab(artists, onArtistClick)
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
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
    var showAddToPlaylist by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { showAddToPlaylist = true })
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

    if (showAddToPlaylist) {
        val playlists by container.library.playlists()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        var showNew by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { showAddToPlaylist = false }) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (showNew) {
                NewPlaylistDialog(
                    onCreate = { name ->
                        showNew = false
                        scope.launch {
                            val id = container.library.createPlaylist(name)
                            if (id > 0) container.library.addToPlaylist(id, track.id)
                            showAddToPlaylist = false
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
                            showAddToPlaylist = false
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text("Play next") },
                    leadingContent = { Icon(Icons.Filled.SkipNext, null) },
                    modifier = Modifier.clickable {
                        container.player.playNext(track.toTrack())
                        showAddToPlaylist = false
                    },
                )
                ListItem(
                    headlineContent = { Text("Add to queue") },
                    leadingContent = { Icon(Icons.Filled.PlaylistAdd, null) },
                    modifier = Modifier.clickable {
                        container.player.addToQueue(track.toTrack())
                        showAddToPlaylist = false
                    },
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
                                    showAddToPlaylist = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
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
            scope.launch { container.library.syncAll() }
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
                    TrackRow(container, track, onArtistClick) { player.playNow(listOf(track.toTrack())) }
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
private fun artistsTab(artists: List<String>, onArtistClick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it }) { name ->
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
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
