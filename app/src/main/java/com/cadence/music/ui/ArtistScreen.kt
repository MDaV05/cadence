package com.cadence.music.ui

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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity

@Composable
fun ArtistScreen(container: AppContainer, artistName: String, onAlbumClick: (String) -> Unit = {}) {
    val player = container.player
    var tracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var info by remember { mutableStateOf<com.cadence.music.data.metadata.ArtistInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(artistName) {
        loading = true; error = false
        try {
            tracks = container.library.tracksByArtist(artistName)
            info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Cache-first: the background worker pre-fills this table; a miss
                // fetches once and is stored so later visits are instant.
                val dao = container.database.artistInfoDao()
                val cached = dao.byName(artistName)
                when {
                    cached != null ->
                        com.cadence.music.data.metadata.ArtistInfo(cached.bio, cached.imageUrl)
                    else -> {
                        val fetched =
                            com.cadence.music.data.metadata.Wikipedia.artistInfoBlocking(artistName)
                        dao.upsert(
                            com.cadence.music.data.db.ArtistInfoEntity(
                                name = artistName, bio = fetched?.bio, imageUrl = fetched?.imageUrl,
                            )
                        )
                        fetched
                    }
                }
            }
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

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
                        contentDescription = artistName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(artistName, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${albums.size} albums • ${tracks.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
