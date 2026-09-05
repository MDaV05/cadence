package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NamesTest {

    @Test
    fun `first artist wins on and`() {
        assertEquals("Future", primaryArtist("Future and Drake"))
    }

    @Test
    fun `slash splits without spaces`() {
        assertEquals("21 Savage", primaryArtist("21 Savage/Doja Cat"))
    }

    @Test
    fun `feat clause stripped before split`() {
        assertEquals("A", primaryArtist("A feat. B & C"))
    }

    @Test
    fun `trailing parens trimmed`() {
        assertEquals("Kendrick", primaryArtist("Kendrick (Deluxe)"))
    }

    @Test
    fun `blank passes through`() {
        assertEquals("", primaryArtist(""))
        assertEquals("", primaryArtist("   "))
    }

    @Test
    fun `stylized slash name stays whole`() {
        assertEquals("AC/DC", primaryArtist("AC/DC"))
    }

    @Test
    fun `album key ignores case articles and edition tags`() {
        assertEquals(
            albumNormKey("A Great Chaos", "Ken Carson"),
            albumNormKey("Great Chaos (Deluxe)", "ken carson"),
        )
    }

    @Test
    fun `album key differs across artists`() {
        assertNotEquals(
            albumNormKey("After Hours", "The Weeknd"),
            albumNormKey("After Hours", "Adele"),
        )
    }
}
