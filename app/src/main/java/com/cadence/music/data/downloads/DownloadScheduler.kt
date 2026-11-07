package com.cadence.music.data.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cadence.music.data.prefs.Prefs
import java.util.concurrent.TimeUnit

/**
 * Owns the single download drain loop. Adding a row is enough to make it run;
 * this class only decides when the loop may start and how pause/cancel stop it.
 */
object DownloadScheduler {
    const val WORK_NAME = "download_queue"
    const val TAG = "download"

    fun start(context: Context, prefs: Prefs, replace: Boolean = false) {
        if (prefs.downloadsPaused) return
        DownloadWorker.clearPause()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (prefs.downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Pause keeps partial files; the queue stays visible and resumable. */
    fun pause(context: Context, prefs: Prefs) {
        prefs.downloadsPaused = true
        DownloadWorker.requestPause()
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_NAME)
        wm.cancelAllWorkByTag(TAG)
    }

    fun resume(context: Context, prefs: Prefs) {
        prefs.downloadsPaused = false
        DownloadWorker.clearPause()
        start(context, prefs, replace = true)
    }
}
