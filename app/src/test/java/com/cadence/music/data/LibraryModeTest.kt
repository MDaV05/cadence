package com.cadence.music.data

import com.cadence.music.data.prefs.LibraryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryModeTest {

    @Test
    fun `local only maps to local source`() {
        assertEquals(setOf("local"), sourcesFor(LibraryMode.LOCAL_ONLY))
    }

    @Test
    fun `api only maps to subsonic source`() {
        assertEquals(setOf("subsonic"), sourcesFor(LibraryMode.API_ONLY))
    }

    @Test
    fun `hybrid maps to null unfiltered`() {
        assertNull(sourcesFor(LibraryMode.HYBRID))
    }
}
