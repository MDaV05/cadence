package com.cadence.music.data.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JSON-string fixtures only — no network, no Android. */
class SubsonicLyricsTest {

    @Test
    fun `lyrics endpoints get the raw remote song id`() {
        // fetchServerLyrics passes remoteKey's output ("sub:s6") — the family
        // prefix must be stripped before it rides in getLyricsBySongId?id=.
        assertEquals("s6", SubsonicSource.rawSongId("sub:s6"))
        assertEquals("s6", SubsonicSource.rawSongId("s6"))
    }

    @Test
    fun syncedEntryWinsAndAppliesSongOffset() {
        val resp = JSONObject(
            """{"lyricsList":{"structuredLyrics":[
               {"synced":false,"line":[{"value":"plain"}]},
               {"synced":true,"offset":1500,
                "line":[{"start":1000,"value":"First"},{"start":4800,"value":"Second"}]}
               ]}}""",
        )
        val (text, synced) = SubsonicSource.lyricsFromStructured(resp)!!
        assertTrue(synced)
        // Same [mm:ss.mmm] shape LrcLib.toLrcText emits for LRCLIB results.
        assertEquals("[00:02.500]First\n[00:06.300]Second", text)
    }

    @Test
    fun unsyncedLinesJoinAsPlainText() {
        val resp = JSONObject(
            """{"lyricsList":{"structuredLyrics":[
               {"synced":false,"line":[{"value":"one"},{"value":"two"}]}
               ]}}""",
        )
        assertEquals("one\ntwo" to false, SubsonicSource.lyricsFromStructured(resp))
    }

    @Test
    fun absentOrEmptyLyricsYieldNull() {
        assertNull(SubsonicSource.lyricsFromStructured(JSONObject("""{"status":"ok"}""")))
        assertNull(
            SubsonicSource.lyricsFromStructured(
                JSONObject("""{"lyricsList":{"structuredLyrics":[]}}"""),
            ),
        )
        assertNull(
            SubsonicSource.lyricsFromStructured(
                JSONObject("""{"lyricsList":{"structuredLyrics":[{"synced":true}]}}"""),
            ),
        )
    }
}
