package com.cadence.music.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    @Test fun `empty list is zero progress`() {
        val p = progressOf(emptyList())
        assertEquals(DownloadProgress(0, 0, 0, 0, 0), p)
        assertEquals(0f, p.fraction, 0f)
        assertFalse(p.active)
    }

    @Test fun `counts each known status`() {
        val p = progressOf(listOf("queued", "running", "running", "done", "failed"))
        assertEquals(5, p.total)
        assertEquals(1, p.queued)
        assertEquals(2, p.running)
        assertEquals(1, p.done)
        assertEquals(1, p.failed)
    }

    @Test fun `unknown statuses count as queued`() {
        val p = progressOf(listOf("queued", "pending", "done"))
        assertEquals(3, p.total)
        assertEquals(2, p.queued)
        assertEquals(1, p.done)
        assertEquals(0, p.failed)
        assertTrue(p.active)
    }

    @Test fun `failed downloads are not progress`() {
        // 2 done out of 6 visible: the old behavior counted the failure as
        // settled and displayed 50% for work that did not land.
        val p = progressOf(listOf("done", "done", "failed", "running", "queued", "queued"))
        assertEquals(2f / 6f, p.fraction, 0.0001f)
        assertEquals(1, p.failed)
        assertEquals(3, p.pending)
        assertTrue(p.active)
    }

    @Test fun `all done is full fraction`() {
        val p = progressOf(listOf("done", "done", "done"))
        assertEquals(1f, p.fraction, 0f)
        assertFalse(p.active)
    }

    @Test fun `queued-only queue is active but has no fraction`() {
        val p = progressOf(listOf("queued", "queued"))
        assertEquals(0f, p.fraction, 0f)
        assertEquals(2, p.pending)
        assertTrue(p.active)
    }
}
