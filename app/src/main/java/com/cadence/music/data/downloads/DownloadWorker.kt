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
        val url = inputData.getString("url") ?: return Result.failure()
        if (trackId <= 0) return Result.failure()

        val db = app.container.database
        val track = db.trackDao().byId(trackId) ?: return Result.failure()

        db.downloadDao().upsert(
            DownloadEntity(track.serverId, track.sourceId, "running")
        )

        val out = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
            .resolve("${track.serverId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.audio")

        try {
            var done = if (out.exists()) out.length() else 0L
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
                DownloadEntity(track.serverId, track.sourceId, "done", out.length())
            )
            return Result.success()
        } catch (e: Exception) {
            db.downloadDao().upsert(
                DownloadEntity(track.serverId, track.sourceId, "failed", updatedAt = System.currentTimeMillis())
            )
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun isRetryableStop() = runAttemptCount < 3

    companion object {
        fun enqueue(context: Context, trackRowId: Long, url: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("trackRowId" to trackRowId, "url" to url))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("dl_$trackRowId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
