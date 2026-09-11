package com.cadence.music.data.downloads

/**
 * Count-based snapshot of the download queue. The worker does not report
 * per-file byte progress upward, so the UI bar advances per settled track.
 */
data class DownloadProgress(val total: Int, val running: Int, val done: Int, val failed: Int) {
    /** Failed counts as settled: the queue is done with that item either way. */
    val fraction: Float get() = if (total == 0) 0f else (done + failed).toFloat() / total
    val active: Boolean get() = running > 0
}

/** statuses: "running" | "done" | "failed" | anything else = queued (counted in total only). */
fun progressOf(statuses: List<String>): DownloadProgress {
    var running = 0
    var done = 0
    var failed = 0
    for (s in statuses) when (s) {
        "running" -> running++
        "done" -> done++
        "failed" -> failed++
    }
    return DownloadProgress(total = statuses.size, running = running, done = done, failed = failed)
}
