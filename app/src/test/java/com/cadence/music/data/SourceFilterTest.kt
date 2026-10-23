package com.cadence.music.data

import com.cadence.music.data.db.TrackQueries
import com.cadence.music.data.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFilterTest {

    @Test
    fun `active source filter with all sources returns empty where clause`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = emptySet(),
            offlineServerIds = emptySet(),
        )
        assertEquals("", sql)
        assertTrue(args.isEmpty())
    }

    @Test
    fun `active source filter excludes unselected servers and local`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = setOf("local", "srv1"),
            offlineServerIds = emptySet(),
        )
        assertTrue(sql.contains("sourceId = 'local'"))
        assertTrue(sql.contains("serverId LIKE ?"))
        assertEquals(1, args.size)
        assertEquals("srv1:%", args[0])
    }

    @Test
    fun `active source filter restricts specified servers to offline only`() {
        val (sql, args) = TrackQueries.activeSourceFilter(
            activeSources = setOf("srv1"),
            offlineServerIds = setOf("srv1"),
        )
        assertTrue(sql.contains("(path IS NOT NULL AND path != '')"))
        assertEquals(1, args.size)
        assertEquals("srv1:%", args[0])
    }

    @Test
    fun `isSourceFilterMatched correctly filters tracks based on active sources and offline state`() {
        // empty activeSources and offlineServerIds matches everything
        assertTrue(isSourceFilterMatched("local", "1", null, emptySet(), emptySet()))
        assertTrue(isSourceFilterMatched("subsonic", "srv1:track1", null, emptySet(), emptySet()))

        // activeSources only local
        assertTrue(isSourceFilterMatched("local", "1", null, setOf("local"), emptySet()))
        assertFalse(isSourceFilterMatched("subsonic", "srv1:track1", null, setOf("local"), emptySet()))

        // activeSources contains srv1 but not local
        assertFalse(isSourceFilterMatched("local", "1", null, setOf("srv1"), emptySet()))
        assertTrue(isSourceFilterMatched("subsonic", "srv1:track1", null, setOf("srv1"), emptySet()))

        // activeSources contains srv1 with offlineServerIds set for srv1
        assertFalse(isSourceFilterMatched("subsonic", "srv1:track1", null, setOf("srv1"), setOf("srv1")))
        assertTrue(isSourceFilterMatched("subsonic", "srv1:track1", "file:///dl.mp3", setOf("srv1"), setOf("srv1")))
    }

    @Test
    fun `tracksQuery incorporates active sources filter in generated query`() {
        val query = TrackQueries.tracksQuery(
            sort = Prefs.SongSort.TITLE,
            ascending = true,
            activeSources = setOf("local", "srv1"),
            offlineServerIds = setOf("srv1"),
        )
        val sql = query.sql
        assertTrue(sql.contains("sourceId = 'local'"))
        assertTrue(sql.contains("serverId LIKE ?"))
        assertTrue(sql.contains("(path IS NOT NULL AND path != '')"))
    }
}
