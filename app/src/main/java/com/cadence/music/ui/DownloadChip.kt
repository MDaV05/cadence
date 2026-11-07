package com.cadence.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cadence.music.AppContainer
import com.cadence.music.data.downloads.ByteProgress
import com.cadence.music.data.downloads.activeScope
import com.cadence.music.data.downloads.byteProgress

private val EMPTY_PROGRESS = ByteProgress(0f, 0L, 0L, 0, 0, 0)

/**
 * Global download pill shown above the mini player while a batch is active:
 * the same byte-weighted fraction the Downloads screen bar uses, a one-line
 * count of the active scope, and a pause/resume button. Tapping the body
 * opens the Downloads screen; the chip is gone entirely once the queue is
 * idle.
 */
@Composable
fun DownloadChip(
    container: AppContainer,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeRows by container.library.observeActiveScope()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val progress = remember(activeRows) { byteProgress(activeScope(activeRows)) }
    val prefs = container.prefs

    // Mirrors prefs like the Downloads screen's header button; keyed on the
    // scope so the icon re-syncs whenever the queue itself changes.
    var paused by remember(activeRows) { mutableStateOf(prefs.downloadsPaused) }

    // Hold the last non-empty snapshot so the exit animation shrinks the real
    // content instead of flashing "0 of 0" when the queue drains.
    var shown by remember { mutableStateOf(EMPTY_PROGRESS) }
    if (progress.totalCount > 0) shown = progress

    AnimatedVisibility(
        visible = progress.totalCount > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Open downloads",
                ) { onOpenDownloads() }
                .padding(start = 12.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                progress = { shown.fraction },
                strokeWidth = 4.dp,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (shown.totalCount == 1) "Downloading 1 track"
                else "Downloading ${shown.doneCount} of ${shown.totalCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            IconButton(onClick = {
                if (paused) {
                    paused = false
                    container.library.resumeDownloads()
                } else {
                    paused = true
                    container.library.pauseDownloads()
                }
            }) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (paused) "Resume downloads" else "Pause downloads",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
