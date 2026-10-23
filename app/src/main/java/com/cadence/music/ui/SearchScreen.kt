package com.cadence.music.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.source.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnlineFilter(val label: String) {
    ALL("All"),
    YT_MUSIC("YouTube Music"),
    SOUNDCLOUD("SoundCloud"),
}

/**
 * Universal search screen supporting both local synced library tracks and
 * online sources (YouTube Music and SoundCloud) with streaming, playlists, and downloads.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    container: AppContainer,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val player = container.player
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    var searchTab by rememberSaveable { mutableStateOf(0) }
    var onlineFilter by rememberSaveable { mutableStateOf(OnlineFilter.ALL) }
    var targetPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(query) {
        delay(300)
        debounced = query
    }
    var history by remember { mutableStateOf(container.prefs.searchHistory) }

    // Running-download keys collected once at the list level, never per item.
    val downloadRows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runningDownloads = remember(downloadRows) {
        downloadRows.filter { it.download.status == "running" }
            .mapTo(mutableSetOf()) { "${it.download.sourceId}:${it.download.trackServerId}" }
    }

    var onlineResults by remember { mutableStateOf<List<Track>?>(null) }
    var isSearchingOnline by remember { mutableStateOf(false) }

    LaunchedEffect(debounced, onlineFilter, searchTab) {
        if (searchTab != 1 || debounced.isBlank()) {
            if (debounced.isBlank()) onlineResults = null
            isSearchingOnline = false
            return@LaunchedEffect
        }
        isSearchingOnline = true
        try {
            val results = withContext(Dispatchers.IO) {
                when (onlineFilter) {
                    OnlineFilter.ALL -> {
                        val ytDeferred = async { runCatching { container.ytMusicSource.search(debounced) }.getOrDefault(emptyList()) }
                        val scDeferred = async { runCatching { container.soundCloudSource.search(debounced) }.getOrDefault(emptyList()) }
                        val ytResults = ytDeferred.await()
                        val scResults = scDeferred.await()
                        val combined = mutableListOf<Track>()
                        val maxLen = maxOf(ytResults.size, scResults.size)
                        for (i in 0 until maxLen) {
                            if (i < ytResults.size) combined.add(ytResults[i])
                            if (i < scResults.size) combined.add(scResults[i])
                        }
                        combined
                    }
                    OnlineFilter.YT_MUSIC -> {
                        runCatching { container.ytMusicSource.search(debounced) }.getOrDefault(emptyList())
                    }
                    OnlineFilter.SOUNDCLOUD -> {
                        runCatching { container.soundCloudSource.search(debounced) }.getOrDefault(emptyList())
                    }
                }
            }
            onlineResults = results
        } catch (_: Exception) {
            onlineResults = emptyList()
        } finally {
            isSearchingOnline = false
        }
    }

    fun recordQuery(q: String) {
        if (q.isBlank()) return
        history = (listOf(q) + history.filter { !it.equals(q, true) }).take(10)
        container.prefs.searchHistory = history
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Songs, artists, albums") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, "Clear search")
                        }
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            TabRow(
                selectedTabIndex = searchTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = searchTab == 0,
                    onClick = { searchTab = 0 },
                    text = { Text("In Library", style = MaterialTheme.typography.labelLarge) },
                )
                Tab(
                    selected = searchTab == 1,
                    onClick = { searchTab = 1 },
                    text = { Text("Online", style = MaterialTheme.typography.labelLarge) },
                )
            }

            if (searchTab == 0) {
                if (query.isBlank()) {
                    if (history.isEmpty()) {
                        Text(
                            "Search your library. Recent searches show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Text(
                                "Recent",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                history = emptyList()
                                container.prefs.searchHistory = emptyList()
                            }) { Text("Clear") }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            history.forEach { h ->
                                SuggestionChip(
                                    onClick = { query = h },
                                    label = { Text(h) },
                                )
                            }
                        }
                    }
                } else {
                    if (debounced.isBlank()) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val pagingItems = remember(debounced) {
                            container.library.searchPaged(debounced).cachedIn(scope)
                        }.collectAsLazyPagingItems()
                        Text(
                            "${pagingItems.itemCount} result${if (pagingItems.itemCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        LazyColumn {
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
                                    ) {
                                        recordQuery(query)
                                        focusManager.clearFocus()
                                        player.playNow(listOf(track.toTrack()))
                                    }
                                }
                            }
                            when (pagingItems.loadState.refresh) {
                                is LoadState.Loading -> if (pagingItems.itemCount == 0) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                                is LoadState.Error -> if (pagingItems.itemCount == 0) {
                                    item {
                                        Text(
                                            "Couldn't load results.",
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(32.dp),
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnlineFilter.values().forEach { filter ->
                        FilterChip(
                            selected = onlineFilter == filter,
                            onClick = { onlineFilter = filter },
                            label = { Text(filter.label) },
                        )
                    }
                }

                if (query.isBlank()) {
                    if (history.isEmpty()) {
                        Text(
                            "Search online music from YouTube Music and SoundCloud.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Text(
                                "Recent",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                history = emptyList()
                                container.prefs.searchHistory = emptyList()
                            }) { Text("Clear") }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            history.forEach { h ->
                                SuggestionChip(
                                    onClick = { query = h },
                                    label = { Text(h) },
                                )
                            }
                        }
                    }
                } else if (isSearchingOnline || debounced.isBlank()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val results = onlineResults
                    if (results == null || results.isEmpty()) {
                        Text(
                            "No online tracks found for '$debounced'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    } else {
                        Text(
                            "${results.size} result${if (results.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        LazyColumn {
                            items(results, key = { "${it.sourceId}:${it.key}" }) { track ->
                                val cleanKey = track.key.removePrefix("${track.sourceId}:")
                                OnlineTrackRow(
                                    track = track,
                                    isDownloading = runningDownloads.any { it.endsWith(":$cleanKey") },
                                    onClick = {
                                        recordQuery(query)
                                        focusManager.clearFocus()
                                        player.playNow(listOf(track))
                                    },
                                    onAddToPlaylist = {
                                        targetPlaylistTrack = track
                                    },
                                    onDownload = {
                                        scope.launch {
                                            container.library.downloadOnlineTrack(track)
                                            Toast.makeText(context, "Downloading ${track.title}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    targetPlaylistTrack?.let { track ->
        OnlineAddToPlaylistSheet(
            container = container,
            track = track,
            onDismiss = { targetPlaylistTrack = null },
        )
    }
}

@Composable
fun OnlineSourceBadge(sourceId: String, modifier: Modifier = Modifier) {
    val isYt = sourceId == "ytmusic"
    val badgeText = if (isYt) "YT Music" else "SoundCloud"
    val containerColor = if (isYt) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    }
    val contentColor = if (isYt) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun OnlineTrackRow(
    track: Track,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumKey,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                OnlineSourceBadge(track.sourceId)
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = {
                        menuExpanded = false
                        onAddToPlaylist()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = {
                        menuExpanded = false
                        onDownload()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineAddToPlaylistSheet(
    container: AppContainer,
    track: Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by container.library.playlists()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showNew by remember { mutableStateOf(false) }

    if (showNew) {
        NewPlaylistDialog(
            onCreate = { name ->
                showNew = false
                if (name.isNotBlank()) {
                    scope.launch {
                        val newId = container.library.createPlaylist(name)
                        if (newId > 0) {
                            val entity = container.library.saveOnlineTrack(track)
                            container.library.addToPlaylist(newId, entity.id)
                            Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                } else {
                    onDismiss()
                }
            },
            onDismiss = { showNew = false },
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Text(
                "Add to Playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                                val entity = container.library.saveOnlineTrack(track)
                                container.library.addToPlaylist(p.id, entity.id)
                                Toast.makeText(context, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}
