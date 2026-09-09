package com.cadence.music.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class CachesTest {
    @Test fun `gigabytes map to bytes`() {
        assertEquals(1L shl 30, cacheBytes(1, false))
        assertEquals(100L * (1L shl 30), cacheBytes(100, false))
    }
    @Test fun `out of range clamps to one gigabyte`() {
        assertEquals(1L shl 30, cacheBytes(0, false))
        assertEquals(1L shl 30, cacheBytes(-5, false))
        assertEquals(100L * (1L shl 30), cacheBytes(500, false))
    }
    @Test fun `unlimited ignores gb`() {
        assertEquals(Long.MAX_VALUE, cacheBytes(1, true))
        assertEquals(Long.MAX_VALUE, cacheBytes(0, true))
        assertEquals(Long.MAX_VALUE, cacheBytes(-5, true))
        assertEquals(Long.MAX_VALUE, cacheBytes(500, true))
    }
}
