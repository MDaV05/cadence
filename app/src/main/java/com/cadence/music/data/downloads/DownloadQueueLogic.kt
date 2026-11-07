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
