package com.cadence.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyLikeTest {

    private val entry = com.cadence.music.data.prefs.ServerEntry(
        id = "e1", type = com.cadence.music.data.prefs.ServerType.JELLYFIN,
        url = "https://box:8096/", user = "u", token = "tok", userId = "uid",
    )

    @Test
    fun `stream url carries api key`() {
        val s = JellyfinSource(entry)
        assertEquals("https://box:8096/Audio/abc/stream?api_key=tok", s.streamUrlFor("abc"))
    }

    @Test
    fun `cover art url`() {
        val s = JellyfinSource(entry)
        assertEquals("https://box:8096/Items/abc/Images/Primary?api_key=tok", s.coverArtUrl("jelly:abc"))
    }

    @Test
    fun `auth header shape`() {
        val h = EmbyLikeAuthHeader(client = "Cadence", version = "0.2.0", deviceId = "d1", token = null)
        assertTrue(h.startsWith("MediaBrowser "))
        assertTrue(h.contains("Client=\"Cadence\""))
    }

    @Test
    fun `downloadUrl strips own prefix`() {
        val j = JellyfinSource(entry)
        assertEquals("https://box:8096/Items/abc/Download?api_key=tok", j.downloadUrl("jelly:abc"))
        val e = EmbySource(entry.copy(type = com.cadence.music.data.prefs.ServerType.EMBY))
        assertEquals("https://box:8096/Items/abc/Download?api_key=tok", e.downloadUrl("emby:abc"))
    }

    @Test
    fun `embedded stream url wins over derived`() = kotlinx.coroutines.runBlocking {
        val s = JellyfinSource(entry)
        val t = Track(
            key = "jelly:abc", sourceId = "jellyfin", title = "T", artist = "A",
            album = "B", durationMs = 1, localPath = null, streamUrl = "https://cdn/x",
        )
        assertEquals("https://cdn/x", s.streamUrl(t))
    }
}
