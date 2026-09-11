package com.cadence.music.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzTest {

    @Test
    fun `quotes are escaped and cannot close the quoted clause`() {
        assertEquals("a\\\"b", MusicBrainz.escapeLucene("a\"b"))
    }

    @Test
    fun `backslash escapes are applied before quotes`() {
        // Input: backslash-backslash-a-quote-b-backslash-backslash
        // Output: each backslash doubles, the quote gains a leading backslash.
        assertEquals("\\\\\\\\a\\\"b\\\\\\\\", MusicBrainz.escapeLucene("\\\\a\"b\\\\"))
        assertEquals("\\\\", MusicBrainz.escapeLucene("\\"))
    }

    @Test
    fun `plain text is unchanged`() {
        assertEquals("Daft Punk", MusicBrainz.escapeLucene("Daft Punk"))
    }
}
