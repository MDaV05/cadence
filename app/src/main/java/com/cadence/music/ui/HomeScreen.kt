package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.cadence.music.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Start tab: play stats brought to life — recently played, most played, shuffle-all. */
@Composable
fun HomeScreen(
    container: AppContainer,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val player = container.player
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var most by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var total by remember { mutableStateOf(0) }

    // Reload whenever the playing track changes — stats move as you listen.
    val nowTitle by player.state.collectAsStateWithLifecycle()
    LaunchedEffect(nowTitle.title) {
        val dao = container.database.trackDao()
        withContext(Dispatchers.IO) {
            recent = dao.recentlyPlayed()
            most = dao.mostPlayed()
            total = dao.count()
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Cadence", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "$total tracks in your library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = {
                        scope.launch {
                            val all = withContext(Dispatchers.IO) { container.library.tracks().first() }
                            container.player.shuffleAll(all.map { it.toTrack() })
                        }
                    }) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.padding(end = 6.dp))
                        Text("Shuffle all")
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                items(recent, key = { "recent:${it.id}" }) { track ->
                    TrackRow(container, track, onArtistClick, onClick = {
                        player.playNow(listOf(track.toTrack()))
                    })
                }
            }

            if (most.isNotEmpty()) {
                item { SectionHeader("Most played") }
                items(most, key = { "most:${it.id}" }) { track ->
                    TrackRow(container, track, onArtistClick, onClick = {
                        player.playNow(listOf(track.toTrack()))
                    })
                }
            }

            if (recent.isEmpty() && most.isEmpty()) {
                item {
                    Text(
                        "Play something and your history will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}
