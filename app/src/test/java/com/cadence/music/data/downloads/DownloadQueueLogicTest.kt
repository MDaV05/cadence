package com.cadence.music.data.downloads

import com.cadence.music.data.db.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- retry backoff and pacing tables (spec D5/D6) ----

    @Test
    fun `backoff is 15s then 60s then flat five minutes`() {
        assertEquals(15_000L, backoffMillis(1))
        assertEquals(60_000L, backoffMillis(2))
        assertEquals(300_000L, backoffMillis(3))
        assertEquals(300_000L, backoffMillis(9))
    }

    @Test
    fun `pacing is one second for telegram sources and 250ms otherwise`() {
        assertEquals(1_000L, pacingMillis("telegram"))
        assertEquals(1_000L, pacingMillis("bale"))
        assertEquals(250L, pacingMillis("plex"))
        assertEquals(250L, pacingMillis("subsonic"))
    }

    // ---- queuedMoveWrites: the pure core of LibraryRepository.moveQueued ----

    /** Queue in drain order (priority DESC, enqueuedAt ASC). */
    private fun queuedRow(name: String, priority: Int = 0, enqueuedAt: Long) = DownloadEntity(
        trackServerId = name,
        sourceId = "plex",
        status = DownloadStatus.QUEUED,
        priority = priority,
        enqueuedAt = enqueuedAt,
    )

    /** Applies the writes by row identity (upsert semantics) and re-sorts. */
    private fun apply(queued: List<DownloadEntity>, writes: List<DownloadEntity>): List<DownloadEntity> {
        val byKey = writes.associateBy { it.trackServerId to it.sourceId }
        return queued.map { byKey[it.trackServerId to it.sourceId] ?: it }
            .sortedWith(compareByDescending<DownloadEntity> { it.priority }.thenBy { it.enqueuedAt })
    }

    @Test
    fun `move swaps order keys with the neighbor when they differ`() {
        val queued = listOf(
            queuedRow("a", enqueuedAt = 100),
            queuedRow("b", enqueuedAt = 200),
            queuedRow("c", enqueuedAt = 300),
        )
        val writes = queuedMoveWrites(queued, "b", "plex", -1)!!
        val moved = apply(queued, writes)
        assertEquals(listOf("b", "a", "c"), moved.map { it.trackServerId })
    }

    @Test
    fun `move down swaps with the next neighbor`() {
        val queued = listOf(
            queuedRow("a", enqueuedAt = 100),
            queuedRow("b", enqueuedAt = 200),
            queuedRow("c", enqueuedAt = 300),
        )
        val writes = queuedMoveWrites(queued, "b", "plex", 1)!!
        val moved = apply(queued, writes)
        assertEquals(listOf("a", "c", "b"), moved.map { it.trackServerId })
    }

    @Test
    fun `priority swap moves a user tap below bulk work`() {
        val queued = listOf(
            queuedRow("tap", priority = 1, enqueuedAt = 500),
            queuedRow("bulk1", priority = 0, enqueuedAt = 100),
            queuedRow("bulk2", priority = 0, enqueuedAt = 200),
        )
        val writes = queuedMoveWrites(queued, "tap", "plex", 1)!!
        val moved = apply(queued, writes)
        assertEquals(listOf("bulk1", "tap", "bulk2"), moved.map { it.trackServerId })
        // Priorities were exchanged, not lost.
        assertEquals(0, moved.first { it.trackServerId == "tap" }.priority)
        assertEquals(1, moved.first { it.trackServerId == "bulk1" }.priority)
    }

    @Test
    fun `tied rows of one enqueue call are re-keyed so the swap lands`() {
        // One button press: every row shares priority AND enqueuedAt.
        val queued = listOf(
            queuedRow("a", enqueuedAt = 100),
            queuedRow("b", enqueuedAt = 100),
            queuedRow("c", enqueuedAt = 100),
            queuedRow("d", enqueuedAt = 100),
        )
        val writes = queuedMoveWrites(queued, "c", "plex", -1)!!
        val moved = apply(queued, writes)
        // c moved one slot up; everyone else kept their relative order.
        assertEquals(listOf("a", "c", "b", "d"), moved.map { it.trackServerId })
        // The re-key left every row a distinct enqueuedAt (no new ties).
        assertEquals(moved.size, moved.map { it.enqueuedAt }.toSet().size)
    }

    @Test
    fun `moves are no-ops at the queue edges`() {
        val queued = listOf(
            queuedRow("a", enqueuedAt = 100),
            queuedRow("b", enqueuedAt = 200),
        )
        assertNull(queuedMoveWrites(queued, "a", "plex", -1))
        assertNull(queuedMoveWrites(queued, "b", "plex", 1))
    }

    @Test
    fun `unknown row and zero delta are no-ops`() {
        val queued = listOf(queuedRow("a", enqueuedAt = 100), queuedRow("b", enqueuedAt = 200))
        assertNull(queuedMoveWrites(queued, "zz", "plex", -1))
        assertNull(queuedMoveWrites(queued, "a", "plex", 0))
    }
}
