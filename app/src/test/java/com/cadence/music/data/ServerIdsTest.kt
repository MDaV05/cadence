package com.cadence.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdsTest {

    @Test
    fun `entryIdOf takes segment before first colon`() {
        assertEquals("primary", entryIdOf("primary:sub:5"))
        assertEquals("local", entryIdOf("local:1"))
        assertNull(entryIdOf("nocolon"))
        assertNull(entryIdOf(""))
    }

    @Test
    fun `namespacedKey joins with colon`() {
        assertEquals("primary:sub:5", namespacedKey("primary", "sub:5"))
        assertEquals("p1:plex:9", namespacedKey("p1", "plex:9"))
    }

    @Test
    fun `legacyPrefixSql is the exact v9 migration`() {
        assertEquals(
            listOf(
                "UPDATE tracks SET serverId = 'primary:' || serverId WHERE sourceId != 'local'",
                "UPDATE tracks SET albumKey = 'primary:' || albumKey WHERE albumKey IS NOT NULL AND sourceId != 'local'",
                "UPDATE albums SET serverId = 'primary:' || serverId",
                "UPDATE downloads SET trackServerId = 'primary:' || trackServerId",
            ),
            legacyPrefixSql(),
        )
    }

    @Test
    fun `resumeLookupIds retries primary prefix only when unknown`() {
        assertEquals(listOf("5", "primary:5"), resumeLookupIds("5", emptySet()))
        assertEquals(
            listOf("sub:5", "primary:sub:5"),
            resumeLookupIds("sub:5", setOf("primary")),
        )
        assertEquals(
            listOf("primary:sub:5"),
            resumeLookupIds("primary:sub:5", setOf("primary")),
        )
        assertEquals(
            listOf("p1:jelly:9"),
            resumeLookupIds("p1:jelly:9", setOf("p1", "p2")),
        )
    }

    @Test
    fun `isEntryActive keeps local and active entries`() {
        val active = setOf("local", "primary")
        assertTrue(isEntryActive("local", "local:1", active))
        assertTrue(isEntryActive("local", "local:1", emptySet()))
        assertTrue(isEntryActive("subsonic", "primary:sub:5", active))
        assertFalse(isEntryActive("subsonic", "gone:sub:5", active))
        assertFalse(isEntryActive("subsonic", "unprefixed", active))
    }
}
