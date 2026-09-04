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
        assertTrue(q.sql.contains("OR (sourceId = 'subsonic' AND path LIKE 'file:%')"))
        assertEquals(1, q.argCount)

        val s = TrackQueries.searchQuery("love", setOf("local"), includeDownloaded = true)
        assertTrue(s.sql.contains("OR (sourceId = 'subsonic' AND path LIKE 'file:%')"))
        assertEquals(4, s.argCount)
    }

    @Test
    fun `includeDownloaded false keeps plain IN clause`() {
        val q = TrackQueries.tracksQuery(SongSort.TITLE, true, setOf("local"), includeDownloaded = false)
        assertTrue(!q.sql.contains("path LIKE"))
    }
}
