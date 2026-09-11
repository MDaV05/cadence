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

// Storage guard rails: refuse to start a write unless filesDir has the announced
// size plus headroom (or a flat floor when the server hides Content-Length).
private const val HEADROOM_BYTES = 50L * 1024 * 1024
private const val MIN_FREE_UNKNOWN_BYTES = 100L * 1024 * 1024

/** Conventional on-disk name for a downloaded track, safe against path traversal. */
fun downloadFileName(sourceId: String, serverId: String): String =
    serverId.ifBlank { sourceId }.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".audio"

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
            .resolve(downloadFileName(track.sourceId, track.serverId))
            .let { derived ->
                // v9 renamed serverIds; a track downloaded pre-rename still names the OLD file.
                track.path?.takeIf { it.startsWith("file:") }
                    ?.let { runCatching { File(java.net.URI(it)) }.getOrNull() }
                    ?.takeIf { it.exists() } ?: derived
            }
        val tmp = File(out.parentFile, "${out.name}.tmp")

        // Read before the "running" upsert below overwrites the row.
        // A format switch (raw->opus) must not reuse a stale file as done.
        val prior = db.downloadDao().byTrack(track.sourceId, track.serverId)
        if (prior?.status == "done" && prior.transcode == prefsFormat && out.exists()) {
            db.trackDao().setPath(trackId, Uri.fromFile(out).toString())
            return Result.success()
        }
        if (prior != null && prior.transcode != prefsFormat) {
            runCatching { out.delete() }
            runCatching { tmp.delete() }
        }

        db.downloadDao().upsert(
            DownloadEntity(track.serverId, track.sourceId, "running", transcode = prefsFormat)
        )

        try {
            // Resume works off the .tmp; the final path is only ever a verified file.
            var done = if (tmp.exists()) tmp.length() else 0L
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
                    val announcedLen = conn.contentLengthLong
                    when (conn.responseCode) {
                        200 -> done = 0L
                        206 -> {}
                        416 -> {
                            // Stale/unsatisfiable range (file changed server-side): drop the
                            // partial and reset the row so the next tap starts clean.
                            // Deliberately no in-worker refetch loop.
                            runCatching { tmp.delete() }
                            db.downloadDao().updateProgress(track.serverId, track.sourceId, "running", 0)
                            throw IOException("HTTP 416")
                        }
                        else -> throw IOException("HTTP ${conn.responseCode}")
                    }
                    // Space precheck before opening output: an announced size needs itself
                    // + headroom; an unknown size needs a flat floor (hostile endless stream).
                    val needed = if (announcedLen > 0) announcedLen + HEADROOM_BYTES else MIN_FREE_UNKNOWN_BYTES
                    if (applicationContext.filesDir.usableSpace <= needed) {
                        throw IOException("insufficient storage")
                    }
                    val expectedTotal = if (announcedLen > 0) done + announcedLen else -1L
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(tmp, done > 0).use { output ->
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
                    // Premature EOF must not be marked done when the size was known.
                    if (expectedTotal > 0 && done != expectedTotal) {
                        throw IOException("truncated: $done of $expectedTotal bytes")
                    }
                    break
                } finally {
                    conn.disconnect()
                }
            }

            if (!tmp.renameTo(out)) throw IOException("rename failed")
            db.trackDao().setPath(trackId, Uri.fromFile(out).toString())
            db.downloadDao().upsert(
                DownloadEntity(track.serverId, track.sourceId, "done", out.length(), prefsFormat)
            )
            return Result.success()
        } catch (e: Exception) {
            // No partial is ever left behind: drop the tmp and any legacy in-place file.
            runCatching { tmp.delete() }
            runCatching { out.delete() }
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
