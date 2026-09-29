package com.cadence.music.data

import com.cadence.music.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartMixesTest {

    private fun makeTrack(id: Long, plays: Int, lastPlayed: Long?): TrackEntity =
        TrackEntity(
            id = id,
            sourceId = "local",
            serverId = "srv-$id",
            title = "Track $id",
            playCount = plays,
            lastPlayed = lastPlayed,
            path = "/music/$id.mp3",
            durationMs = 180_000L,
            trackNumber = 1,
        )

    @Test
    fun filterOnRepeatRequiresMinPlaysAndSortsByPlayCountDesc() {
        val t1 = makeTrack(1, 1, 1000L)
        val t2 = makeTrack(2, 5, 2000L)
        val t3 = makeTrack(3, 10, 1500L)
        val t4 = makeTrack(4, 0, null)

        val result = SmartMixesFilter.filterOnRepeat(listOf(t1, t2, t3, t4))

        assertEquals(listOf(3L, 2L), result.map { it.id })
    }

    @Test
    fun filterForgottenGemsRequiresMinPlaysAndPlayedBeforeCutoff() {
        val now = 100_000L
        val cutoff = 50_000L
        val oldFavorite = makeTrack(1, 8, 20_000L)
        val recentFavorite = makeTrack(2, 8, 80_000L)
        val unplayed = makeTrack(3, 0, null)

        val result = SmartMixesFilter.filterForgottenGems(listOf(oldFavorite, recentFavorite, unplayed), cutoffMs = cutoff)

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun filterDeepCutsReturnsOnlyUnplayedTracks() {
        val t1 = makeTrack(1, 0, null)
        val t2 = makeTrack(2, 3, 5000L)
        val t3 = makeTrack(3, 0, null)

        val result = SmartMixesFilter.filterDeepCuts(listOf(t1, t2, t3))

        assertEquals(listOf(1L, 3L), result.map { it.id })
    }
}
