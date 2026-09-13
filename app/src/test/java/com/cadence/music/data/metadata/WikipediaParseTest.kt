package com.cadence.music.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikipediaParseTest {

    @Test
    fun `first artist mbid wins`() {
        val json = """{"created":"x","count":2,"artists":[
            {"id":"mbid-1","name":"Justice","score":100},
            {"id":"mbid-2","name":"justice cover band","score":42}]}"""
        assertEquals("mbid-1", parseMbArtistMbid(json))
    }

    @Test
    fun `empty or garbage artist search yields null`() {
        assertNull(parseMbArtistMbid("""{"artists":[]}"""))
        assertNull(parseMbArtistMbid("""{"count":0}"""))
        assertNull(parseMbArtistMbid("not json"))
        assertNull(parseMbArtistMbid(null))
    }

    @Test
    fun `wikipedia relation gives the page title`() {
        val json = """{"relations":[
            {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q299857"}},
            {"type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Justice_(band)"}}]}"""
        assertEquals("Justice (band)", parseMbWikipediaTitle(json))
    }

    @Test
    fun `no wikipedia relation yields null`() {
        val json = """{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}"""
        assertNull(parseMbWikipediaTitle(json))
        assertNull(parseMbWikipediaTitle("garbage"))
        assertNull(parseMbWikipediaTitle(null))
    }

    @Test
    fun `wikidata sitelink gives the en page title`() {
        val json = """{"entities":{"Q299857":{"sitelinks":{
            "dewiki":{"title":"Justice (Band)","site":"dewiki"},
            "enwiki":{"title":"Justice (band)","site":"enwiki"}}}}}"""
        assertEquals("Justice (band)", parseWikidataSitelink(json))
    }

    @Test
    fun `missing enwiki sitelink yields null`() {
        val json = """{"entities":{"Q1":{"sitelinks":{"dewiki":{"title":"X","site":"dewiki"}}}}}"""
        assertNull(parseWikidataSitelink(json))
        assertNull(parseWikidataSitelink("{}"))
        assertNull(parseWikidataSitelink(null))
    }
}
