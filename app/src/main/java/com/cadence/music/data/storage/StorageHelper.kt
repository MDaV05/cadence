package com.cadence.music.data.storage

import android.content.Context
import java.io.File
import java.util.Locale

object StorageHelper {

    data class StorageBreakdown(
        val downloadsBytes: Long = 0L,
        val streamCacheBytes: Long = 0L,
        val telegramBytes: Long = 0L,
        val imageCacheBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = downloadsBytes + streamCacheBytes + telegramBytes + imageCacheBytes
    }

    fun calculateDirSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun computeBreakdown(context: Context): StorageBreakdown {
        val dl = calculateDirSize(File(context.filesDir, "downloads"))
        val sc = calculateDirSize(File(context.cacheDir, "stream_cache")) +
                calculateDirSize(File(context.filesDir, "stream_cache"))
        val tg = calculateDirSize(File(context.cacheDir, "tdlib_files"))
        val img = calculateDirSize(File(context.cacheDir, "metadata_images"))
        return StorageBreakdown(
            downloadsBytes = dl,
            streamCacheBytes = sc,
            telegramBytes = tg,
            imageCacheBytes = img,
        )
    }

    fun cleanCacheOlderThan(dir: File, cutoffMs: Long): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var freed = 0L
        dir.walkBottomUp().forEach { file ->
            if (file.isFile && file.lastModified() < cutoffMs) {
                val len = file.length()
                if (file.delete()) {
                    freed += len
                }
            }
        }
        return freed
    }

    fun clearDirectory(dir: File): Long {
        if (!dir.exists()) return 0L
        val size = calculateDirSize(dir)
        dir.deleteRecursively()
        dir.mkdirs()
        return size
    }

    fun formatStorageSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
