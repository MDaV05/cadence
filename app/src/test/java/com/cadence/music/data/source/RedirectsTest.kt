package com.cadence.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectsTest {

    @Test
    fun `port differences make different origins`() {
        assertEquals(false, sameOrigin("http://host/x", "http://host:8080/x"))
        assertEquals(false, sameOrigin("http://host:8080/x", "http://host/x"))
        // Absent port equals the scheme default.
        assertEquals(true, sameOrigin("http://host/x", "http://host:80/y"))
        assertEquals(true, sameOrigin("https://host/x", "https://host:443/y"))
    }

    @Test
    fun `host compare is case-insensitive`() {
        assertEquals(true, sameOrigin("http://Host.EXAMPLE/x", "http://host.example/y"))
    }

    @Test
    fun `path and query are ignored`() {
        assertEquals(true, sameOrigin("http://h/a/b?q=1", "http://h/other"))
    }

    @Test
    fun `scheme differences make different origins`() {
        assertEquals(false, sameOrigin("http://h/x", "https://h/x"))
    }

    @Test
    fun `unparseable urls are not same origin`() {
        assertEquals(false, sameOrigin("not a url", "http://h/x"))
        assertEquals(false, sameOrigin("http://h/x", "still not"))
    }
}
