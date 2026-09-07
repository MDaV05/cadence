package com.cadence.music.data.source

import com.cadence.music.data.prefs.LibraryMode
import com.cadence.music.data.isDownloaded
import com.cadence.music.data.isIncluded
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.prefs.ServerType
import com.cadence.music.data.sourcesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSourceTest {

    private val entry = ServerEntry(
        id = "tg1",
        type = ServerType.TELEGRAM,
        url = "me",
        user = "My Cloud Music",
        userId = "12345678",
        token = null,
        active = true,
    )

    @Test
    fun `telegram server entry round-trips through json`() {
        val json = entry.toJson()
        val restored = ServerEntry.fromJson(json)
        assertEquals(entry.id, restored.id)
        assertEquals(ServerType.TELEGRAM, restored.type)
        assertEquals("me", restored.url)
        assertEquals("My Cloud Music", restored.user)
        assertEquals("12345678", restored.userId)
        assertTrue(restored.active)
    }

    @Test
    fun `telegram source is included in api only library mode`() {
        val sources = sourcesFor(LibraryMode.API_ONLY)
        assertNotNull(sources)
        assertTrue(sources!!.contains("telegram"))
    }

    @Test
    fun `telegram tracks obey library mode inclusion predicates`() {
        val trackKey = "tg:remote_abc_123"
        // Not downloaded -> excluded in local-only, included in api-only
        assertFalse(isIncluded("telegram", null, LibraryMode.LOCAL_ONLY))
        assertTrue(isIncluded("telegram", null, LibraryMode.API_ONLY))
        assertTrue(isIncluded("telegram", null, LibraryMode.HYBRID))

        // Downloaded to local file -> included in all modes
        val localPath = "file:///data/user/0/com.cadence.music/files/downloads/tg_123.audio"
        assertTrue(isDownloaded("telegram", localPath))
        assertTrue(isIncluded("telegram", localPath, LibraryMode.LOCAL_ONLY))
        assertTrue(isIncluded("telegram", localPath, LibraryMode.API_ONLY))
        assertTrue(isIncluded("telegram", localPath, LibraryMode.HYBRID))
    }

    @Test
    fun `telegram track model formats properly`() {
        val track = Track(
            key = "tg:AQAD...",
            sourceId = "telegram",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            albumKey = "tg:chat:12345678",
            durationMs = 354000,
            localPath = null,
            streamUrl = "http://127.0.0.1:8080/stream?remoteId=AQAD...",
        )
        assertEquals("telegram", track.sourceId)
        assertTrue(track.key.startsWith("tg:"))
        assertEquals("Queen", track.artist)
        assertEquals("Bohemian Rhapsody", track.title)
    }

    @Test
    fun `parseTelegramChatIds parses single me and numeric ids`() {
        val meOnly = com.cadence.music.data.source.telegram.parseTelegramChatIds("me", 98765L)
        assertEquals(listOf(98765L), meOnly)

        val savedOnly = com.cadence.music.data.source.telegram.parseTelegramChatIds("saved", 98765L)
        assertEquals(listOf(98765L), savedOnly)

        val numericOnly = com.cadence.music.data.source.telegram.parseTelegramChatIds("-1001234567890", 98765L)
        assertEquals(listOf(-1001234567890L), numericOnly)
    }

    @Test
    fun `parseTelegramChatIds parses multiple comma and semicolon separated ids`() {
        val multiple = com.cadence.music.data.source.telegram.parseTelegramChatIds("me, -1001234567890; 11223344", 98765L)
        assertEquals(listOf(98765L, -1001234567890L, 11223344L), multiple)

        val duplicates = com.cadence.music.data.source.telegram.parseTelegramChatIds("112233, 112233, me, saved", 98765L)
        assertEquals(listOf(112233L, 98765L), duplicates)
    }
}
