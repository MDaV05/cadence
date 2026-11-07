package com.cadence.music.data.downloads

import com.cadence.music.data.db.DownloadEntity

/**
 * Byte-weighted snapshot of the active download scope.
 *
 * [fraction] weighs by bytes so a 60 MB track counts six times a 10 MB one,
 * and counts only bytes that arrived: a failed track's [bytesDone] never
 * counts as progress, while its [totalBytes] stays in the denominator as
 * outstanding work.
 */
data class ByteProgress(
    val fraction: Float,
    val bytesDone: Long,
    val bytesTotal: Long,
    val doneCount: Int,
    val totalCount: Int,
    val failedCount: Int,
)

/**
 * Rows the progress bar is allowed to describe: every pending row plus the
 * finished siblings of any batch that still has pending rows. Empty when the
 * queue is idle, which is what makes the bar disappear between jobs.
 *
 * Kotlin twin of [com.cadence.music.data.db.DownloadDao.observeActiveScope];
 * applied on top of the DAO result so UI code can also scope ad-hoc lists.
 */
fun activeScope(rows: List<DownloadEntity>): List<DownloadEntity> {
    val pending = rows.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
    if (pending.isEmpty()) return emptyList()
    val batches = pending.map { it.batchId }.toSet()
    return rows.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.batchId in batches }
}

/** Byte-weighted progress over [activeScope] rows. */
fun byteProgress(rows: List<DownloadEntity>): ByteProgress {
    val done = rows.filter { it.status == DownloadStatus.DONE }
    val pending = rows.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
    val failed = rows.filter { it.status == DownloadStatus.FAILED }
    val bytesDone = done.sumOf { it.bytesDone } + pending.sumOf { it.bytesDone }
    val bytesTotal = done.sumOf { it.totalBytes } + pending.sumOf { it.totalBytes } + failed.sumOf { it.totalBytes }
    val fraction = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    return ByteProgress(fraction, bytesDone, bytesTotal, done.size, rows.size, failed.size)
}

/**
 * Per-row retry backoff (spec D5): the first transient failure waits 15 s, the
 * second a minute, everything after that five minutes flat. A waiting row stays
 * QUEUED with its error message and is not eligible for pickup until
 * `nextAttemptAt` passes — attempts are not burned during an outage.
 */
fun backoffMillis(attempts: Long): Long = when {
    attempts <= 1 -> 15_000L
    attempts == 2L -> 60_000L
    else -> 300_000L
}

/**
 * Inter-track pacing (spec D6): telegram/bale sources get a full second
 * between tracks, everything else 250 ms. A constant table on purpose — no
 * settings.
 */
fun pacingMillis(sourceId: String): Long =
    if (sourceId == "telegram" || sourceId == "bale") 1_000L else 250L

/**
 * Pure core of `LibraryRepository.moveQueued`: [queued] is the queue already in
 * drain order (priority DESC, enqueuedAt ASC — the nextQueued order), and the
 * result is the rows to write back so [serverId]/[sourceId] lands one slot up
 * (-1) or down (+1). Null when the move is a no-op (row unknown or at an edge).
 *
 * A move swaps the pair's order keys. Rows of one enqueue call share priority
 * AND enqueuedAt, so a bare swap between two tied rows is a no-op; their whole
 * tied run is first re-keyed with 1ms spacing (relative order unchanged) so
 * the swap changes the actual order.
 * ponytail: 1ms spacing can collide with another run enqueued within runSize
 * ms; derive keys from the gap between runs if that ever shows up in practice.
 */
internal fun queuedMoveWrites(
    queued: List<DownloadEntity>,
    serverId: String,
    sourceId: String,
    delta: Int,
): List<DownloadEntity>? {
    if (delta == 0) return null
    val index = queued.indexOfFirst { it.trackServerId == serverId && it.sourceId == sourceId }
    val target = index + delta
    if (index < 0 || target < 0 || target >= queued.size) return null
    val moving = queued[index]
    val neighbor = queued[target]
    var movingEnqueuedAt = moving.enqueuedAt
    var neighborEnqueuedAt = neighbor.enqueuedAt
    val writes = mutableListOf<DownloadEntity>()
    if (moving.priority == neighbor.priority && moving.enqueuedAt == neighbor.enqueuedAt) {
        var start = index
        while (start > 0 && sameOrderKey(queued[start - 1], moving)) start--
        var end = index
        while (end < queued.size - 1 && sameOrderKey(queued[end + 1], moving)) end++
        val base = moving.enqueuedAt
        for (i in start..end) {
            val enqueuedAt = base + (i - start)
            writes += queued[i].copy(enqueuedAt = enqueuedAt)
            if (i == index) movingEnqueuedAt = enqueuedAt
            if (i == target) neighborEnqueuedAt = enqueuedAt
        }
    }
    // The swap itself: each row takes the other's order keys.
    writes += moving.copy(priority = neighbor.priority, enqueuedAt = neighborEnqueuedAt)
    writes += neighbor.copy(priority = moving.priority, enqueuedAt = movingEnqueuedAt)
    return writes
}

private fun sameOrderKey(a: DownloadEntity, b: DownloadEntity): Boolean =
    a.priority == b.priority && a.enqueuedAt == b.enqueuedAt
