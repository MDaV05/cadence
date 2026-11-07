package com.cadence.music.data.downloads

import com.cadence.music.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadEstimateTest {
    private fun track(durationMs: Long, sizeBytes: Long = 0) = TrackEntity(
        sourceId = "srv",
        serverId = "1",
        title = "Song",
        path = null,
        durationMs = durationMs,
        trackNumber = 1,
        sizeBytes = sizeBytes,
    )

    @Test fun `raw download uses the advertised size`() {
        assertEquals(8_000_000L, estimateDownloadBytes(track(240_000, 8_000_000), "raw", 128))
    }

    @Test fun `raw without a size falls back to duration estimate`() {
        // 240 s at the conservative 256 kbps fallback = 7.68 MB.
        assertEquals(7_680_000L, estimateDownloadBytes(track(240_000), "raw", 128))
    }

    @Test fun `transcode uses the chosen bitrate`() {
        // 240 s at 128 kbps = 3.84 MB, ignoring the source size.
        assertEquals(3_840_000L, estimateDownloadBytes(track(240_000, 8_000_000), "mp3", 128))
    }

    @Test fun `bitrate is clamped to the supported range`() {
        assertEquals(
            estimateDownloadBytes(track(60_000), "mp3", 64),
            estimateDownloadBytes(track(60_000), "mp3", 1),
        )
        assertEquals(
            estimateDownloadBytes(track(60_000), "mp3", 320),
            estimateDownloadBytes(track(60_000), "mp3", 9_999),
        )
    }

    @Test fun `unknown duration produces no forecast`() {
        assertEquals(0L, estimateDownloadBytes(track(0, 0), "mp3", 128))
        assertEquals(0L, estimateDownloadBytes(track(0, 0), "raw", 128))
    }

    @Test fun `queue estimate sums every track`() {
        val tracks = listOf(
            track(60_000, 1_000_000),
            track(120_000, 2_000_000),
        )
        assertEquals(3_000_000L, estimateQueueBytes(tracks, "raw", 128))
    }
}
