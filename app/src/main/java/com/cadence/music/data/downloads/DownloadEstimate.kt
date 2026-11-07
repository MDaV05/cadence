package com.cadence.music.data.downloads

import com.cadence.music.data.db.TrackEntity

/**
 * Best-effort size forecast used before a server reports Content-Length.
 *
 * Original downloads use the size the server advertised during sync. Transcoded
 * downloads are estimated from duration and the chosen bitrate; an unknown
 * original bitrate falls back to [DEFAULT_RAW_KBPS] so the number is honest
 * about being an estimate rather than pretending to be exact.
 */
fun estimateDownloadBytes(track: TrackEntity, format: String, bitrateKbps: Int): Long =
    estimateDownloadBytes(track.durationMs, track.sizeBytes, format, bitrateKbps)

fun estimateDownloadBytes(
    durationMs: Long,
    sizeBytes: Long,
    format: String,
    bitrateKbps: Int,
): Long {
    if (format == "raw" && sizeBytes > 0) return sizeBytes
    if (durationMs <= 0) return 0
    val kbps = if (format == "raw") DEFAULT_RAW_KBPS else bitrateKbps.coerceIn(64, 320)
    return durationMs * kbps / 8
}

fun estimateQueueBytes(tracks: List<TrackEntity>, format: String, bitrateKbps: Int): Long =
    tracks.sumOf { estimateDownloadBytes(it, format, bitrateKbps) }

/** Middle-of-the-road bitrate for libraries that do not expose a file size. */
const val DEFAULT_RAW_KBPS = 256
