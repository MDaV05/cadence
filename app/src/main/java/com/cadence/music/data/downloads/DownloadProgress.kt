package com.cadence.music.data.downloads

/**
 * Count-based snapshot of the download queue.
 *
 * [fraction] deliberately counts only completed tracks. A failed download is
 * not progress: the old behavior counted it as settled, so a fully failed
 * batch could display as 100% complete.
 */
data class DownloadProgress(
    val total: Int,
    val queued: Int,
    val running: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int = 0,
) {
    val visibleTotal: Int get() = (total - cancelled).coerceAtLeast(0)
    val pending: Int get() = queued + running
    val active: Boolean get() = pending > 0
    val fraction: Float get() = if (visibleTotal == 0) 0f else done.toFloat() / visibleTotal
}

/** statuses: "queued" | "running" | "done" | "failed" | "cancelled" | unknown = queued. */
fun progressOf(statuses: List<String>): DownloadProgress {
    var queued = 0
    var running = 0
    var done = 0
    var failed = 0
    var cancelled = 0
    for (s in statuses) when (s) {
        "running" -> running++
        "done" -> done++
        "failed" -> failed++
        "cancelled" -> cancelled++
        else -> queued++
    }
    return DownloadProgress(
        total = statuses.size,
        queued = queued,
        running = running,
        done = done,
        failed = failed,
        cancelled = cancelled,
    )
}
