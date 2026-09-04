package com.cadence.music.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object StreamCache {

    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context, maxBytes: Long = 2L * 1024 * 1024 * 1024): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: run {
                // One-time cleanup of the pre-cacheDir location (backed-up dead weight).
                runCatching { File(context.applicationContext.filesDir, "stream_cache").deleteRecursively() }
                SimpleCache(
                    // cacheDir (not filesDir): excluded from Auto Backup, OS may evict under pressure.
                    File(context.applicationContext.cacheDir, "stream_cache"),
                    LeastRecentlyUsedCacheEvictor(maxBytes),
                    StandaloneDatabaseProvider(context),
                ).also { instance = it }
            }
        }
}
