package com.cadence.music.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTest {
    @Test fun `empty list is zero progress`() {
        val p = progressOf(emptyList())
        assertEquals(DownloadProgress(0, 0, 0, 0), p)
        assertEquals(0f, p.fraction, 0f)
        assertEquals(false, p.active)
    }

    @Test fun `counts each known status`() {
        val p = progressOf(listOf("running", "running", "done", "failed"))
        assertEquals(4, p.total)
        assertEquals(2, p.running)
        assertEquals(1, p.done)
        assertEquals(1, p.failed)
        assertEquals(true, p.active)
    }

    @Test fun `unknown statuses count in total as queued`() {
        val p = progressOf(listOf("queued", "pending", "done"))
        assertEquals(3, p.total)
        assertEquals(0, p.running)
        assertEquals(1, p.done)
        assertEquals(0, p.failed)
        assertEquals(false, p.active)
    }

    @Test fun `fraction counts failed as settled`() {
        // 2 done + 1 failed of 6 settled = 0.5
        val p = progressOf(listOf("done", "done", "failed", "running", "queued", "queued"))
        assertEquals(0.5f, p.fraction, 0.0001f)
    }

    @Test fun `all done is full fraction`() {
        val p = progressOf(listOf("done", "done", "done"))
        assertEquals(1f, p.fraction, 0f)
        assertEquals(false, p.active)
    }
}
