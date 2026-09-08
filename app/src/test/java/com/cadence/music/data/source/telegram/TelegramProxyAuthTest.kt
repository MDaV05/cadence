package com.cadence.music.data.source.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProxyAuthTest {

    @Test
    fun `auth matches exact token only`() {
        assertTrue(TelegramStreamProxy.authMatches("abc123", "abc123"))
        assertFalse(TelegramStreamProxy.authMatches("abc123", "abc124"))
        assertFalse(TelegramStreamProxy.authMatches("abc123", "abc1234"))
        assertFalse(TelegramStreamProxy.authMatches("", "abc123"))
        assertFalse(TelegramStreamProxy.authMatches(null, "abc123"))
    }

    @Test
    fun `generated tokens are unique and url safe`() {
        val a = TelegramStreamProxy.generateAuthToken()
        val b = TelegramStreamProxy.generateAuthToken()
        assertEquals(43, a.length) // 32 bytes -> base64url without padding
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(a == b)
        assertTrue(TelegramStreamProxy.authMatches(a, a))
        assertFalse(TelegramStreamProxy.authMatches(b, a))
    }
}
