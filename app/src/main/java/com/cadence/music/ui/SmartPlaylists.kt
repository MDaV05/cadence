package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A preset, rule-based playlist: the id routes to it, the flows come straight
 * from TrackDao — the queries are the playlist, nothing is stored.
 */
data class SmartPlaylist(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val tracks: Flow<List<TrackEntity>>,
    val count: Flow<Int>,
)

fun smartPlaylists(container: AppContainer): List<SmartPlaylist> {
    val dao = container.database.trackDao()
    return listOf(
        SmartPlaylist(
            "smart_most_played",
            "Most played",
            Icons.AutoMirrored.Filled.TrendingUp,
            dao.observeMostPlayed(),
            dao.observeMostPlayedCount(),
        ),
        SmartPlaylist(
            "smart_recently_added",
            "Recently added",
            Icons.Filled.Schedule,
            dao.observeRecentlyAdded(),
            dao.observeRecentlyAddedCount(),
        ),
        SmartPlaylist(
            "smart_recently_played",
            "Recently played",
            Icons.Filled.History,
            dao.observeRecentlyPlayed(),
            dao.observeRecentlyPlayedCount(),
        ),
        SmartPlaylist(
            "smart_never_played",
            "Never played",
            Icons.Filled.Album,
            dao.observeNeverPlayed(),
            dao.observeNeverPlayedCount(),
        ),
        SmartPlaylist(
            "smart_offline",
            "Offline ready",
            Icons.Filled.DownloadDone,
            dao.observeOffline(),
            dao.observeOfflineCount(),
        ),
    )
}

/**
 * One preset smart playlist as a pushed detail screen. Rows reuse TrackRow so
 * playback, the long-press sheet and the tappable download badge behave exactly
 * like the library list. An unknown presetId renders the standard empty state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistScreen(
    container: AppContainer,
    presetId: String,
    onBack: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val player = container.player
    val preset = remember(presetId) { smartPlaylists(container).firstOrNull { it.id == presetId } }
    val tracks by remember(preset) { preset?.tracks ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Running-download rows collected once at the list level, never per item.
    val downloadRows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadRowsByKey = remember(downloadRows) {
        downloadRows.associate {
            "${it.download.sourceId}:${it.download.trackServerId}" to it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(preset?.title ?: "Smart playlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (tracks.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    preset?.icon ?: Icons.Filled.Album,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Nothing here yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Nothing matches this playlist yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item {
                    Text(
                        if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        container,
                        track,
                        onArtistClick,
                        downloadRow = downloadRowsByKey["${track.sourceId}:${track.serverId}"],
                    ) {
                        player.playNow(tracks.map { it.toTrack() }, index)
                    }
                }
            }
        }
    }
}
