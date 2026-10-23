package com.cadence.music.data.metadata

import org.junit.Assert.assertNull
import org.junit.Test

class WikipediaTest {

    @Test
    fun blankArtistNameReturnsNull() {
        assertNull(Wikipedia.artistInfoBlocking(""))
        assertNull(Wikipedia.artistInfoBlocking("   "))
    }
}
