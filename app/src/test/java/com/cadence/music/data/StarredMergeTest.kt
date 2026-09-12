package com.cadence.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarredMergeTest {

    @Test
    fun `server sources defer to the server value`() {
        assertTrue(mergedStarred("subsonic", true, false))
        assertFalse(mergedStarred("subsonic", false, true)) // server un-star clears locally
        assertTrue(mergedStarred("jellyfin", true, false))
        assertFalse(mergedStarred("emby", false, true))
    }

    @Test
    fun `local-only sources keep the local flag`() {
        assertTrue(mergedStarred("local", false, true))
        assertFalse(mergedStarred("local", true, false))
        assertTrue(mergedStarred("telegram", false, true))
        assertTrue(mergedStarred("plex", false, true))
    }
}
