package com.cadence.music.data.downloads

/**
 * Count-based snapshot of the download queue.
 *
 * [fraction] deliberately counts only completed tracks. A failed download is
 * not progress: the old behavior counted it as settled, so a fully failed
 * batch could display as 100% complete. Cancelling deletes the row outright,
 * so there is no cancelled state to model here.
 */
data class DownloadProgress(
    val total: Int,
    val queued: Int,
    val running: Int,
    val done: Int,
    val failed: Int,
) {
    val pending: Int get() = queued + running
    val active: Boolean get() = pending > 0
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** statuses: "queued" | "running" | "done" | "failed" | unknown = queued. */
fun progressOf(statuses: List<String>): DownloadProgress {
    var queued = 0
    var running = 0
    var done = 0
    var failed = 0
    for (s in statuses) when (s) {
        "running" -> running++
        "done" -> done++
        "failed" -> failed++
        else -> queued++
    }
    return DownloadProgress(
        total = statuses.size,
        queued = queued,
        running = running,
        done = done,
        failed = failed,
    )
}
