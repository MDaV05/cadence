package com.cadence.music.data

import com.cadence.music.data.prefs.LibraryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModeTest {

    @Test
    fun `local only maps to local source`() {
        assertEquals(setOf("local"), sourcesFor(LibraryMode.LOCAL_ONLY))
    }

    @Test
    fun `api only maps to all server sources`() {
        assertEquals(setOf("subsonic", "jellyfin", "emby", "plex"), sourcesFor(LibraryMode.API_ONLY))
    }

    @Test
    fun `hybrid maps to null unfiltered`() {
        assertNull(sourcesFor(LibraryMode.HYBRID))
    }

    @Test
    fun `downloaded means non-local with local file`() {
        assertTrue(isDownloaded("subsonic", "file:///data/a.audio"))
        assertTrue(isDownloaded("jellyfin", "file:///data/b.audio"))
        assertTrue(isDownloaded("emby", "file:///data/c.audio"))
        assertTrue(isDownloaded("plex", "file:///data/d.audio"))
        assertFalse(isDownloaded("subsonic", null))
        assertFalse(isDownloaded("subsonic", "https://server/stream?id=1"))
        assertFalse(isDownloaded("jellyfin", null))
        assertFalse(isDownloaded("local", "content://media/1"))
        assertFalse(isDownloaded("local", "file:///data/e.audio"))
    }

    @Test
    fun `local only includes local plus downloaded`() {
        val m = LibraryMode.LOCAL_ONLY
        assertTrue(isIncluded("local", "content://media/1", m))
        assertTrue(isIncluded("subsonic", "file:///data/a.audio", m))
        assertTrue(isIncluded("jellyfin", "file:///data/b.audio", m))
        assertTrue(isIncluded("plex", "file:///data/d.audio", m))
        assertFalse(isIncluded("subsonic", null, m))
        assertFalse(isIncluded("jellyfin", null, m))
    }

    @Test
    fun `api only includes all server tracks`() {
        val m = LibraryMode.API_ONLY
        assertTrue(isIncluded("subsonic", null, m))
        assertTrue(isIncluded("subsonic", "file:///data/a.audio", m))
        assertTrue(isIncluded("jellyfin", null, m))
        assertTrue(isIncluded("emby", null, m))
        assertTrue(isIncluded("plex", null, m))
        assertFalse(isIncluded("local", "content://media/1", m))
    }

    @Test
    fun `hybrid includes everything`() {
        val m = LibraryMode.HYBRID
        assertTrue(isIncluded("local", "content://media/1", m))
        assertTrue(isIncluded("subsonic", null, m))
        assertTrue(isIncluded("jellyfin", null, m))
        assertTrue(isIncluded("plex", null, m))
    }
}
