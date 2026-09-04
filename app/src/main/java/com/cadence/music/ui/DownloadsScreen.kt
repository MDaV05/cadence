package com.cadence.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(container: AppContainer, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val rows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val totalBytes = rows.filter { it.download.status == "done" }.sumOf { it.download.bytesDone }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "${rows.size} items · ${formatBytes(totalBytes)} offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                LazyColumn {
                    items(rows, key = { "${it.download.sourceId}:${it.download.trackServerId}" }) { row ->
                        DownloadRow(
                            container,
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

@Composable
private fun DownloadRow(
    container: AppContainer,
    row: com.cadence.music.data.DownloadStatusRow,
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
                    else -> "Failed — will stay streamable"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        "running" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
