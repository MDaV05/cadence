package com.cadence.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.cadence.music.AppContainer
import kotlinx.coroutines.delay

/**
 * Library search over the synced tracks table. Recent queries live in Prefs
 * (last 10) and render as chips while the field is empty. Server tracks are
 * all mirrored into the local DB by sync, so a local filter covers them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    container: AppContainer,
    onArtistClick: (String) -> Unit = {},
) {
    val player = container.player
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debounced = query
    }
    var history by remember { mutableStateOf(container.prefs.searchHistory) }

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
            androidx.compose.material3.OutlinedTextField(
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
                // cachedIn lives INSIDE remember: called inline during composition it would
                // return a new Flow instance every recomposition, restarting the pager
                // collection (and its refresh) in a loop — refresh would never settle.
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
                            TrackRow(container, track, onArtistClick) {
                                recordQuery(query)
                                focusManager.clearFocus()
                                player.playNow(listOf(track.toTrack()))
                            }
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
                                "Couldn't load results.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(32.dp),
                            ) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
