package com.cadence.music.data.source.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramProxyParserTest {

    @Test
    fun `parses mtproto tg link`() {
        val link = "tg://proxy?server=198.51.100.1&port=8443&secret=ee1234567890abcdef1234567890abcdef"
        val parsed = TelegramManager.parseProxyUrl(link)
        assertNotNull(parsed)
        assertEquals("MTPROTO", parsed!!.type)
        assertEquals("198.51.100.1", parsed.server)
        assertEquals(8443, parsed.port)
        assertEquals("ee1234567890abcdef1234567890abcdef", parsed.secret)
    }

    @Test
    fun `parses mtproto https t me link`() {
        val link = "https://t.me/proxy?server=proxy.example.com&port=443&secret=dd001122"
        val parsed = TelegramManager.parseProxyUrl(link)
        assertNotNull(parsed)
        assertEquals("MTPROTO", parsed!!.type)
        assertEquals("proxy.example.com", parsed.server)
        assertEquals(443, parsed.port)
        assertEquals("dd001122", parsed.secret)
    }

    @Test
    fun `parses socks5 link`() {
        val link = "tg://socks?server=127.0.0.1&port=10808&user=myuser&pass=mypass"
        val parsed = TelegramManager.parseProxyUrl(link)
        assertNotNull(parsed)
        assertEquals("SOCKS5", parsed!!.type)
        assertEquals("127.0.0.1", parsed.server)
        assertEquals(10808, parsed.port)
        assertEquals("myuser", parsed.username)
        assertEquals("mypass", parsed.password)
    }

    @Test
    fun `returns null on invalid link`() {
        assertNull(TelegramManager.parseProxyUrl(""))
        assertNull(TelegramManager.parseProxyUrl("random string"))
        assertNull(TelegramManager.parseProxyUrl("https://google.com"))
    }
}
