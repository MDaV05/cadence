package com.cadence.music.data

import com.cadence.music.data.db.TrackQueries
import org.junit.Assert.assertEquals
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
}
