package com.cadence.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cadence.music.AppContainer
import com.cadence.music.data.metadata.ListenBrainz
import com.cadence.music.data.stats.ArtistPlays
import com.cadence.music.data.stats.ListeningStats
import com.cadence.music.data.stats.computeStats
import com.cadence.music.data.stats.formatListenMinutes
import com.cadence.music.data.stats.mergeRecentPlays
import com.cadence.music.data.stats.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the stats screen renders, loaded once on entry: local aggregation plus an
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

private fun pluralPlays(n: Int): String = if (n == 1) "1 play" else "$n plays"

private fun pluralTracks(n: Int): String = if (n == 1) "1 track" else "$n tracks"

@Composable
fun StatsScreen(container: AppContainer, onArtistClick: (String) -> Unit = {}) {
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
                val user = verdict?.takeIf { it.first }?.second?.trim()
                // A valid token whose response lacks a user name must not build a
                // "/user//statistics" URL — fall through to the pure-local view.
                if (!user.isNullOrBlank()) {
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

    // The outer nav Scaffold zeroes its insets, so clear the status bar here —
    // the header then sits exactly where Library/Playlists headers do.
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Stats",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        val d = data
        when {
            d == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp)) }

            d.totalPlays == 0L && d.topArtists.isEmpty() && d.recents.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your listening stats will build up here as you play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                // One clock reading for all relative labels; a screen-lifetime
                // staleness of minutes is irrelevant for "5m"-style labels.
                val now = remember { System.currentTimeMillis() }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item { StatsHero(d) }
                    if (d.topArtists.isNotEmpty()) {
                        item { StatsSectionLabel("Top artists") }
                        itemsIndexed(d.topArtists) { i, artist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onArtistClick(artist.name) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    pluralPlays(artist.plays),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (d.recents.isNotEmpty()) {
                        item { StatsSectionLabel("Recently played") }
                        itemsIndexed(d.recents) { _, recent ->
                            val (ts, artistTitle) = recent
                            val (artist, title) = artistTitle
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = artist.isNotBlank()) { onArtistClick(artist) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                                Text(
                                    relativeTime(ts, now),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-width gradient hero matching the Home greeting banner's visual language. */
@Composable
private fun StatsHero(d: StatsData) {
    val s = d.stats
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    )
                )
            )
            .padding(20.dp),
    ) {
        Text(
            formatListenMinutes(s.totalMinutes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${pluralPlays(d.totalPlays.toInt())} · ${pluralTracks(s.uniquePlayed)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (d.playsFromLb) "on ListenBrainz" else "from your library",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "${pluralPlays(s.playsLast7Days)} this week · ${s.weekStreak}-week streak",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
