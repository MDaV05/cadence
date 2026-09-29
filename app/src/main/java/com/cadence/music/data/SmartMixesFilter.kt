package com.cadence.music.data

import com.cadence.music.data.db.TrackEntity

object SmartMixesFilter {

    fun filterOnRepeat(tracks: List<TrackEntity>, minPlays: Int = 2, limit: Int = 30): List<TrackEntity> {
        return tracks
            .filter { it.playCount >= minPlays }
            .sortedWith(compareByDescending<TrackEntity> { it.playCount }.thenByDescending { it.lastPlayed ?: 0L })
            .take(limit)
    }

    fun filterForgottenGems(tracks: List<TrackEntity>, cutoffMs: Long, minPlays: Int = 2, limit: Int = 30): List<TrackEntity> {
        return tracks
            .filter { it.playCount >= minPlays && it.lastPlayed != null && it.lastPlayed < cutoffMs }
            .sortedWith(compareByDescending<TrackEntity> { it.playCount }.thenBy { it.lastPlayed ?: 0L })
            .take(limit)
    }

    fun filterDeepCuts(tracks: List<TrackEntity>, limit: Int = 30): List<TrackEntity> {
        return tracks
            .filter { it.playCount == 0 }
            .take(limit)
    }
}
