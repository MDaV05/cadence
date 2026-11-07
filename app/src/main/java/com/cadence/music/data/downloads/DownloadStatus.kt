package com.cadence.music.data.downloads

/** Persisted states for a download row. The row exists from the moment it is queued. */
object DownloadStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}
