package com.cadence.music.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cadence.music.AppContainer
import com.cadence.music.data.WriteConsentRequired
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.downloads.DownloadStatus
import com.cadence.music.data.tags.albumNormKey
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    container: AppContainer,
    albumNorm: String,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val player = container.player
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalDownloadFeedback.current
    var currentNorm by remember(albumNorm) { mutableStateOf(albumNorm) }
    var tracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var art by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var sheetTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pendingRename by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refreshArt() {
        scope.launch {
            art = tracks.firstOrNull()?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.artResolver.urlFor(it)
                }
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.library.setAlbumCover(currentNorm, uri)
            toast(if (ok) "Cover updated" else "Couldn't set cover")
            if (ok) refreshArt()
        }
    }

    fun downloadAll() {
        // Album downloads are bulk work: queue them on the serial chain instead
        // of one independent work per track.
        val missing = tracks.filter { it.sourceId != "local" && it.path == null }
        enqueueWithFeedback(
            missing, scope, container.library, container.prefs, context,
            feedback.snackbar, feedback.navigateToQueue,
        )
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                pendingRename?.let { name ->
                    try {
                        val ok = container.library.renameAlbumTracks(currentNorm, name)
                        toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                        if (ok) {
                            showRename = false
                            currentNorm = albumNormKey(name.trim(), tracks.firstOrNull()?.artistName.orEmpty())
                        }
                    } catch (e: WriteConsentRequired) {
                        toast("Couldn't complete — permission denied")
                    }
                }
            } else {
                toast("Not changed — permission denied")
            }
            pendingRename = null
        }
    }

    fun doRename(name: String) {
        scope.launch {
            try {
                val ok = container.library.renameAlbumTracks(currentNorm, name)
                toast(if (ok) "Metadata updated" else "Couldn't update metadata")
                if (ok) {
                    showRename = false
                    currentNorm = albumNormKey(name.trim(), tracks.firstOrNull()?.artistName.orEmpty())
                }
            } catch (e: WriteConsentRequired) {
                pendingRename = name
                consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }

    LaunchedEffect(currentNorm) {
        loading = true; error = false
        try {
            tracks = container.library.tracksByAlbumNorm(currentNorm)
            art = tracks.firstOrNull()?.let { container.artResolver.urlFor(it) }
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

    val allLocal = tracks.isNotEmpty() && tracks.all { it.sourceId == "local" }

    // Per-track markers + a determinate header bar instead of a bare spinner:
    // the album row tells you how much is actually offline right now.
    val downloadRows by container.library.observeDownloads()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadStatuses = remember(downloadRows) {
        downloadRows.associate {
            "${it.download.sourceId}:${it.download.trackServerId}" to it.download.status
        }
    }
    val downloadRowsByKey = remember(downloadRows) {
        downloadRows.associate {
            "${it.download.sourceId}:${it.download.trackServerId}" to it
        }
    }
    val downloadServerTracks = remember(tracks) {
        tracks.filter { it.sourceId != "local" }
    }
    val offlineCount = remember(downloadServerTracks, downloadStatuses) {
        downloadServerTracks.count {
            it.path != null || downloadStatuses["${it.sourceId}:${it.serverId}"] == DownloadStatus.DONE
        }
    }
    val pendingCount = remember(downloadServerTracks, downloadStatuses) {
        downloadServerTracks.count {
            val status = downloadStatuses["${it.sourceId}:${it.serverId}"]
            status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
        }
    }
    val albumDownloading = remember(downloadServerTracks, downloadStatuses) {
        downloadServerTracks.any {
            downloadStatuses["${it.sourceId}:${it.serverId}"] == DownloadStatus.RUNNING
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tracks.firstOrNull()?.albumName?.takeIf { it.isNotBlank() } ?: currentNorm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Couldn't load this album.", color = MaterialTheme.colorScheme.error)
            }
            tracks.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No songs found for this album.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.small),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            tracks.firstOrNull()?.albumName?.takeIf { it.isNotBlank() } ?: currentNorm,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            buildString {
                                append("${tracks.size} tracks")
                                if (downloadServerTracks.isNotEmpty()) {
                                    append(" · $offlineCount offline")
                                    if (pendingCount > 0) append(" · $pendingCount queued")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (downloadServerTracks.isNotEmpty() && (offlineCount > 0 || pendingCount > 0)) {
                            LinearProgressIndicator(
                                progress = { offlineCount.toFloat() / downloadServerTracks.size },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    Column {
                        IconButton(onClick = { player.playNow(tracks.map { it.toTrack() }) }) {
                            Icon(Icons.Filled.PlayArrow, "Play album")
                        }
                        IconButton(onClick = { player.shuffleAll(tracks.map { it.toTrack() }) }) {
                            Icon(Icons.Filled.Shuffle, "Shuffle album")
                        }
                        IconButton(onClick = { downloadAll() }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Download, "Download album")
                                if (albumDownloading) {
                                    CircularProgressIndicator(Modifier.size(16.dp))
                                }
                            }
                        }
                        IconButton(onClick = { coverPicker.launch("image/*") }) {
                            Icon(Icons.Filled.AddPhotoAlternate, "Change cover")
                        }
                        if (allLocal) {
                            IconButton(onClick = {
                                renameValue = tracks.firstOrNull()?.albumName.orEmpty()
                                showRename = true
                            }) {
                                Icon(Icons.Filled.Edit, "Rename album")
                            }
                        }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { player.playNow(tracks.map { it.toTrack() }, index) },
                            onLongClick = { sheetTrack = track },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${track.trackNumber.takeIf { it > 0 } ?: ""}",
                        modifier = Modifier.padding(end = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(track.title, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        formatDuration(track.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (track.sourceId != "local") {
                        val downloaded = track.path != null
                        val row = downloadRowsByKey["${track.sourceId}:${track.serverId}"]
                        DownloadStatusIcon(
                            status = downloadStatuses["${track.sourceId}:${track.serverId}"],
                            isDownloaded = downloaded,
                            onDownload = if (!downloaded) {
                                {
                                    enqueueWithFeedback(
                                        listOf(track), scope, container.library, container.prefs,
                                        context, feedback.snackbar, feedback.navigateToQueue, priority = 1,
                                    )
                                }
                            } else {
                                null
                            },
                            onCancel = if (row != null) {
                                { scope.launch { container.library.cancelDownload(row.download, row.track) } }
                            } else {
                                null
                            },
                            onRetry = if (row != null) {
                                { scope.launch { container.library.retryDownload(row.download, row.track) } }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionsSheet(
            container = container,
            track = track,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            onDismiss = { sheetTrack = null },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Title") }, singleLine = true)
            },
            confirmButton = { TextButton(onClick = { doRename(renameValue) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}
