package com.cadence.music.data.downloads

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cadence.music.CadenceApp
import com.cadence.music.data.db.DownloadEntity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CadenceApp
        val trackId = inputData.getLong("trackRowId", -1)
        if (trackId <= 0) return Result.failure()

        val db = app.container.database
        val track = db.trackDao().byId(trackId) ?: return Result.failure()

        val prefsFormat = app.container.prefs.downloadFormat
        val url = app.container.library.downloadUrlFor(track.serverId, prefsFormat, app.container.prefs.downloadBitrate)
            ?: return Result.failure()

        val out = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
            .resolve("${track.serverId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.audio")

        // Read before the "running" upsert below overwrites the row.
        // A format switch (raw->opus) must not reuse a stale file as done.
        val prior = db.downloadDao().byTrack(track.sourceId, track.serverId)
        if (prior?.status == "done" && prior.transcode == prefsFormat && out.exists()) {
            db.trackDao().setPath(trackId, Uri.fromFile(out).toString())
            return Result.success()
        }
        if (prior != null && prior.transcode != prefsFormat) runCatching { out.delete() }

        db.downloadDao().upsert(
            DownloadEntity(track.serverId, track.sourceId, "running", transcode = prefsFormat)
        )

        try {
            var done = if (out.exists()) out.length() else 0L
            // Progress rows are throttled: Room writes at most every ~500 KB.
            var lastReported = -1L
            suspend fun reportProgress() {
                if (done - lastReported >= 500_000) {
                    lastReported = done
                    db.downloadDao().updateProgress(track.serverId, track.sourceId, "running", done)
                }
            }
            reportProgress()
            while (true) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
                try {
                    when (conn.responseCode) {
                        200 -> done = 0L
                        206 -> {}
                        else -> throw IOException("HTTP ${conn.responseCode}")
                    }
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(out, done > 0).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isStopped && isRetryableStop()) return Result.retry()
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                done += n
                                reportProgress()
                            }
                        }
                    }
                    break
                } finally {
                    conn.disconnect()
                }
            }

            db.trackDao().setPath(trackId, Uri.fromFile(out).toString())
            db.downloadDao().upsert(
                DownloadEntity(track.serverId, track.sourceId, "done", out.length(), prefsFormat)
            )
            return Result.success()
        } catch (e: Exception) {
            db.downloadDao().upsert(
                DownloadEntity(track.serverId, track.sourceId, "failed", updatedAt = System.currentTimeMillis(), transcode = prefsFormat)
            )
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun isRetryableStop() = runAttemptCount < 3

    companion object {
        fun enqueue(context: Context, trackRowId: Long) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("trackRowId" to trackRowId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("dl_$trackRowId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
