package com.cadence.music.data.downloads

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cadence.music.CadenceApp
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.DownloadEntity
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.source.sameOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// Storage guard rails: refuse to start a write unless filesDir has the announced
// size plus headroom (or a flat floor when the server hides Content-Length).
private const val HEADROOM_BYTES = 50L * 1024 * 1024
private const val MIN_FREE_UNKNOWN_BYTES = 100L * 1024 * 1024
private const val MAX_ATTEMPTS = 3
private const val MAX_REDIRECTS = 3
private const val PROGRESS_STEP_BYTES = 500_000L

/** A server said "no" — retrying the same request would only hammer it again. */
private class DownloadRejected(message: String, val staleId: Boolean = false) : IOException(message)

/** A transient network/HTTP failure; keep the partial and try again. */
private class DownloadRetryable(message: String) : IOException(message)

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CadenceApp
        val trackId = inputData.getLong("trackRowId", -1)
        if (trackId <= 0) return Result.success()
        // A user tap (unique name "dl_<id>") can overlap the same track waiting
        // in the bulk chain ("downloads"); both would write the same .part.
        // Retry so the second request picks up after the active one finishes.
        if (!activeTracks.add(trackId)) return Result.retry()
        return try {
            download(app, trackId)
        } catch (e: CancellationException) {
            // WorkManager is stopping us; never turn that into a failed download.
            throw e
        } catch (e: Exception) {
            // download() handles every expected failure itself, but an
            // unexpected throw must not leave a "running" row forever.
            runCatching {
                val db = app.container.database
                val current = db.trackDao().byId(trackId) ?: return@runCatching
                db.downloadDao().upsert(
                    DownloadEntity(
                        current.serverId, current.sourceId, "failed", 0,
                        app.container.prefs.downloadFormat,
                        errorText = e.message ?: "download failed",
                    )
                )
            }
            // Never fail the serial chain from here, or every queued track
            // after this one dies.
            Result.success()
        } finally {
            activeTracks.remove(trackId)
        }
    }

    private suspend fun download(app: CadenceApp, trackId: Long): Result {
        val db = app.container.database
        val prefs = app.container.prefs
        // The track may have been deleted while it sat in the bulk queue.
        val track = db.trackDao().byId(trackId) ?: return Result.success()
        val format = prefs.downloadFormat

        val dir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val base = downloadBaseName(track.sourceId, track.serverId)
        val tmp = File(dir, "$base.part")
        // ETag/Last-Modified of the partial, so a resumed Range request can be
        // rejected by the server when the file changed underneath us.
        val meta = File(dir, "$base.part.meta")
        fun discardPartial() {
            runCatching { tmp.delete() }
            runCatching { meta.delete() }
        }
        val prior = db.downloadDao().byTrack(track.sourceId, track.serverId)
        var current = track.path?.let(::fileFromPath)?.takeIf { it.exists() }

        // Reuse a playable file in the requested format. This also adopts a file
        // whose download row was lost, and replaces a row that was wrongly "done".
        val currentIssue = current?.let(::audioFileIssue)
        // Pre-transcode rows have a null transcode; they hold the original file.
        val sameFormat = prior == null || prior.transcode == format ||
            (prior.transcode == null && format == "raw")
        if (current != null && currentIssue == null && sameFormat) {
            db.trackDao().setPath(trackId, Uri.fromFile(current).toString())
            if (prior == null || prior.status != "done" || prior.transcode != format) {
                db.downloadDao().upsert(
                    DownloadEntity(track.serverId, track.sourceId, "done", current.length(), format)
                )
            }
            return Result.success()
        }
        // Anything that failed verification is poison: deleting it and clearing the
        // path is what lets the track become downloadable again.
        if (current != null && currentIssue != null) {
            runCatching { current.delete() }
            current = null
            db.trackDao().setPath(trackId, null)
        }

        val url = app.container.library.downloadUrlFor(track.serverId, format, prefs.downloadBitrate)
            ?: return markFailed(
                db, track, prior?.transcode ?: format, "server is not configured",
                retryable = false, clearPath = false,
            )

        // A format switch must not reuse the old container as "done".
        if (prior != null && prior.transcode != format) {
            current?.let { runCatching { it.delete() } }
            current = null
            db.trackDao().setPath(trackId, null)
            discardPartial()
        }
        // A partial that is actually a JSON/HTML error page can never be resumed.
        if (tmp.exists() && looksLikeErrorBody(tmp)) discardPartial()

        db.downloadDao().upsert(
            DownloadEntity(track.serverId, track.sourceId, "running", transcode = format)
        )

        var done = if (tmp.exists()) tmp.length() else 0L
        var lastReported = -1L
        // If the server honors If-Range only when the file is unchanged, a
        // changed file comes back as 200 and the partial is replaced below.
        // No validator means we cannot prove the partial still matches the
        // source, so restart rather than risk splicing two different files.
        var validator = if (done > 0) readValidator(meta) else null
        if (done > 0 && validator == null) {
            discardPartial()
            done = 0L
        }
        suspend fun reportProgress() {
            if (done - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = done
                db.downloadDao().updateProgress(track.serverId, track.sourceId, "running", done)
            }
        }
        reportProgress()

        try {
            val conn = openFollowingRedirects(url, done, validator)
            try {
                val code = conn.responseCode
                var expectedTotal: Long
                when {
                    code == 200 -> {
                        done = 0L
                        writeValidator(meta, conn, fullResponse = true)
                        val announcedLen = conn.contentLengthLong
                        expectedTotal = if (announcedLen > 0) announcedLen else -1L
                    }
                    code == 206 -> {
                        val range = conn.getHeaderField("Content-Range")
                        val start = contentRangeStart(range)
                        val total = contentRangeTotal(range)
                        if (range == null || start != done) {
                            discardPartial()
                            throw DownloadRetryable("server resumed at the wrong offset")
                        }
                        writeValidator(meta, conn, fullResponse = false)
                        val announcedLen = conn.contentLengthLong
                        expectedTotal = when {
                            total != null && total > 0 -> total
                            announcedLen > 0 -> done + announcedLen
                            else -> -1L
                        }
                    }
                    code == 416 -> {
                        // Stale/unsatisfiable range (file changed server-side): drop the
                        // partial and let the next attempt start clean.
                        discardPartial()
                        db.downloadDao().updateProgress(track.serverId, track.sourceId, "queued", 0)
                        throw DownloadRetryable("HTTP 416")
                    }
                    code == 408 || code == 425 || code == 429 || code in 500..599 ->
                        throw DownloadRetryable("HTTP $code")
                    // A missing id (404/410) is just as stale as Subsonic 70.
                    else -> throw DownloadRejected("HTTP $code", staleId = code == 404 || code == 410)
                }

                val needed = if (expectedTotal > done) (expectedTotal - done) + HEADROOM_BYTES else MIN_FREE_UNKNOWN_BYTES
                if (applicationContext.filesDir.usableSpace <= needed) {
                    throw DownloadRejected("not enough free storage")
                }

                conn.inputStream.use { input ->
                    FileOutputStream(tmp, done > 0).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) {
                                if (runAttemptCount < MAX_ATTEMPTS) {
                                    db.downloadDao().updateProgress(
                                        track.serverId, track.sourceId, "queued", done,
                                    )
                                    return Result.retry()
                                }
                                throw DownloadRetryable("stopped")
                            }
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
                    throw DownloadRetryable("truncated: $done of $expectedTotal bytes")
                }

                // The whole point: no response body becomes a track until it
                // proves it is audio rather than a Subsonic error page.
                val head = readHead(tmp, DOWNLOAD_SNIFF_BYTES)
                    ?: throw DownloadRetryable("could not read downloaded file")
                when (val verdict = classifyDownloadPayload(
                    conn.contentType, conn.getHeaderField("Content-Disposition"), head, tmp.length(),
                )) {
                    is PayloadVerdict.Audio -> {
                        // A sync may have relinked this track to a new server
                        // id while the bytes were in flight. Store the file
                        // and row under the current ids, never the old ones.
                        val latest = db.trackDao().byId(trackId)
                        if (latest == null) {
                            discardPartial()
                            current?.let { runCatching { it.delete() } }
                            return Result.success()
                        }
                        val outBase = downloadBaseName(latest.sourceId, latest.serverId)
                        val out = File(dir, "$outBase.${verdict.extension}")
                        if (current != null && current.absolutePath != out.absolutePath) {
                            runCatching { current.delete() }
                        }
                        if (out.exists()) runCatching { out.delete() }
                        if (!tmp.renameTo(out)) throw DownloadRetryable("could not finalize file")
                        runCatching { meta.delete() }
                        // Sweep a legacy ".audio" / old-server-id sibling so
                        // only one copy of this track stays on disk.
                        dir.listFiles()?.filter {
                            (it.name.startsWith("$outBase.") || it.name.startsWith("$base.")) &&
                                it.name != out.name && !it.name.endsWith(".part") &&
                                !it.name.endsWith(".meta")
                        }?.forEach { runCatching { it.delete() } }
                        db.trackDao().setPath(trackId, Uri.fromFile(out).toString())
                        db.downloadDao().upsert(
                            DownloadEntity(latest.serverId, latest.sourceId, "done", out.length(), format)
                        )
                        return Result.success()
                    }
                    is PayloadVerdict.Rejected -> {
                        throw DownloadRejected(verdict.message, staleId = verdict.staleId)
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val latest = runCatching { db.trackDao().byId(trackId) }.getOrNull() ?: track
                runCatching {
                    db.downloadDao().updateProgress(latest.serverId, latest.sourceId, "queued", done)
                }
            }
            throw e
        } catch (e: DownloadRejected) {
            discardPartial()
            current?.let { runCatching { it.delete() } }
            val latest = db.trackDao().byId(trackId) ?: track
            if (e.staleId) {
                latest.albumKey?.let { runCatching { db.albumDao().invalidateToken(it) } }
            }
            return markFailed(db, latest, format, e.message ?: "download rejected", retryable = false)
        } catch (e: Exception) {
            val latest = db.trackDao().byId(trackId) ?: track
            if (isStopped) {
                db.downloadDao().updateProgress(latest.serverId, latest.sourceId, "queued", done)
                return Result.retry()
            }
            val retryable = e is DownloadRetryable || e is IOException
            // A raw connection failure (unknown host, refused, timeout) can mean
            // the cached LAN/public URL is wrong for this network. Switch before
            // the next attempt; HTTP 5xx/truncation is not URL-specific.
            if (e is IOException && e !is DownloadRetryable) {
                runCatching { app.container.library.switchActiveUrlFor(latest.serverId) }
            }
            // Keep a resumable partial until the final attempt, then reclaim the space.
            if (!retryable || runAttemptCount + 1 >= MAX_ATTEMPTS) {
                discardPartial()
            }
            current?.let { runCatching { it.delete() } }
            return markFailed(
                db, latest, format, e.message ?: "download failed",
                retryable = retryable && runAttemptCount + 1 < MAX_ATTEMPTS,
                doneBytes = done,
            )
        }
    }

    private suspend fun markFailed(
        db: AppDatabase,
        track: TrackEntity,
        format: String,
        message: String,
        retryable: Boolean,
        clearPath: Boolean = true,
        doneBytes: Long = 0L,
    ): Result {
        // Re-read once more: a relink can land between the caller's lookup and
        // this write, and a row under the old server id would be an orphan.
        val latest = runCatching { db.trackDao().byId(track.id) }.getOrNull() ?: track
        if (retryable) {
            runCatching {
                db.downloadDao().updateProgress(latest.serverId, latest.sourceId, "queued", doneBytes)
            }
            return Result.retry()
        }
        if (clearPath) runCatching { db.trackDao().setPath(latest.id, null) }
        runCatching {
            db.downloadDao().upsert(
                DownloadEntity(
                    latest.serverId, latest.sourceId, "failed", 0, format,
                    errorText = message,
                )
            )
        }
        // Once a track is finally failed, report success so the serial bulk chain
        // continues to the next track; the failed row (shown in Downloads) is what
        // matters, not WorkInfo state.
        return Result.success()
    }

    private fun fileFromPath(path: String): File? =
        if (!path.startsWith("file:")) null
        else runCatching { File(java.net.URI(path)) }.getOrNull()

    /**
     * Opens [url], following at most [MAX_REDIRECTS] same-origin hops. The
     * download URL carries u/t/s credentials in its query, so a cross-host
     * redirect must be refused rather than followed (R3-07 parity).
     */
    private fun openFollowingRedirects(url: String, rangeStart: Long, validator: String?): HttpURLConnection {
        var current = URL(url)
        var hops = 0
        while (true) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            // Transparent gzip would corrupt a Range-based resume (and could
            // hand us an encoded HTML error page with no audio magic).
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=$rangeStart-")
                // The server returns 200 (full body) when the file changed,
                // which resets `done` and replaces the stale partial.
                if (!validator.isNullOrBlank()) conn.setRequestProperty("If-Range", validator)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || hops >= MAX_REDIRECTS) {
                    throw DownloadRejected("bad redirect")
                }
                val next = URL(current, location)
                if (!sameOrigin(current.toString(), next.toString())) {
                    throw DownloadRejected("refused a cross-server redirect")
                }
                current = next
                hops++
                continue
            }
            return conn
        }
    }

    private fun contentRangeStart(header: String?): Long? =
        header?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun contentRangeTotal(header: String?): Long? =
        header?.let { Regex("""bytes\s+\d+-\d+/(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** Stores the strongest validator the server sent for a resumable partial. */
    private fun writeValidator(meta: File, conn: HttpURLConnection, fullResponse: Boolean) {
        val value = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
            ?: conn.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() }
        if (value != null) {
            runCatching { meta.writeText(value.take(1_024)) }
        } else if (fullResponse) {
            // A fresh full response with no validator must not keep a stale
            // If-Range from the previous file.
            runCatching { meta.delete() }
        }
    }

    private fun readValidator(meta: File): String? =
        runCatching {
            meta.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    companion object {
        /** Tracks with a worker running in this process; guards shared .part files. */
        private val activeTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        /** True while this track's worker is writing its file in this process. */
        internal fun isActive(trackRowId: Long): Boolean = activeTracks.contains(trackRowId)

        /** Unique per track: a user tap never waits behind the bulk queue. */
        fun enqueue(context: Context, trackRowId: Long) = enqueue(context, trackRowId, serial = false)

        /**
         * Bulk (album/playlist/library) work is chained on one unique name so the
         * server never sees thousands of simultaneous requests. [ExistingWorkPolicy.APPEND_OR_REPLACE]
         * keeps the queue moving even after one item fails.
         */
        fun enqueue(context: Context, trackRowId: Long, serial: Boolean) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("trackRowId" to trackRowId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            val wm = WorkManager.getInstance(context)
            if (serial) {
                wm.beginUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
            } else {
                wm.enqueueUniqueWork("dl_$trackRowId", ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
