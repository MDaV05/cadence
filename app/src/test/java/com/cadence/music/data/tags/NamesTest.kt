package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NamesTest {

    @Test
    fun `known ensembles stay whole`() {
        assertEquals("Earth, Wind & Fire", primaryArtist("Earth, Wind & Fire"))
        assertEquals("Earth, Wind & Fire", primaryArtist("earth, wind & fire"))
        assertEquals("Simon & Garfunkel", primaryArtist("Simon & Garfunkel"))
        assertEquals("Simon & Garfunkel", primaryArtist("Simon and Garfunkel"))
        assertEquals("AC/DC", primaryArtist("AC/DC"))
        assertEquals("Crosby, Stills, Nash & Young", primaryArtist("Crosby, Stills, Nash & Young"))
        assertEquals("Hall & Oates", primaryArtist("Hall & Oates"))
        assertEquals("Kool & The Gang", primaryArtist("Kool & The Gang"))
        assertEquals("Earth, Wind & Fire", primaryArtist("Earth, Wind & Fire feat. Kenny G"))
        assertEquals("AC/DC", primaryArtist("AC/DC feat. Axl Rose"))
        assertEquals("Simon & Garfunkel", primaryArtist("Simon & Garfunkel (Live)"))
    }

    @Test
    fun `collabs fold under first artist`() {
        assertEquals("Future", primaryArtist("Future and Drake"))
        assertEquals("21 Savage", primaryArtist("21 Savage/Doja Cat"))
        assertEquals("Drake", primaryArtist("Drake & 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake, 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake, 21 Savage & Metro Boomin"))
        assertEquals("Drake", primaryArtist("Drake ; 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake;21 Savage"))
        assertEquals("Drake", primaryArtist("Drake / 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake x 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake with 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake w/ 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake vs. 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake vs 21 Savage"))
    }

    @Test
    fun `feat clauses stripped in all formats`() {
        assertEquals("Drake", primaryArtist("Drake feat. 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake ft. 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake featuring 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake feat 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake ft 21 Savage"))
        assertEquals("Drake", primaryArtist("Drake (feat. 21 Savage)"))
        assertEquals("Drake", primaryArtist("Drake [feat. 21 Savage]"))
        assertEquals("Drake", primaryArtist("Drake (ft. 21 Savage)"))
        assertEquals("Drake", primaryArtist("Drake [ft. 21 Savage]"))
        assertEquals("Drake", primaryArtist("Drake (with 21 Savage)"))
        assertEquals("Drake", primaryArtist("Drake [with 21 Savage]"))
        assertEquals("Drake", primaryArtist("Drake (w/ 21 Savage)"))
        assertEquals("Drake", primaryArtist("Drake [w/ 21 Savage]"))
        assertEquals("Drake", primaryArtist("Drake (feat. 21 Savage & Metro Boomin)"))
        assertEquals("Drake", primaryArtist("Drake feat. 21 Savage & Metro Boomin"))
        assertEquals("A", primaryArtist("A feat. B & C"))
    }

    @Test
    fun `trailing parens and brackets trimmed`() {
        assertEquals("Kendrick", primaryArtist("Kendrick (Deluxe)"))
        assertEquals("Kendrick", primaryArtist("Kendrick [Deluxe]"))
    }

    @Test
    fun `blank passes through`() {
        assertEquals("", primaryArtist(""))
        assertEquals("", primaryArtist("   "))
    }

    @Test
    fun `album key ignores case articles and edition tags`() {
        assertEquals(
            albumNormKey("A Great Chaos", "Ken Carson"),
            albumNormKey("Great Chaos (Deluxe)", "ken carson"),
        )
        assertEquals(
            albumNormKey("A Great Chaos", "Ken Carson"),
            albumNormKey("Great Chaos [Deluxe Edition]", "ken carson"),
        )
    }

    @Test
    fun `album key folds features into primary artist`() {
        assertEquals(
            albumNormKey("Her Loss", "Drake"),
            albumNormKey("Her Loss", "Drake feat. 21 Savage"),
        )
        assertEquals(
            albumNormKey("Her Loss", "Drake"),
            albumNormKey("Her Loss", "Drake & 21 Savage"),
        )
        assertEquals(
            albumNormKey("Her Loss", "Drake"),
            albumNormKey("Her Loss", "Drake, 21 Savage"),
        )
        assertEquals(
            albumNormKey("Her Loss", "Drake"),
            albumNormKey("Her Loss", "Drake / 21 Savage"),
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
