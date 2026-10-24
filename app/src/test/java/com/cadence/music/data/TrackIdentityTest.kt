package com.cadence.music.data

import com.cadence.music.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {

    @Test
    fun `case and whitespace do not break the match`() {
        assertEquals(
            trackFingerprint("  Song  Title ", "Artist", 180_000),
            trackFingerprint("song title", "artist", 180_999),
        )
    }

    @Test
    fun `featured artists fold to the primary artist`() {
        assertEquals(
            trackFingerprint("Song", "Artist feat. Guest", 180_000),
            trackFingerprint("Song", "Artist", 180_000),
        )
    }

    @Test
    fun `a different duration is a different track`() {
        assertNotEquals(
            trackFingerprint("Song", "Artist", 180_000),
            trackFingerprint("Song", "Artist", 190_000),
        )
    }

    @Test
    fun `a different title is a different track`() {
        assertNotEquals(
            trackFingerprint("Song", "Artist", 180_000),
            trackFingerprint("Other", "Artist", 180_000),
        )
    }

    @Test
    fun `isSameTrack identifies same track across local and server`() {
        val local = TrackEntity(
            id = 1,
            sourceId = "local",
            serverId = "local:101",
            title = "Blinding Lights",
            artistName = "The Weeknd",
            albumName = "After Hours",
            albumNorm = "after hours::the weeknd",
            path = "content://media/101",
            durationMs = 200_000,
            trackNumber = 0,
        )
        val server = TrackEntity(
            id = 2,
            sourceId = "subsonic",
            serverId = "srv:999",
            title = "  blinding lights  ",
            artistName = "The Weeknd feat. Guest",
            albumName = "After Hours (Deluxe)",
            albumNorm = "after hours::the weeknd",
            path = null,
            durationMs = 200_500,
            trackNumber = 3,
        )
        assertTrue(isSameTrack(local, server))
    }

    @Test
    fun `isSameTrack distinguishes different tracks with different track numbers in same album`() {
        val t1 = TrackEntity(
            id = 1,
            sourceId = "subsonic",
            serverId = "srv:1",
            title = "Intro",
            artistName = "Artist",
            albumNorm = "album::artist",
            path = null,
            durationMs = 60_000,
            trackNumber = 1,
        )
        val t2 = TrackEntity(
            id = 2,
            sourceId = "subsonic",
            serverId = "srv:2",
            title = "Intro",
            artistName = "Artist",
            albumNorm = "album::artist",
            path = null,
            durationMs = 60_000,
            trackNumber = 2,
        )
        assertFalse(isSameTrack(t1, t2))
    }

    @Test
    fun `isSameTrack matches tracks without album by artist title and duration`() {
        val local = TrackEntity(
            id = 1,
            sourceId = "local",
            serverId = "local:1",
            title = "Single Song",
            artistName = "Solo Artist",
            albumNorm = "",
            path = "content://1",
            durationMs = 180_000,
            trackNumber = 0,
        )
        val server = TrackEntity(
            id = 2,
            sourceId = "subsonic",
            serverId = "srv:2",
            title = "Single Song",
            artistName = "Solo Artist",
            albumNorm = "",
            path = null,
            durationMs = 181_000,
            trackNumber = 0,
        )
        assertTrue(isSameTrack(local, server))
    }

    @Test
    fun `mergeTrackEntities prefers local source for playback and merges metadata`() {
        val server = TrackEntity(
            id = 10,
            sourceId = "subsonic",
            serverId = "srv:track1",
            title = "Starboy",
            artistName = "The Weeknd",
            albumName = "Starboy",
            albumNorm = "starboy::the weeknd",
            albumKey = "srv:album1",
            path = null,
            durationMs = 230_000,
            trackNumber = 1,
            starred = true,
            playCount = 12,
            lastPlayed = 5000L,
        )
        val local = TrackEntity(
            id = 20,
            sourceId = "local",
            serverId = "local:55",
            title = "Starboy",
            artistName = "The Weeknd",
            albumName = "Starboy",
            albumNorm = "starboy::the weeknd",
            albumMediaId = 444L,
            path = "content://media/external/audio/55",
            durationMs = 230_100,
            trackNumber = 0,
            starred = false,
            playCount = 5,
            lastPlayed = 9000L,
        )

        val merged = mergeTrackEntities(server, local)
        assertEquals("local", merged.sourceId)
        assertEquals("content://media/external/audio/55", merged.path)
        assertEquals(1, merged.trackNumber) // Enriched from server
        assertEquals("srv:album1", merged.albumKey) // Enriched from server
        assertEquals(444L, merged.albumMediaId) // Kept from local
        assertTrue(merged.starred) // Preserved star from server
        assertEquals(12, merged.playCount) // Max play count
        assertEquals(9000L, merged.lastPlayed) // Most recent lastPlayed
    }

    @Test
    fun `mergeTrackList deduplicates duplicate pairs and keeps unique tracks`() {
        val t1Local = TrackEntity(
            id = 1,
            sourceId = "local",
            serverId = "loc:1",
            title = "Song 1",
            artistName = "Artist A",
            albumNorm = "album::artist a",
            path = "content://1",
            durationMs = 180_000,
            trackNumber = 1,
        )
        val t1Server = TrackEntity(
            id = 2,
            sourceId = "subsonic",
            serverId = "srv:1",
            title = "Song 1",
            artistName = "Artist A",
            albumNorm = "album::artist a",
            path = null,
            durationMs = 180_000,
            trackNumber = 1,
        )
        val t2Server = TrackEntity(
            id = 3,
            sourceId = "subsonic",
            serverId = "srv:2",
            title = "Song 2",
            artistName = "Artist A",
            albumNorm = "album::artist a",
            path = null,
            durationMs = 200_000,
            trackNumber = 2,
        )

        val merged = mergeTrackList(listOf(t1Server, t1Local, t2Server))
        assertEquals(2, merged.size)
        assertEquals("Song 1", merged[0].title)
        assertEquals("local", merged[0].sourceId) // Local preferred for playback
        assertEquals("Song 2", merged[1].title)
        assertEquals("subsonic", merged[1].sourceId)
    }
}
