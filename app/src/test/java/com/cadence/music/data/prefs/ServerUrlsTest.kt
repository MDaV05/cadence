package com.cadence.music.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {

    @Test
    fun `scheme-less url gets http prefix`() {
        assertEquals("http://192.168.1.106:4533", sanitizeServerUrl("192.168.1.106:4533"))
    }

    @Test
    fun `https url preserved as-is`() {
        assertEquals("https://wan.example.com/sub", sanitizeServerUrl("  https://wan.example.com/sub  "))
    }

    @Test
    fun `file scheme rejected`() {
        assertNull(sanitizeServerUrl("file://x"))
        assertNull(sanitizeServerUrl("file:///sdcard/music.db"))
    }

    @Test
    fun `content scheme rejected`() {
        assertNull(sanitizeServerUrl("content://media/external/audio/media/42"))
    }

    @Test
    fun `scheme case-insensitive`() {
        assertEquals("HTTP://Host", sanitizeServerUrl("HTTP://Host"))
        assertEquals("HTTPS://Host", sanitizeServerUrl("HTTPS://Host"))
    }

    @Test
    fun `garbage rejected`() {
        assertNull(sanitizeServerUrl(""))
        assertNull(sanitizeServerUrl("   "))
        assertNull(sanitizeServerUrl("://x"))
        assertNull(sanitizeServerUrl("not a server url"))
        assertNull(sanitizeServerUrl("javascript:alert(1)://x"))
    }

    @Test
    fun `url with path and port ok`() {
        assertEquals("http://box:8096/jellyfin", sanitizeServerUrl("http://box:8096/jellyfin"))
    }

    @Test
    fun `adopting a cleartext url from an active https url is refused`() {
        assertEquals(false, canAdoptActiveUrl("https://wan.example.com", "http://lan.local:4533"))
        assertEquals(false, canAdoptActiveUrl("HTTPS://wan.example.com/rest", "http://lan.local"))
    }

    @Test
    fun `same or upgraded scheme adoption is allowed`() {
        assertEquals(true, canAdoptActiveUrl("http://a", "http://b"))
        assertEquals(true, canAdoptActiveUrl("https://a", "https://b"))
        assertEquals(true, canAdoptActiveUrl(null, "http://b"))
        assertEquals(true, canAdoptActiveUrl("", "https://b"))
    }

    @Test
    fun `telegram entries bypass the url sanitizer`() {
        // Their `url` field holds chat targets, not URLs — "me" and "12345,67890"
        // must round-trip unchanged (regression: sanitizing them blanked/deleted
        // configured Telegram libraries).
        val tg = ServerEntry(id = "t1", type = ServerType.TELEGRAM, url = "12345,67890", user = "me")
        assertEquals(tg, sanitizeEntry(tg))
        val me = tg.copy(url = "me")
        assertEquals(me, sanitizeEntry(me))
    }

    @Test
    fun `non-telegram entries filter by url scheme`() {
        val sub = ServerEntry(id = "s1", type = ServerType.SUBSONIC, url = "192.168.1.5:4533", user = "u")
        assertEquals("http://192.168.1.5:4533", sanitizeEntry(sub)?.url)
        val bad = sub.copy(url = "file:///sdcard/x", secondaryUrl = "content://y")
        assertNull(sanitizeEntry(bad))
        val badSecondary = sub.copy(secondaryUrl = "file:///sdcard/x")
        assertEquals(null, sanitizeEntry(badSecondary)?.secondaryUrl)
        assertEquals("http://192.168.1.5:4533", sanitizeEntry(badSecondary)?.url)
    }
}
