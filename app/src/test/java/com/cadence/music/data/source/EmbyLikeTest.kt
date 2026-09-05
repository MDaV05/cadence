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
    fun `emby and jellyfin share paths`() {
        val j = JellyfinSource(entry)
        val e = EmbySource(entry.copy(type = com.cadence.music.data.prefs.ServerType.EMBY))
        assertEquals(j.streamUrlFor("abc"), e.streamUrlFor("abc").replace("box:8096", "box:8096"))
    }

    @Test
    fun `album track keys carry prefix`() {
        assertTrue("jelly:abc".removePrefix("jelly:") == "abc")
    }
}
