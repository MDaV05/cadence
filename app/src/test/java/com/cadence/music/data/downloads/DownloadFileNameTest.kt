package com.cadence.music.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** downloadFileName must never let a hostile server/source id escape the downloads dir. */
class DownloadFileNameTest {

    @Test
    fun plainIdsMapToTheConventionalName() {
        assertEquals("primary_track-7.audio", downloadFileName("subsonic", "primary:track-7"))
    }

    @Test
    fun pathSeparatorsAndDotsAreSanitized() {
        val name = downloadFileName("x", "a/../../etc/passwd")
        assertEquals("a_______etc_passwd.audio", name)
        assertFalse(name, name.contains('/'))
        assertFalse(name, name.contains(".."))
    }

    @Test
    fun percentEncodingIsSanitized() {
        val name = downloadFileName("x", "%2e%2e%2ff")
        assertFalse(name, name.contains('%'))
        assertFalse(name, name.contains('/'))
        assertEquals("_2e_2e_2ff.audio", name)
    }

    @Test
    fun blankServerIdFallsBackToSourceId() {
        assertEquals("primary.audio", downloadFileName("primary", ""))
    }
}
