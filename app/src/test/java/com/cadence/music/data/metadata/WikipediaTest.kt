package com.cadence.music.data.metadata

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaTest {

    @Test
    fun blankArtistNameReturnsNull() {
        assertNull(Wikipedia.artistInfoBlocking(""))
        assertNull(Wikipedia.artistInfoBlocking("   "))
    }

    @Test
    fun resolvesWellKnownArtistWithSpaces() {
        val info = Wikipedia.artistInfoBlocking("Daft Punk")
        assertNotNull(info)
        assertNotNull(info?.bio)
        assertNotNull(info?.imageUrl)
        assertTrue(info!!.bio!!.contains("Daft Punk", ignoreCase = true) || info.bio!!.contains("electronic", ignoreCase = true))
    }

    @Test
    fun resolvesArtistRequiringDisambiguation() {
        val info = Wikipedia.artistInfoBlocking("Nirvana")
        assertNotNull(info)
        assertNotNull(info?.bio)
        assertTrue(info!!.bio!!.contains("band", ignoreCase = true) || info.bio!!.contains("rock", ignoreCase = true) || info.bio!!.contains("Cobain", ignoreCase = true))
    }
}
