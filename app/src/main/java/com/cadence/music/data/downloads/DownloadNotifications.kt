package com.cadence.music.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cadence.music.CadenceApp
import com.cadence.music.MainActivity
import com.cadence.music.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The progress surface while the app is closed (spec D7): a low-importance
 * foreground notification driven by the worker, plus a one-shot completion
 * summary. Every post no-ops silently when notifications are not permitted,
 * and tap-through lands on the Downloads screen via MainActivity's
 * `open_downloads` extra.
 */
object DownloadNotifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_COMPLETE = "downloads_complete"

    const val NOTIFICATION_ID_PROGRESS = 41
    private const val NOTIFICATION_ID_COMPLETE = 42
    private const val REQUEST_OPEN_DOWNLOADS = 1

    const val ACTION_PAUSE = "com.cadence.music.action.DOWNLOAD_PAUSE"
    const val ACTION_RESUME = "com.cadence.music.action.DOWNLOAD_RESUME"
    const val ACTION_CANCEL_ALL = "com.cadence.music.action.DOWNLOAD_CANCEL_ALL"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Posts/refreshes the progress notice; the same body backs the foreground service. */
    fun showProgress(
        context: Context,
        done: Int,
        total: Int,
        bytesDone: Long,
        bytesTotal: Long,
        paused: Boolean,
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        runCatching {
            nm.notify(
                NOTIFICATION_ID_PROGRESS,
                progressNotification(context, done, total, bytesDone, bytesTotal, paused),
            )
        }
    }

    /** Builds the progress notice; also the body handed to worker.setForeground. */
    fun progressNotification(
        context: Context,
        done: Int,
        total: Int,
        bytesDone: Long,
        bytesTotal: Long,
        paused: Boolean,
    ): Notification {
        val counts = "$done of $total track${if (total == 1) "" else "s"}"
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (paused) "Downloads paused" else "Downloading")
            .setContentText(
                when {
                    paused || bytesTotal <= 0 -> counts
                    else -> "$counts · ${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloadsIntent(context))
            .addAction(
                if (paused) action(context, ACTION_RESUME, "Resume")
                else action(context, ACTION_PAUSE, "Pause")
            )
            .addAction(action(context, ACTION_CANCEL_ALL, "Cancel all"))
        if (bytesTotal > 0) {
            val percent = ((bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) * 100).toInt()
            builder.setProgress(100, percent, false)
        } else {
            // The server hides Content-Length: an activity bar beats a fake one.
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /**
     * The one-shot summary when a batch settles: "$count tracks · $bytes" (or
     * "$count of $total tracks · $failed failed" when some did not make it).
     */
    fun showComplete(context: Context, title: String, count: Int, bytes: Long, failed: Int = 0) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val text = when {
            failed > 0 && count > 0 -> "$count of ${count + failed} tracks · $failed failed"
            failed > 0 -> "$failed track${if (failed == 1) "" else "s"} failed"
            count <= 0 -> return
            bytes > 0 -> "$count track${if (count == 1) "" else "s"} · ${formatBytes(bytes)}"
            else -> "$count track${if (count == 1) "" else "s"}"
        }
        runCatching {
            nm.notify(
                NOTIFICATION_ID_COMPLETE,
                NotificationCompat.Builder(context, CHANNEL_COMPLETE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(openDownloadsIntent(context))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** Clears the progress notice; the completion notice stays until tapped. */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS) }
    }

    private fun openDownloadsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        // Distinct request code: the update notice's content intent targets the
        // same component and filterEquals ignores extras, so a shared code would
        // let the last FLAG_UPDATE_CURRENT registration hijack the other's tap.
        context, REQUEST_OPEN_DOWNLOADS,
        Intent(context, MainActivity::class.java)
            .putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, label: String): NotificationCompat.Action =
        NotificationCompat.Action(
            0,
            label,
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, DownloadActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    // Mirrors DownloadsScreen.formatBytes; kept local so the data layer does
    // not reach into the ui package for a six-line formatter.
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toFloat() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toFloat() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toFloat() / (1L shl 10))
        else -> "$bytes B"
    }
}

/**
 * The byte-weighted active-scope snapshot the notification renders, straight
 * from the same query the chip and the Downloads bar use.
 */
internal suspend fun downloadScopeProgress(db: AppDatabase): ByteProgress =
    byteProgress(db.downloadDao().observeActiveScope().first())

/**
 * Progress-notification actions (Pause / Resume / Cancel all). Cancel all
 * drops every queued row and signals the running writer, which owns its
 * partial-file cleanup; pause/resume go through the scheduler so the
 * WorkManager work, the prefs and the in-app chip stay in step.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? CadenceApp)?.container ?: return
        val result = goAsync()
        // One-shot scope: broadcast receivers outlive onReceive only via goAsync.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (intent.action) {
                    DownloadNotifications.ACTION_PAUSE -> {
                        container.library.pauseDownloads()
                        // The foreground notice dies with the cancelled work; this
                        // re-post explains why nothing is moving.
                        val p = downloadScopeProgress(container.database)
                        DownloadNotifications.showProgress(
                            context, p.doneCount, p.totalCount, p.bytesDone, p.bytesTotal, paused = true,
                        )
                    }
                    DownloadNotifications.ACTION_RESUME -> {
                        DownloadNotifications.cancel(context)
                        container.library.resumeDownloads()
                    }
                    DownloadNotifications.ACTION_CANCEL_ALL -> {
                        DownloadWorker.requestCancelAll()
                        val db = container.database
                        db.downloadDao().deleteByStatus(DownloadStatus.QUEUED)
                        for (row in db.downloadDao().byStatus(DownloadStatus.RUNNING)) {
                            val track = db.trackDao().byServerId(row.trackServerId)
                            if (track != null && track.sourceId == row.sourceId) {
                                DownloadWorker.requestCancel(track.id)
                            }
                            db.downloadDao().delete(row.trackServerId, row.sourceId)
                        }
                        DownloadNotifications.cancel(context)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
