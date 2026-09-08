package com.cadence.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexTest {

    @Test
    fun `pin request targets plex tv v2`() {
        assertEquals("https://plex.tv/api/v2/pins?strong=true", PlexPin.requestUrl())
    }

    @Test
    fun `stream url carries token`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        assertTrue(s.partUrl("/library/parts/7/123/file")?.contains("X-Plex-Token=tok") == true)
    }

    @Test
    fun `part url rejects authority injection`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        // "@evil.host/..." after base becomes userinfo and would leak X-Plex-Token.
        assertNull(s.partUrl("@evil.host/library/parts/7/1/file"))
        assertNull(s.partUrl("//evil.host/library/parts/7/1/file"))
        assertNull(s.partUrl("https://evil.host/x"))
        assertNull(s.partUrl("library/parts/7/1/file"))
        assertNull(s.partUrl(""))
    }

    @Test
    fun `part url accepts single slash paths with query`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        assertEquals(
            "http://nas:32400/library/parts/7/1/file?x=1&X-Plex-Token=tok",
            s.partUrl("/library/parts/7/1/file?x=1"),
        )
    }

    @Test
    fun `thumb url prefixes server`() {
        val s = PlexSource(
            com.cadence.music.data.prefs.ServerEntry(
                id = "p1", type = com.cadence.music.data.prefs.ServerType.PLEX,
                url = "http://nas:32400/", user = "", token = "tok",
            ),
            deviceId = "d1",
        )
        assertEquals("http://nas:32400/photo/:/transcode?url=/thumb&X-Plex-Token=tok", s.thumbUrl("/thumb"))
    }
}
