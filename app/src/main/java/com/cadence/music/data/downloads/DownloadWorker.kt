package com.cadence.music.data.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cadence.music.CadenceApp
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.db.DownloadEntity
import com.cadence.music.data.db.TrackEntity
import com.cadence.music.data.source.sameOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

/** The user removed this track from the queue. Delete the partial and move on. */
private class DownloadCancelled : Exception()

/** The user paused the queue. Keep the partial; it will resume later. */
private class DownloadPaused : Exception()

private enum class Outcome { CONTINUE, RETRY, STOP }

/**
 * Drains the persistent queue one track at a time. The row is the source of
 * truth: a queued row exists before WorkManager runs, survives process death,
 * and carries the byte total, attempt count, and queue order the UI needs.
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Batches whose rows settled (done or failed) during this drain run. */
    private val settledBatches = mutableSetOf<Long>()

    override suspend fun doWork(): Result {
        val app = applicationContext as CadenceApp
        val prefs = app.container.prefs
        if (prefs.downloadsPaused) return Result.success()
        // Legacy work requests from the old per-track chain may overlap this
        // queue after an upgrade. One drain loop is enough.
        if (!draining.compareAndSet(false, true)) return Result.success()
        val db = app.container.database
        return try {
            requeueInterrupted(db)
            while (!isStopped) {
                if (prefs.downloadsPaused || pauseRequested) return Result.success()
                // Wi-Fi-only and its one-shot overrides (spec D2) decide this
                // gate: "Download anyway" must actually download on metered, a
                // not-remembered "Wait for Wi-Fi" must actually wait.
                val wifiOnly = (prefs.downloadWifiOnly || prefs.waitWifiOnce) && !prefs.allowMeteredOnce
                if (wifiOnly && !isUnmetered(applicationContext)) {
                    return Result.retry()
                }
                val row = db.downloadDao().nextQueued(System.currentTimeMillis())
                if (row == null) {
                    // The queue drained: the one-shot network overrides have
                    // served their purpose (spec D2), so the next wait starts clean.
                    prefs.allowMeteredOnce = false
                    prefs.waitWifiOnce = false
                    reportCompletion(db)
                    DownloadNotifications.cancel(applicationContext)
                    return Result.success()
                }
                val track = db.trackDao().byServerId(row.trackServerId)
                if (track == null || track.sourceId != row.sourceId) {
                    db.downloadDao().delete(row.trackServerId, row.sourceId)
                    continue
                }
                when (downloadOne(app, track)) {
                    Outcome.CONTINUE -> Unit
                    Outcome.RETRY -> return Result.retry()
                    Outcome.STOP -> return Result.success()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A row-level failure is already persisted. A database failure keeps
            // the queue alive for the next run instead of losing it.
            Result.retry()
        } finally {
            draining.set(false)
        }
    }

    /**
     * Rows left "running" by a killed process would otherwise never be picked
     * up again. An active track is skipped so two workers can never reset each
     * other mid-flight.
     */
    private suspend fun requeueInterrupted(db: AppDatabase) {
        for (row in db.downloadDao().byStatus(DownloadStatus.RUNNING)) {
            val track = db.trackDao().byServerId(row.trackServerId) ?: continue
            if (isActive(track.id)) continue
            db.downloadDao().update(
                row.trackServerId,
                row.sourceId,
                DownloadStatus.QUEUED,
                row.bytesDone,
                row.totalBytes,
                null,
                row.attempts,
            )
        }
    }

    private suspend fun downloadOne(app: CadenceApp, track: TrackEntity): Outcome {
        if (!activeTracks.add(track.id)) return Outcome.CONTINUE
        cancelRequests.remove(track.id)
        return try {
            val current = app.container.database.downloadDao()
                .byTrack(track.sourceId, track.serverId)
                ?: return Outcome.CONTINUE
            if (current.status != DownloadStatus.QUEUED) return Outcome.CONTINUE
            download(app, track, current)
        } catch (_: DownloadCancelled) {
            Outcome.CONTINUE
        } catch (_: DownloadPaused) {
            Outcome.STOP
        } finally {
            activeTracks.remove(track.id)
            cancelRequests.remove(track.id)
        }
    }

    private suspend fun download(app: CadenceApp, track: TrackEntity, prior: DownloadEntity): Outcome {
        val db = app.container.database
        val prefs = app.container.prefs
        val trackId = track.id
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
        var current = track.path?.let(::fileFromPath)?.takeIf { it.exists() }

        // Reuse a playable file in the requested format. This also adopts a file
        // whose download row was lost, and replaces a row that was wrongly "done".
        val currentIssue = current?.let(::audioFileIssue)
        // Pre-transcode rows have a null transcode; they hold the original file.
        val sameFormat = prior.transcode == format ||
            (prior.transcode == null && format == "raw")
        if (current != null && currentIssue == null && sameFormat) {
            db.trackDao().setPath(trackId, Uri.fromFile(current).toString())
            db.downloadDao().update(
                track.serverId,
                track.sourceId,
                DownloadStatus.DONE,
                current.length(),
                current.length(),
                null,
                0,
            )
            settledBatches.add(prior.batchId)
            return Outcome.CONTINUE
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
                db, track, prior, "server is not configured", retryable = false, clearPath = false,
            )

        // A format switch must not reuse the old container as "done".
        if (prior.transcode != format) {
            current?.let { runCatching { it.delete() } }
            current = null
            db.trackDao().setPath(trackId, null)
            discardPartial()
        }
        // A partial that is actually a JSON/HTML error page can never be resumed.
        if (tmp.exists() && looksLikeErrorBody(tmp)) discardPartial()

        var done = if (tmp.exists()) tmp.length() else 0L
        var total = prior.totalBytes
        var lastReported = -1L
        var attempts = prior.attempts
        // If the server honors If-Range only when the file is unchanged, a
        // changed file comes back as 200 and the partial is replaced below.
        // No validator means we cannot prove the partial still matches the
        // source, so restart rather than risk splicing two different files.
        var validator = if (done > 0) readValidator(meta) else null
        if (done > 0 && validator == null) {
            discardPartial()
            done = 0L
        }

        val running = db.downloadDao().update(
            track.serverId, track.sourceId, DownloadStatus.RUNNING,
            done, total, null, attempts,
        )
        if (running == 0) return Outcome.CONTINUE
        enterForeground(db)

        suspend fun reportProgress(force: Boolean = false) {
            if (force || done - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = done
                // A relink can move this row to a new server id mid-flight, so a
                // zero-row update is not proof of cancellation; the loop's
                // cancelRequests check is the authoritative signal.
                db.downloadDao().updateProgress(
                    track.serverId, track.sourceId, DownloadStatus.RUNNING, done, total,
                )
                // Same 500 KB cadence as the row: the notification is the
                // progress surface while the app is closed (spec D7).
                val p = downloadScopeProgress(db)
                DownloadNotifications.showProgress(
                    applicationContext, p.doneCount, p.totalCount, p.bytesDone, p.bytesTotal, paused = false,
                )
            }
        }
        reportProgress(force = true)

        try {
            val conn = openFollowingRedirects(url, done, validator)
            try {
                val code = conn.responseCode
                when {
                    code == 200 -> {
                        done = 0L
                        writeValidator(meta, conn, fullResponse = true)
                        val announcedLen = conn.contentLengthLong
                        total = if (announcedLen > 0) announcedLen else -1L
                    }
                    code == 206 -> {
                        val range = conn.getHeaderField("Content-Range")
                        val start = contentRangeStart(range)
                        val announcedTotal = contentRangeTotal(range)
                        if (range == null || start != done) {
                            discardPartial()
                            throw DownloadRetryable("server resumed at the wrong offset")
                        }
                        writeValidator(meta, conn, fullResponse = false)
                        val announcedLen = conn.contentLengthLong
                        total = when {
                            announcedTotal != null && announcedTotal > 0 -> announcedTotal
                            announcedLen > 0 -> done + announcedLen
                            else -> -1L
                        }
                    }
                    code == 416 -> {
                        // Stale/unsatisfiable range (file changed server-side): drop the
                        // partial and let the next attempt start clean.
                        discardPartial()
                        done = 0L
                        total = -1L
                        throw DownloadRetryable("HTTP 416")
                    }
                    code == 408 || code == 425 || code == 429 || code in 500..599 ->
                        throw DownloadRetryable("HTTP $code")
                    // A missing id (404/410) is just as stale as Subsonic 70.
                    else -> throw DownloadRejected("HTTP $code", staleId = code == 404 || code == 410)
                }
                reportProgress(force = true)

                val needed = if (total > done) (total - done) + HEADROOM_BYTES else MIN_FREE_UNKNOWN_BYTES
                if (applicationContext.filesDir.usableSpace <= needed) {
                    throw DownloadRejected("not enough free storage")
                }

                conn.inputStream.use { input ->
                    FileOutputStream(tmp, done > 0).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelRequests.contains(trackId)) throw DownloadCancelled()
                            if (pauseRequested) throw DownloadPaused()
                            if (isStopped) {
                                throw CancellationException("download worker stopped")
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
                if (total > 0 && done != total) {
                    throw DownloadRetryable("truncated: $done of $total bytes")
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
                            return Outcome.CONTINUE
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
                        val stored = db.downloadDao().update(
                            latest.serverId,
                            latest.sourceId,
                            DownloadStatus.DONE,
                            out.length(),
                            out.length(),
                            null,
                            0,
                        )
                        if (stored == 0) {
                            // The user cancelled while the final bytes were landing.
                            runCatching { out.delete() }
                            db.trackDao().setPath(latest.id, null)
                            return Outcome.CONTINUE
                        }
                        db.trackDao().setPath(latest.id, Uri.fromFile(out).toString())
                        settledBatches.add(prior.batchId)
                        return Outcome.CONTINUE
                    }
                    is PayloadVerdict.Rejected -> {
                        throw DownloadRejected(verdict.message, staleId = verdict.staleId)
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: DownloadCancelled) {
            discardPartial()
            current?.let { runCatching { it.delete() } }
            throw e
        } catch (e: DownloadPaused) {
            markQueued(db, track, done, total, attempts)
            throw e
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                markQueued(db, track, done, total, attempts)
            }
            throw e
        } catch (e: DownloadRejected) {
            discardPartial()
            current?.let { runCatching { it.delete() } }
            val latest = db.trackDao().byId(trackId) ?: track
            if (e.staleId) {
                latest.albumKey?.let { runCatching { db.albumDao().invalidateToken(it) } }
            }
            return markFailed(
                db, latest, prior, e.message ?: "download rejected",
                retryable = false, clearPath = true, doneBytes = done, totalBytes = total,
            )
        } catch (e: Exception) {
            val latest = db.trackDao().byId(trackId) ?: track
            if (isStopped || pauseRequested) {
                markQueued(db, latest, done, total, attempts)
                return Outcome.STOP
            }
            attempts += 1
            val retryable = e is DownloadRetryable || e is IOException
            // A raw connection failure (unknown host, refused, timeout) can mean
            // the cached LAN/public URL is wrong for this network. Switch before
            // the next attempt; HTTP 5xx/truncation is not URL-specific.
            if (e is IOException && e !is DownloadRetryable) {
                runCatching { app.container.library.switchActiveUrlFor(latest.serverId) }
            }
            // Keep a resumable partial until the final attempt, then reclaim the space.
            val willRetry = retryable && attempts < MAX_ATTEMPTS
            if (!willRetry) {
                discardPartial()
                current?.let { runCatching { it.delete() } }
            }
            if (willRetry) {
                db.downloadDao().update(
                    latest.serverId, latest.sourceId, DownloadStatus.QUEUED,
                    done, total, e.message ?: "retrying", attempts,
                )
                return Outcome.RETRY
            }
            return markFailed(
                db, latest, prior, e.message ?: "download failed",
                retryable = false, clearPath = true, doneBytes = done, totalBytes = total,
                attempts = attempts,
            )
        }
    }

    private suspend fun markFailed(
        db: AppDatabase,
        track: TrackEntity,
        prior: DownloadEntity,
        message: String,
        retryable: Boolean,
        clearPath: Boolean = true,
        doneBytes: Long = 0L,
        totalBytes: Long = prior.totalBytes,
        attempts: Int = prior.attempts,
    ): Outcome {
        // Re-read once more: a relink can land between the caller's lookup and
        // this write, and a row under the old server id would be an orphan.
        val latest = runCatching { db.trackDao().byId(track.id) }.getOrNull() ?: track
        if (retryable) {
            db.downloadDao().update(
                latest.serverId, latest.sourceId, DownloadStatus.QUEUED,
                doneBytes, totalBytes, message, attempts,
            )
            return Outcome.RETRY
        }
        if (clearPath) runCatching { db.trackDao().setPath(latest.id, null) }
        db.downloadDao().update(
            latest.serverId, latest.sourceId, DownloadStatus.FAILED,
            0, totalBytes, message, attempts,
        )
        settledBatches.add(prior.batchId)
        // Once a track is finally failed, continue the queue; the failed row
        // (shown in Downloads) is what matters, not WorkInfo state.
        return Outcome.CONTINUE
    }

    private suspend fun markQueued(
        db: AppDatabase,
        track: TrackEntity,
        doneBytes: Long,
        totalBytes: Long,
        attempts: Int = 0,
    ) {
        val latest = runCatching { db.trackDao().byId(track.id) }.getOrNull() ?: track
        runCatching {
            db.downloadDao().update(
                latest.serverId, latest.sourceId, DownloadStatus.QUEUED,
                doneBytes, totalBytes, null, attempts,
            )
        }
    }

    /**
     * Promotes the drain to a dataSync foreground service while bytes flow
     * (spec D7). The platform can refuse a background FGS start (Android 12+);
     * a plain notification keeps the progress visible instead.
     */
    private suspend fun enterForeground(db: AppDatabase) {
        val p = downloadScopeProgress(db)
        val info = ForegroundInfo(
            DownloadNotifications.NOTIFICATION_ID_PROGRESS,
            DownloadNotifications.progressNotification(
                applicationContext, p.doneCount, p.totalCount, p.bytesDone, p.bytesTotal, paused = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        try {
            setForeground(info)
        } catch (_: Exception) {
            DownloadNotifications.showProgress(
                applicationContext, p.doneCount, p.totalCount, p.bytesDone, p.bytesTotal, paused = false,
            )
        }
    }

    /**
     * The completion notice for the batches that settled in this run (spec
     * D7): "Download complete · N tracks · size", honest about failures.
     * Counts come from the rows themselves, so a batch finished across
     * several runs is still reported once, whole.
     */
    private suspend fun reportCompletion(db: AppDatabase) {
        if (settledBatches.isEmpty()) return
        val done = db.downloadDao().byStatus(DownloadStatus.DONE).filter { it.batchId in settledBatches }
        val failed = db.downloadDao().byStatus(DownloadStatus.FAILED).filter { it.batchId in settledBatches }
        if (done.isEmpty() && failed.isEmpty()) return
        DownloadNotifications.showComplete(
            applicationContext,
            title = if (done.isEmpty()) "Download failed" else "Download complete",
            count = done.size,
            bytes = done.sumOf { it.bytesDone },
            failed = failed.size,
        )
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
        private val activeTracks = ConcurrentHashMap.newKeySet<Long>()

        /** Tracks the user removed while their bytes were in flight. */
        private val cancelRequests = ConcurrentHashMap.newKeySet<Long>()

        /** One drain loop per process; legacy work requests become no-ops. */
        private val draining = AtomicBoolean(false)

        @Volatile
        private var pauseRequested = false

        /** True while this track's worker is writing its file in this process. */
        internal fun isActive(trackRowId: Long): Boolean = activeTracks.contains(trackRowId)

        /** Stops the active track and deletes its partial; the queue continues. */
        fun requestCancel(trackRowId: Long) {
            cancelRequests.add(trackRowId)
        }

        /** Stops every in-flight writer; the notification's "Cancel all" also drops their rows. */
        fun requestCancelAll() {
            for (trackRowId in activeTracks) cancelRequests.add(trackRowId)
        }

        /** Stops the active track but keeps its partial for a later resume. */
        fun requestPause() {
            pauseRequested = true
        }

        fun clearPause() {
            pauseRequested = false
        }

        internal val isPauseRequested: Boolean get() = pauseRequested
    }
}
