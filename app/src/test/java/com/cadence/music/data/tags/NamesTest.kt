package com.cadence.music.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `lead and feat both become candidates`() {
        assertEquals(listOf("Future", "Drake"), artistCandidates("Future feat. Drake"))
    }

    @Test
    fun `collaborators split into candidates`() {
        assertEquals(listOf("A", "B"), artistCandidates("A / B"))
        assertEquals(listOf("A", "B", "C", "D"), artistCandidates("A, B & C and D"))
    }

    @Test
    fun `known ensembles are never split`() {
        assertEquals(listOf("AC/DC"), artistCandidates("AC/DC"))
        assertEquals(listOf("Simon & Garfunkel"), artistCandidates("Simon & Garfunkel"))
    }

    @Test
    fun `ensemble keeps whole while feat name is added`() {
        assertEquals(
            listOf("Simon & Garfunkel", "Paul Simon"),
            artistCandidates("Simon & Garfunkel feat. Paul Simon"),
        )
    }

    @Test
    fun `bracketed feat and edition tags handled`() {
        assertEquals(listOf("Kendrick", "SZA"), artistCandidates("Kendrick (Deluxe) feat. SZA"))
    }

    @Test
    fun `case-insensitive dedupe keeps first form`() {
        assertEquals(listOf("A"), artistCandidates("A feat. a"))
    }

    @Test
    fun `blank yields no candidates`() {
        assertEquals(emptyList<String>(), artistCandidates("   "))
    }

    @Test
    fun `bracketed feat clause becomes a candidate without stray brackets`() {
        assertEquals(listOf("Drake", "21 Savage"), artistCandidates("Drake (feat. 21 Savage)"))
    }

    @Test
    fun `attacker-length tag returns in bounded time without throwing`() {
        // R3-01: nested-quantifier regexes blow up super-linearly on long
        // whitespace/delimiter runs; the 512-char cap makes them O(1).
        val started = System.nanoTime()
        // Only the first MAX_TAG_LEN chars are inspected, so the trailing "x" is dropped.
        assertEquals("", primaryArtist(" ".repeat(10_000) + "x"))
        assertEquals("", primaryArtist(" ".repeat(10_000)))
        assertEquals("A", primaryArtist("A feat. " + " ".repeat(10_000)))
        assertTrue(albumNormKey(" ".repeat(10_000), "a".repeat(10_000)).isNotEmpty())
        artistCandidates("a feat. " + "x ".repeat(10_000))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("parsing took ${ms}ms", ms < 5_000)
    }

    @Test
    fun `candidate list is capped at sixteen`() {
        // R3-12: 40 delimiters must not fan out into an unbounded SQLite IN-list.
        val raw = (1..41).joinToString("; ") { "Artist$it" }
        val candidates = artistCandidates(raw)
        assertEquals(16, candidates.size)
        assertEquals("Artist1", candidates.first())
        assertEquals("Artist16", candidates.last())
    }
}
