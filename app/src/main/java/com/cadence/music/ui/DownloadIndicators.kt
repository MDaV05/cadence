package com.cadence.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cadence.music.data.downloads.DownloadStatus

/**
 * One trailing affordance for a track row: live progress, queue position,
 * failure, offline check, or the download action itself. Keeping this in one
 * place is what makes the state legible in albums, playlists, and songs.
 *
 * The badge is also the tap target: running/queued cancels, failed retries.
 * Without the matching callback the state stays display-only.
 */
@Composable
fun DownloadStatusIcon(
    status: String?,
    isDownloaded: Boolean,
    onDownload: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when {
        status == DownloadStatus.RUNNING -> if (onCancel != null) {
            IconButton(
                onClick = onCancel,
                modifier = modifier
                    .size(32.dp)
                    .semantics { contentDescription = "Cancel download" },
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        status == DownloadStatus.QUEUED -> if (onCancel != null) {
            IconButton(onClick = onCancel, modifier = modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Cancel download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = "Queued for download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(18.dp),
            )
        }
        status == DownloadStatus.FAILED -> if (onRetry != null) {
            IconButton(onClick = onRetry, modifier = modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Download failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(18.dp),
            )
        }
        isDownloaded || status == DownloadStatus.DONE -> Icon(
            Icons.Filled.DownloadDone,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(18.dp),
        )
        onDownload != null -> IconButton(onClick = onDownload, modifier = modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Download,
                contentDescription = "Download for offline",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
