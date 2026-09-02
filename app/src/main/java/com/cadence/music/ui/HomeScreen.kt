package com.cadence.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Start tab: a greeting, quick tiles, and art-first shelves of your library. */
@Composable
fun HomeScreen(
    container: AppContainer,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    val player = container.player
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var most by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var added by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var total by remember { mutableStateOf(0) }

    // Reload whenever the playing track changes — stats move as you listen.
    val nowTitle by player.state.collectAsStateWithLifecycle()
    LaunchedEffect(nowTitle.title) {
        val dao = container.database.trackDao()
        withContext(Dispatchers.IO) {
            recent = dao.recentlyPlayed()
            most = dao.mostPlayed()
            added = dao.recentlyAdded()
            total = dao.count()
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { GreetingBanner(total, onShuffleAll = {
                scope.launch {
                    val all = withContext(Dispatchers.IO) { container.library.tracks().first() }
                    container.player.shuffleAll(all.map { it.toTrack() })
                }
            }, modifier = Modifier.padding(16.dp)) }

            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile("Library", Icons.Outlined.LibraryMusic, Modifier.weight(1f), onOpenLibrary)
                    QuickTile("Playlists", Icons.AutoMirrored.Filled.QueueMusic, Modifier.weight(1f), onOpenPlaylists)
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile("Search", Icons.Filled.Search, Modifier.weight(1f), onOpenSearch)
                    QuickTile("Downloads", Icons.Outlined.Download, Modifier.weight(1f), onOpenDownloads)
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    TrackShelf(container, recent) { track ->
                        player.playNow(listOf(track.toTrack()))
                    }
                }
            }
            if (most.isNotEmpty()) {
                item { SectionHeader("Most played") }
                item {
                    TrackShelf(container, most) { track ->
                        player.playNow(listOf(track.toTrack()))
                    }
                }
            }
            if (added.isNotEmpty()) {
                item { SectionHeader("Recently added") }
                item {
                    TrackShelf(container, added) { track ->
                        player.playNow(listOf(track.toTrack()))
                    }
                }
            }

            if (recent.isEmpty() && most.isEmpty() && added.isEmpty() && total > 0) {
                item {
                    Text(
                        "Play something and your history will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            if (total == 0) {
                item {
                    Text(
                        "Your library is empty — connect a server in Settings or grant audio access in Library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

private fun greeting(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
private fun GreetingBanner(total: Int, onShuffleAll: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    )
                )
            )
            .padding(20.dp),
    ) {
        Column {
            Text(
                greeting(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "$total tracks in your library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onShuffleAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Shuffle, null, Modifier.padding(end = 6.dp).size(18.dp))
                Text("Shuffle all")
            }
        }
    }
}

@Composable
private fun QuickTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Horizontal row of square art cards (no text — the art is the label). */
@Composable
private fun TrackShelf(container: AppContainer, tracks: List<TrackEntity>, onPlay: (TrackEntity) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            ShelfCard(container, track) { onPlay(track) }
        }
    }
}

@Composable
private fun ShelfCard(container: AppContainer, track: TrackEntity, onClick: () -> Unit) {
    val art by produceState<String?>(null, track.id) {
        value = withContext(Dispatchers.IO) { container.artResolver.urlFor(track) }
    }
    Column(
        Modifier
            .width(116.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = art,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            track.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            track.artistName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
