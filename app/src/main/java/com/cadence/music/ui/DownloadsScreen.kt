package com.cadence.music.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.DownloadStatusRow
import com.cadence.music.data.db.AlbumGroup
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.downloads.DownloadStatus
import com.cadence.music.data.downloads.estimateQueueBytes
import com.cadence.music.data.downloads.humanizeDownloadError
import com.cadence.music.data.downloads.isUnmetered
import com.cadence.music.data.downloads.observeUnmetered
import com.cadence.music.data.downloads.progressOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = container.prefs
    val rows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val albums by container.library.albumGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var paused by remember { mutableStateOf(prefs.downloadsPaused) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDownloadAll by remember { mutableStateOf(false) }
    var showDownloadLiked by remember { mutableStateOf(false) }
    var showClearCompleted by remember { mutableStateOf(false) }
    var albumToRemove by remember { mutableStateOf<AlbumGroup?>(null) }
    var showMobileConfirm by remember { mutableStateOf(false) }

    val activeRows = remember(rows) {
        rows.filter {
            it.download.status == DownloadStatus.RUNNING ||
                it.download.status == DownloadStatus.QUEUED
        }.sortedWith(
            compareBy(
                { if (it.download.status == DownloadStatus.RUNNING) 0 else 1 },
                { it.download.enqueuedAt },
            )
        )
    }
    val failedRows = remember(rows) { rows.filter { it.download.status == DownloadStatus.FAILED } }
    val doneRows = remember(rows) { rows.filter { it.download.status == DownloadStatus.DONE } }
    val doneAlbums = remember(doneRows) {
        doneRows.filter { it.track != null }
            .groupBy { it.track!!.albumNorm }
            .toList()
            .sortedBy { it.first.lowercase() }
    }
    val albumByNorm = remember(albums) { albums.associateBy { it.norm } }
    val progress = remember(rows) { progressOf(rows.map { it.download.status }) }
    val totalBytes = remember(doneRows) { doneRows.sumOf { it.download.bytesDone } }
    val wifi by remember(context) { observeUnmetered(context) }
        .collectAsStateWithLifecycle(initialValue = isUnmetered(context))
    val wifiWaiting = !paused && prefs.downloadWifiOnly && !wifi && progress.queued > 0

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun verifyDownloads() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.library.repairInvalidDownloads() }
            }
            val repaired = result.getOrNull()
            toast(
                when {
                    repaired == null -> "Could not verify downloads"
                    repaired == 0 -> "All downloads verified"
                    else -> "$repaired downloads repaired"
                }
            )
        }
    }

    fun retryFailed() {
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { container.library.retryFailedDownloads() }.getOrDefault(0)
            }
            toast(if (n == 0) "Nothing to retry" else "Queued $n tracks")
        }
    }

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
                    if (progress.active) {
                        IconButton(onClick = {
                            if (paused) {
                                paused = false
                                container.library.resumeDownloads()
                                toast("Downloads resumed")
                            } else {
                                paused = true
                                container.library.pauseDownloads()
                                toast("Downloads paused")
                            }
                        }) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                if (paused) "Resume downloads" else "Pause downloads",
                            )
                        }
                    }
                    IconButton(onClick = { showDownloadAll = true }) {
                        Icon(Icons.Filled.Download, "Download whole library")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "More download actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Download liked songs") },
                                onClick = { menuOpen = false; showDownloadLiked = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Verify downloads") },
                                onClick = { menuOpen = false; verifyDownloads() },
                            )
                            if (failedRows.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Retry failed (${failedRows.size})") },
                                    onClick = { menuOpen = false; retryFailed() },
                                )
                            }
                            if (doneRows.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Clear completed") },
                                    onClick = { menuOpen = false; showClearCompleted = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyDownloads(
                modifier = Modifier.fillMaxSize().padding(padding),
                onLiked = { showDownloadLiked = true },
                onLibrary = { showDownloadAll = true },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                    DownloadSummary(
                        progress = progress,
                        totalBytes = totalBytes,
                        paused = paused,
                        wifiWaiting = wifiWaiting,
                        onResume = {
                            paused = false
                            container.library.resumeDownloads()
                            toast("Downloads resumed")
                        },
                        onDownloadOnMobile = { showMobileConfirm = true },
                    )
                }
                if (activeRows.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "active-header") {
                        SectionTitle("In progress", "${activeRows.size} in queue")
                    }
                    items(
                        activeRows,
                        key = { "queue:${it.download.sourceId}:${it.download.trackServerId}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { row ->
                        QueueRow(
                            row = row,
                            paused = paused,
                            wifiWaiting = wifiWaiting,
                            onCancel = {
                                scope.launch {
                                    container.library.cancelDownload(row.download, row.track)
                                }
                            },
                        )
                    }
                }
                if (failedRows.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "failed-header") {
                        SectionTitle(
                            "Needs attention",
                            "${failedRows.size} failed",
                            actionLabel = "Retry all",
                            onAction = ::retryFailed,
                        )
                    }
                    items(
                        failedRows,
                        key = { "failed:${it.download.sourceId}:${it.download.trackServerId}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { row ->
                        FailedRow(
                            row = row,
                            onRetry = {
                                scope.launch { container.library.retryDownload(row.download, row.track) }
                            },
                            onRemove = {
                                scope.launch {
                                    container.library.cancelDownload(row.download, row.track)
                                }
                            },
                        )
                    }
                }
                if (doneAlbums.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "offline-header") {
                        SectionTitle(
                            "Offline",
                            "${doneRows.size} tracks · ${formatBytes(totalBytes)}",
                        )
                    }
                    items(doneAlbums, key = { "album:${it.first}" }) { (norm, group) ->
                        AlbumDownloadTile(
                            container = container,
                            albumNorm = norm,
                            group = group,
                            totalTracks = albumByNorm[norm]?.trackCount ?: group.size,
                            onAlbumClick = onAlbumClick,
                            onDownloadMissing = {
                                scope.launch {
                                    val missing = withContext(Dispatchers.IO) {
                                        runCatching {
                                            container.library.tracksByAlbumNorm(norm)
                                                .filter { it.path == null && it.sourceId != "local" }
                                        }.getOrDefault(emptyList())
                                    }
                                    if (missing.isEmpty()) {
                                        toast("Album is already offline")
                                    } else {
                                        container.library.enqueueDownloads(missing)
                                        toast("Queued ${missing.size} tracks")
                                    }
                                }
                            },
                            onRemove = {
                                albumToRemove = albumByNorm[norm] ?: AlbumGroup(
                                    name = group.first().track?.albumName.orEmpty(),
                                    artistName = group.first().track?.artistName.orEmpty(),
                                    trackCount = group.size,
                                    norm = norm,
                                )
                            },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "bottom-space") {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showDownloadAll) {
        BulkDownloadDialog(
            container = container,
            title = "Download the whole library?",
            emptyText = "Every track already has an offline copy.",
            loadTracks = { container.library.tracksForBulkDownload() },
            onDismiss = { showDownloadAll = false },
        )
    }
    if (showDownloadLiked) {
        BulkDownloadDialog(
            container = container,
            title = "Download liked songs?",
            emptyText = "No liked songs are waiting to be downloaded.",
            loadTracks = {
                container.library.likedTracks().filter { it.path == null && it.sourceId != "local" }
            },
            onDismiss = { showDownloadLiked = false },
        )
    }
    if (showClearCompleted) {
        AlertDialog(
            onDismissRequest = { showClearCompleted = false },
            title = { Text("Clear completed downloads?") },
            text = {
                Text(
                    "This deletes ${doneRows.size} downloaded files and frees about " +
                        "${formatBytes(totalBytes)}. Tracks stay available to stream."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCompleted = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching { container.library.clearCompletedDownloads() }.getOrDefault(0)
                        }
                        toast("Removed $n downloads")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showClearCompleted = false }) { Text("Cancel") } },
        )
    }
    albumToRemove?.let { album ->
        AlertDialog(
            onDismissRequest = { albumToRemove = null },
            title = { Text("Remove album downloads?") },
            text = {
                Text(
                    "This deletes the offline files for ${album.name.ifBlank { "this album" }}. " +
                        "The album stays available to stream."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val norm = album.norm
                    albumToRemove = null
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching { container.library.deleteAlbumDownloads(norm) }.getOrDefault(0)
                        }
                        toast("Removed $n downloads")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { albumToRemove = null }) { Text("Cancel") } },
        )
    }
    if (showMobileConfirm) {
        AlertDialog(
            onDismissRequest = { showMobileConfirm = false },
            title = { Text("Download on mobile data?") },
            text = {
                Text(
                    "Wi-Fi-only downloads will be turned off so the queue can start now. " +
                        "You can turn it back on in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMobileConfirm = false
                    prefs.downloadWifiOnly = false
                    container.library.resumeDownloads()
                    toast("Downloading on mobile data")
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showMobileConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyDownloads(
    modifier: Modifier = Modifier,
    onLiked: () -> Unit,
    onLibrary: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Nothing downloaded yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Save songs, albums, or liked songs here to listen without a connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 20.dp),
        ) {
            OutlinedButton(onClick = onLiked) { Text("Liked songs") }
            OutlinedButton(onClick = onLibrary) { Text("Whole library") }
        }
    }
}

@Composable
private fun DownloadSummary(
    progress: com.cadence.music.data.downloads.DownloadProgress,
    totalBytes: Long,
    paused: Boolean,
    wifiWaiting: Boolean,
    onResume: () -> Unit,
    onDownloadOnMobile: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${progress.done} offline",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${formatBytes(totalBytes)} stored" +
                            if (progress.pending > 0) " · ${progress.pending} in queue" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress.visibleTotal > 0) {
                    Text(
                        "${(progress.fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (progress.visibleTotal > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall),
                )
            }
            val detail = buildList {
                if (progress.running > 0) add("${progress.running} downloading")
                if (progress.queued > 0) add("${progress.queued} queued")
                if (progress.failed > 0) add("${progress.failed} failed")
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.failed > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (paused) {
                NoticeRow(
                    icon = Icons.Filled.Pause,
                    text = "Downloads paused",
                    actionLabel = "Resume",
                    onAction = onResume,
                )
            } else if (wifiWaiting) {
                NoticeRow(
                    icon = Icons.Filled.WifiOff,
                    text = "Waiting for Wi-Fi",
                    actionLabel = "Download on mobile",
                    onAction = onDownloadOnMobile,
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun QueueRow(
    row: DownloadStatusRow,
    paused: Boolean,
    wifiWaiting: Boolean,
    onCancel: () -> Unit,
) {
    val download = row.download
    val running = download.status == DownloadStatus.RUNNING
    val total = download.totalBytes
    val fraction = if (total > 0) (download.bytesDone.toFloat() / total).coerceIn(0f, 1f) else 0f
    val subtitle = when {
        running && total > 0 ->
            "${(fraction * 100).roundToInt()}% · ${formatBytes(download.bytesDone)} of ${formatBytes(total)}"
        running -> "${formatBytes(download.bytesDone)} downloaded"
        paused -> "Paused"
        wifiWaiting -> "Waiting for Wi-Fi"
        total > 0 -> "Queued · ${formatBytes(total)}"
        else -> "Queued"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.track?.title ?: download.trackServerId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, "Remove from queue")
            }
        }
    }
}

@Composable
private fun FailedRow(
    row: DownloadStatusRow,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val download = row.download
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.track?.title ?: download.trackServerId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    humanizeDownloadError(download.errorText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, "Retry download")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, "Remove failed download")
            }
        }
    }
}

