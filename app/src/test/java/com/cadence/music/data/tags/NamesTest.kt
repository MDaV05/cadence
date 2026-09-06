package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NamesTest {

    @Test
    fun `collab names stay whole`() {
        // No heuristic can tell "A and B" (two artists) from "Simon & Garfunkel"
        // (one) — real names must survive, so nothing is ever split.
        assertEquals("Future and Drake", primaryArtist("Future and Drake"))
        assertEquals("Beta & Friends", primaryArtist("Beta & Friends"))
        assertEquals("Earth, Wind & Fire", primaryArtist("Earth, Wind & Fire"))
        assertEquals("21 Savage/Doja Cat", primaryArtist("21 Savage/Doja Cat"))
    }

    @Test
    fun `feat clause stripped`() {
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
