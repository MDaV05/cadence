package com.cadence.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFadeHelperTest {

    @Test
    fun calculateVolumeRampAboveDurationReturnsFullVolume() {
        val vol = AudioFadeHelper.calculateVolumeRamp(remainingMs = 20_000L, fadeDurationMs = 15_000L)
        assertEquals(1.0f, vol, 0.001f)
    }

    @Test
    fun calculateVolumeRampZeroOrNegativeReturnsMute() {
        assertEquals(0.0f, AudioFadeHelper.calculateVolumeRamp(remainingMs = 0L, fadeDurationMs = 15_000L), 0.001f)
        assertEquals(0.0f, AudioFadeHelper.calculateVolumeRamp(remainingMs = -100L, fadeDurationMs = 15_000L), 0.001f)
    }

    @Test
    fun calculateVolumeRampMidwayInterpolatesLinearly() {
        val vol = AudioFadeHelper.calculateVolumeRamp(remainingMs = 7_500L, fadeDurationMs = 15_000L)
        assertEquals(0.5f, vol, 0.001f)
    }
}
