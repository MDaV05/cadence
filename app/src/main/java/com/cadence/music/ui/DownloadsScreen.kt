package com.cadence.music.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.DownloadStatusRow
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.downloads.isStaleDownloadError
import com.cadence.music.data.downloads.progressOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val totalBytes = remember(rows) { rows.filter { it.download.status == "done" }.sumOf { it.download.bytesDone } }
    // Albums shown even when only partially downloaded; orphan rows (no track)
    // are omitted here and surface in the failed section only if failed.
    val albumGroups = remember(rows) {
        rows.filter { it.track != null }
            .groupBy { it.track!!.albumNorm }
            .toList()
            .sortedBy { it.first.lowercase() }
    }
    val failedRows = remember(rows) { rows.filter { it.download.status == "failed" } }
    val staleRows = remember(failedRows) {
        failedRows.filter { isStaleDownloadError(it.download.errorText) }
    }
    val progress = remember(rows) { progressOf(rows.map { it.download.status }) }
    var showDownloadAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { container.library.repairInvalidDownloads() }
                            }
                            val repaired = result.getOrNull()
                            Toast.makeText(
                                context,
                                when {
                                    repaired == null -> "Could not verify downloads"
                                    repaired == 0 -> "All downloads verified"
                                    else -> "$repaired broken downloads cleared"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) {
                        Icon(Icons.Filled.Verified, "Verify downloads")
                    }
                    IconButton(onClick = { showDownloadAll = true }) {
                        Icon(Icons.Filled.Download, "Download whole library")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "${rows.size} items · ${formatBytes(totalBytes)} offline" +
                    (if (failedRows.isNotEmpty()) " · ${failedRows.size} failed" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (progress.total > 0) 4.dp else 8.dp),
            )
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    "${progress.done} done · ${progress.running} downloading" +
                        (if (progress.failed > 0) " · ${progress.failed} failed" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (staleRows.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${staleRows.size} tracks changed on the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        container.library.refreshLibraryAndRetryFailedAsync()
                        Toast.makeText(
                            context,
                            "Refreshing library, then retrying downloads",
                            Toast.LENGTH_LONG,
                        ).show()
                    }) { Text("Refresh") }
                }
            }
            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Long-press a song or use the download icon on an album or playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                // Same grid metrics as the library albums tab.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(albumGroups, key = { it.first }) { (norm, group) ->
                        AlbumDownloadTile(container, norm, group, onAlbumClick)
                    }
                    if (failedRows.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "failed-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Failed",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        val n = withContext(Dispatchers.IO) {
                                            runCatching { container.library.retryFailedDownloads() }
                                                .getOrDefault(0)
                                        }
                                        Toast.makeText(
                                            context,
                                            if (n == 0) "Nothing to retry" else "Queued $n tracks",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }) { Text("Retry all") }
                            }
                        }
                        items(failedRows, key = { "${it.download.sourceId}:${it.download.trackServerId}" },
                            span = { GridItemSpan(maxLineSpan) }) { row ->
                            DownloadRow(
                                row,
                                onRetry = {
                                    scope.launch { container.library.retryDownload(row.download, row.track) }
                                },
                                onDelete = {
                                    scope.launch { container.library.deleteDownload(row.download, row.track) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDownloadAll) {
        DownloadLibraryDialog(container, onDismiss = { showDownloadAll = false })
    }
}

@Composable
private fun AlbumDownloadTile(
    container: AppContainer,
    albumNorm: String,
    group: List<DownloadStatusRow>,
    onAlbumClick: (String) -> Unit,
) {
    val firstTrack = group.first().track!!
    val doneCount = group.count { it.download.status == "done" }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { if (albumNorm.isNotBlank()) onAlbumClick(albumNorm) }
    ) {
        TrackArt(container, firstTrack.serverId, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            firstTrack.albumName.ifBlank { firstTrack.albumNorm },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "$doneCount/${group.size} offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Confirmation for the one-tap whole-library download, shared by the Downloads
 * top bar and the Settings storage row. Count is loaded off the main thread;
 * the confirm button stays disabled until it arrives.
 */
@Composable
internal fun DownloadLibraryDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tracks by produceState<List<TrackEntity>?>(null, container) {
        value = withContext(Dispatchers.IO) {
            runCatching { container.library.tracksForBulkDownload() }.getOrDefault(emptyList())
        }
    }
    val wifi = remember {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            network != null &&
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
    }
    val n = tracks?.size ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download the whole library?") },
        text = {
            Text(
                if (tracks == null) "Counting tracks…"
                else "$n tracks not yet offline. " +
                    (if (!wifi) "You are on mobile data — this can cost a lot. " else "") +
                    "Continue?"
            )
        },
        confirmButton = {
            TextButton(enabled = tracks != null && n > 0, onClick = {
                // Enqueue on the repository's app-lifetime scope: a
                // dialog-scoped coroutine would be cancelled by the onDismiss
                // below before the WorkManager loop dispatches, silently
                // dropping the whole-library queue behind the "Queued" toast.
                container.library.enqueueDownloadsAsync(tracks.orEmpty())
                Toast.makeText(context, "Queued", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadRow(
    row: DownloadStatusRow,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = row.download.status
    ListItem(
        headlineContent = {
            Text(row.track?.title ?: row.download.trackServerId, maxLines = 1)
        },
        supportingContent = {
            Column {
                val label = when (status) {
                    "running" -> "Downloading… ${formatBytes(row.download.bytesDone)}"
                    "done" -> "Offline · ${formatBytes(row.download.bytesDone)}"
                    else -> row.download.errorText?.takeIf { it.isNotBlank() }
                        ?: "Failed — will stay streamable"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        "running" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status == "running") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        },
        trailingContent = {
            when (status) {
                "failed" -> IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, "Retry")
                }
                "done" -> IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete")
                }
                else -> {}
            }
        },
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toFloat() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toFloat() / (1L shl 10))
    else -> "$bytes B"
}
