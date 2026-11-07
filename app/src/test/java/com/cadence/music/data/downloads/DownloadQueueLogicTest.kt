package com.cadence.music.data.downloads

import com.cadence.music.data.db.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Active-scope selection and byte-weighted progress: the bar describes only
 * the batches that still have pending rows, and a failed track counts against
 * the bar (its bytes are outstanding work, not progress).
 */
class DownloadQueueLogicTest {

    private var seq = 0

    private fun row(
        status: String,
        batch: Long,
        bytesDone: Long = 0,
        totalBytes: Long = -1,
    ): DownloadEntity {
        seq += 1
        return DownloadEntity(
            trackServerId = "t$seq",
            sourceId = "plex",
            status = status,
            bytesDone = bytesDone,
            totalBytes = totalBytes,
            batchId = batch,
        )
    }

    @Test
    fun `scope is empty when nothing is pending`() {
        val rows = listOf(row(DownloadStatus.DONE, 1), row(DownloadStatus.DONE, 2))
        assertTrue(activeScope(rows).isEmpty())
    }

    @Test
    fun `fraction is byte weighted across the active batch`() {
        val rows = listOf(
            row(DownloadStatus.DONE, 1, bytesDone = 10, totalBytes = 10),
            row(DownloadStatus.RUNNING, 1, bytesDone = 5, totalBytes = 15),
            row(DownloadStatus.QUEUED, 1, bytesDone = 0, totalBytes = 15),
        )
        assertEquals(15f / 40f, byteProgress(activeScope(rows)).fraction, 0.001f)
    }

    @Test
    fun `failed rows count against the bar`() {
        // The batch is still active (a queued sibling), so the failed track's
        // bytes stay in the denominator as outstanding work.
        val rows = listOf(
            row(DownloadStatus.DONE, 1, bytesDone = 10, totalBytes = 10),
            row(DownloadStatus.FAILED, 1, bytesDone = 0, totalBytes = 5),
            row(DownloadStatus.QUEUED, 1, bytesDone = 0, totalBytes = 5),
        )
        assertEquals(0.5f, byteProgress(activeScope(rows)).fraction, 0.001f)
    }

    @Test
    fun `two pending batches are both included`() {
        val rows = listOf(
            row(DownloadStatus.DONE, 1, bytesDone = 10, totalBytes = 10),
            row(DownloadStatus.QUEUED, 1, totalBytes = 10),
            row(DownloadStatus.RUNNING, 2, bytesDone = 3, totalBytes = 9),
            row(DownloadStatus.DONE, 3, bytesDone = 99, totalBytes = 99),
        )
        val scope = activeScope(rows)
        assertEquals(setOf(1L, 2L), scope.map { it.batchId }.toSet())
        assertEquals(3, scope.size)
    }

    @Test
    fun `historical done-only rows leave the scope empty`() {
        val rows = listOf(row(DownloadStatus.DONE, 1), row(DownloadStatus.DONE, 2), row(DownloadStatus.DONE, 3))
        assertTrue(activeScope(rows).isEmpty())
    }

    @Test
    fun `failed bytes never count as progress`() {
        val rows = listOf(
            row(DownloadStatus.DONE, 1, bytesDone = 10, totalBytes = 10),
            row(DownloadStatus.FAILED, 1, bytesDone = 4, totalBytes = 10),
            row(DownloadStatus.QUEUED, 1, totalBytes = 10),
        )
        val progress = byteProgress(activeScope(rows))
        assertEquals(10L, progress.bytesDone)
        assertEquals(30L, progress.bytesTotal)
        assertEquals(10f / 30f, progress.fraction, 0.001f)
        assertEquals(1, progress.doneCount)
        assertEquals(1, progress.failedCount)
        assertEquals(3, progress.totalCount)
    }
}