@Composable
private fun AlbumDownloadTile(
    container: AppContainer,
    albumNorm: String,
    group: List<DownloadStatusRow>,
    totalTracks: Int,
    onAlbumClick: (String) -> Unit,
    onDownloadMissing: () -> Unit,
    onRemove: () -> Unit,
) {
    val firstTrack = group.first().track ?: return
    var menuOpen by remember { mutableStateOf(false) }
    val doneCount = group.size
    Column(
        Modifier.fillMaxWidth().clickable { if (albumNorm.isNotBlank()) onAlbumClick(albumNorm) },
    ) {
        Box {
            TrackArt(
                container,
                firstTrack.serverId,
                Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (albumNorm.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        "Album download actions",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (doneCount < totalTracks) {
                        DropdownMenuItem(
                            text = { Text("Download missing") },
                            onClick = { menuOpen = false; onDownloadMissing() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove downloads") },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        Text(
            firstTrack.albumName.ifBlank { firstTrack.albumNorm },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "$doneCount/$totalTracks offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Confirmation for the one-tap whole-library download, shared by the Downloads
 * top bar and the Settings storage row. Count and size are loaded off the main
 * thread; the confirm button stays disabled until they arrive.
 */
@Composable
internal fun DownloadLibraryDialog(container: AppContainer, onDismiss: () -> Unit) {
    BulkDownloadDialog(
        container = container,
        title = "Download the whole library?",
        emptyText = "Every track already has an offline copy.",
        loadTracks = { container.library.tracksForBulkDownload() },
        onDismiss = onDismiss,
    )
}

@Composable
private fun BulkDownloadDialog(
    container: AppContainer,
    title: String,
    emptyText: String,
    loadTracks: suspend () -> List<TrackEntity>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = container.prefs
    val tracks by produceState<List<TrackEntity>?>(null, container) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadTracks() }.getOrDefault(emptyList())
        }
    }
    var wifiOnly by remember { mutableStateOf(prefs.downloadWifiOnly) }
    val format = prefs.downloadFormat
    val bitrate = prefs.downloadBitrate
    val estimate = remember(tracks, format, bitrate) {
        tracks?.let { estimateQueueBytes(it, format, bitrate) } ?: 0L
    }
    val free = remember { context.filesDir.usableSpace }
    val wifi by remember(context) { observeUnmetered(context) }
        .collectAsStateWithLifecycle(initialValue = isUnmetered(context))
    val n = tracks?.size ?: 0
    val needsRoom = estimate > 0 && estimate + BULK_HEADROOM_BYTES > free

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    tracks == null -> Text("Counting tracks…")
                    n == 0 -> Text(emptyText)
                    else -> {
                        Text("$n tracks · about ${formatBytes(estimate)}")
                        Text(
                            "${formatBytes(free)} free on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (needsRoom) {
                    Text(
                        "Not enough free space. Free up about " +
                            "${formatBytes(estimate + BULK_HEADROOM_BYTES - free)} first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (n > 0 && !wifi) {
                    Text(
                        if (wifiOnly) "Downloads will wait for Wi-Fi."
                        else "You are on mobile data. This may use about ${formatBytes(estimate)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                prefs.downloadWifiOnly = it
                                // Rebuild the queued work request so its network
                                // constraint matches the new choice immediately.
                                container.library.applyDownloadNetworkPolicy()
                            },
                        )
                        Text("Wait for Wi-Fi", modifier = Modifier.padding(start = 8.dp))
                    }
                } else if (n > 0) {
                    Text(
                        "On Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = tracks != null && n > 0 && !needsRoom,
                onClick = {
                    prefs.downloadWifiOnly = wifiOnly
                    container.library.applyDownloadNetworkPolicy()
                    container.library.enqueueDownloadsAsync(tracks.orEmpty())
                    val message = when {
                        prefs.downloadsPaused -> "Queued $n tracks — downloads are paused"
                        wifiOnly && !wifi -> "Queued — waiting for Wi-Fi"
                        else -> "Queued $n tracks"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ) { Text(if (wifiOnly && !wifi) "Queue" else "Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toFloat() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toFloat() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toFloat() / (1L shl 10))
    else -> "$bytes B"
}

private const val BULK_HEADROOM_BYTES = 50L * 1024 * 1024
