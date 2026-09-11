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
}
