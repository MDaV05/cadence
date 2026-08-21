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
            instance ?: SimpleCache(
                File(context.filesDir, "stream_cache"),
                LeastRecentlyUsedCacheEvictor(maxBytes),
                StandaloneDatabaseProvider(context),
            ).also { instance = it }
        }
}
