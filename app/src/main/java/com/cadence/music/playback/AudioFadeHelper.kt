package com.cadence.music.playback

object AudioFadeHelper {

    fun calculateVolumeRamp(remainingMs: Long, fadeDurationMs: Long = 15_000L): Float {
        if (remainingMs >= fadeDurationMs) return 1.0f
        if (remainingMs <= 0L || fadeDurationMs <= 0L) return 0.0f
        return (remainingMs.toFloat() / fadeDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
    }
}
