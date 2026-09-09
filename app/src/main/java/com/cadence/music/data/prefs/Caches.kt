package com.cadence.music.data.prefs

/** Bytes for a user-chosen cache limit: unlimited = effectively no cap. */
fun cacheBytes(gb: Int, unlimited: Boolean): Long =
    if (unlimited) Long.MAX_VALUE else gb.coerceIn(1, 100) * 1024L * 1024 * 1024
