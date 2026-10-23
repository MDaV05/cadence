package com.cadence.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
