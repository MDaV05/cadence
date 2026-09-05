package com.cadence.music.data.db

import com.cadence.music.data.prefs.Prefs.SongSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackQueriesTest {

    @Test
    fun `title ascending is case-insensitive`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true)
        assertEquals("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC", q.sql)
    }

    @Test
    fun `descending reverses all keys including tiebreak`() {
        val q = TrackQueries.tracksQuery(SongSort.MOST_PLAYED, false)
        assertEquals(
            "SELECT * FROM tracks ORDER BY playCount DESC, COALESCE(lastPlayed, 0) DESC",
            q.sql,
        )
    }

    @Test
    fun `recently played nulls sort as zero`() {
        val q = TrackQueries.tracksQuery(SongSort.RECENTLY_PLAYED, true)
        assertTrue(q.sql.contains("COALESCE(lastPlayed, 0) ASC"))
    }

    @Test
    fun `all seven sorts produce distinct order clauses`() {
        val sqls = SongSort.values().map { TrackQueries.tracksQuery(it, true).sql }.toSet()
        assertEquals(7, sqls.size)
    }

    @Test
    fun `like metacharacters are escaped`() {
        assertEquals("%100\\%\\_x\\\\y%", "%${TrackQueries.escapeLike("100%_x\\y")}%")
    }

    @Test
    fun `search binds three args with escape clause`() {
        val q = TrackQueries.searchQuery("love")
        assertEquals(3, q.argCount)
        assertTrue(q.sql.contains("title LIKE ? ESCAPE '\\'"))
        assertTrue(q.sql.contains("artistName LIKE ? ESCAPE '\\'"))
        assertTrue(q.sql.contains("albumName LIKE ? ESCAPE '\\'"))
    }

    @Test
    fun `sources filter appends IN clause with bound args`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true, setOf("local", "subsonic"))
        assertEquals(
            "SELECT * FROM tracks WHERE (sourceId IN (?, ?)) ORDER BY title COLLATE NOCASE ASC",
            q.sql,
        )
        assertEquals(2, q.argCount)

        val s = TrackQueries.searchQuery("love", setOf("subsonic"))
        assertTrue(s.sql.contains("AND (sourceId IN (?))"))
        assertEquals(4, s.argCount)
    }
    @Test
    fun `null sources produce no source filter`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true, null)
        assertTrue(!q.sql.contains("sourceId"))
        assertTrue(!q.sql.contains("WHERE"))

        val s = TrackQueries.searchQuery("love", null)
        assertTrue(!s.sql.contains("sourceId"))
        assertEquals(3, s.argCount)
    }

    @Test
    fun `includeDownloaded adds offline OR clause without new args`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true, setOf("local"), includeDownloaded = true)
        assertTrue(q.sql.contains("sourceId IN (?)"))
        assertTrue(q.sql.contains("OR (sourceId != 'local' AND path LIKE 'file:%')"))
        assertEquals(1, q.argCount)

        val s = TrackQueries.searchQuery("love", setOf("local"), includeDownloaded = true)
        assertTrue(s.sql.contains("OR (sourceId != 'local' AND path LIKE 'file:%')"))
        assertEquals(4, s.argCount)
    }

    @Test
    fun `includeDownloaded false keeps plain IN clause`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true, setOf("local"), includeDownloaded = false)
        assertTrue(!q.sql.contains("path LIKE"))
    }

    @Test
    fun `tracks null sources with active filter emits WHERE without leading AND`() {
        val q =
            TrackQueries.tracksQuery(
                SongSort.TITLE, true, null, activePrefixes = setOf("e1"),
            )
        assertEquals(
            "SELECT * FROM tracks WHERE (serverId LIKE ?) ORDER BY title COLLATE NOCASE ASC",
            q.sql,
        )
        assertEquals(1, q.argCount)
    }

    @Test
    fun `count no filter is byte-identical to unfiltered SQL`() {
        assertEquals("SELECT COUNT(*) FROM tracks", TrackQueries.countQuery(null).sql)
        assertEquals(0, TrackQueries.countQuery(null).argCount)

        assertEquals(
            "SELECT COUNT(*) FROM tracks WHERE sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')",
            TrackQueries.countQuery(setOf("local")).sql,
        )
        assertEquals(2, TrackQueries.countQuery(setOf("local")).argCount)
    }

    @Test
    fun `count null sources with active filter emits WHERE without leading AND`() {
        val q = TrackQueries.countQuery(null, activePrefixes = setOf("e1"))
        assertEquals("SELECT COUNT(*) FROM tracks WHERE (serverId LIKE ?)", q.sql)
        assertEquals(1, q.argCount)
    }

    @Test
    fun `count with sources and active filter parenthesizes OR branch`() {
        val q = TrackQueries.countQuery(setOf("local"), activePrefixes = setOf("e1"))
        assertEquals(
            "SELECT COUNT(*) FROM tracks WHERE (sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')) AND (serverId LIKE ?)",
            q.sql,
        )
        assertEquals(3, q.argCount)
    }

    @Test
    fun `artistNames no filter is byte-identical to unfiltered SQL`() {
        assertEquals(
            "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' ORDER BY artistName",
            TrackQueries.artistNamesQuery(null).sql,
        )
        assertEquals(
            "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' AND (sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')) ORDER BY artistName",
            TrackQueries.artistNamesQuery(setOf("local")).sql,
        )
    }

    @Test
    fun `artistNames with active filter appends AND clause`() {
        assertEquals(
            "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' AND (serverId LIKE ?) " +
                "ORDER BY artistName",
            TrackQueries.artistNamesQuery(null, activePrefixes = setOf("e1")).sql,
        )
        assertEquals(
            "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' AND (sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')) AND (serverId LIKE ?) " +
                "ORDER BY artistName",
            TrackQueries.artistNamesQuery(setOf("local"), activePrefixes = setOf("e1")).sql,
        )
        assertEquals(1, TrackQueries.artistNamesQuery(null, activePrefixes = setOf("e1")).argCount)
        assertEquals(3, TrackQueries.artistNamesQuery(setOf("local"), activePrefixes = setOf("e1")).argCount)
    }

    @Test
    fun `albumGroups no filter is byte-identical to unfiltered SQL`() {
        assertEquals(
            "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                "FROM tracks WHERE albumName != '' GROUP BY albumName ORDER BY name COLLATE NOCASE",
            TrackQueries.albumGroupsQuery(null).sql,
        )
        assertEquals(
            "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                "FROM tracks WHERE albumName != '' AND (sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')) " +
                "GROUP BY albumName ORDER BY name COLLATE NOCASE",
            TrackQueries.albumGroupsQuery(setOf("local")).sql,
        )
    }

    @Test
    fun `albumGroups with active filter appends AND clause`() {
        assertEquals(
            "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                "FROM tracks WHERE albumName != '' AND (serverId LIKE ?) " +
                "GROUP BY albumName ORDER BY name COLLATE NOCASE",
            TrackQueries.albumGroupsQuery(null, activePrefixes = setOf("e1")).sql,
        )
        assertEquals(
            "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                "FROM tracks WHERE albumName != '' AND (sourceId IN (?) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')) AND (serverId LIKE ?) " +
                "GROUP BY albumName ORDER BY name COLLATE NOCASE",
            TrackQueries.albumGroupsQuery(setOf("local"), activePrefixes = setOf("e1")).sql,
        )
    }

    @Test
    fun `albumsFor no filter is byte-identical to unfiltered SQL`() {
        assertEquals(
            "SELECT * FROM albums WHERE sourceId IN (?) ORDER BY year DESC, title",
            TrackQueries.albumsForQuery(setOf("local")).sql,
        )
        assertEquals(1, TrackQueries.albumsForQuery(setOf("local")).argCount)
    }

    @Test
    fun `albumsFor with active filter appends AND clause`() {
        val q = TrackQueries.albumsForQuery(setOf("local"), setOf("e1"))
        assertEquals(
            "SELECT * FROM albums WHERE sourceId IN (?) AND (serverId LIKE ?) ORDER BY year DESC, title",
            q.sql,
        )
        assertEquals(2, q.argCount)
    }
}
