package com.cadence.music.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cadence.music.data.prefs.Prefs.SongSort

/** Pure-SQL builders for paged track queries. No Android calls — JVM-testable. */
object TrackQueries {

    fun tracksQuery(
        sort: SongSort,
        ascending: Boolean,
        sources: Set<String>? = null,
    ): SupportSQLiteQuery {
        val dir = if (ascending) "ASC" else "DESC"
        // Mirrors sortSongs() semantics exactly: NOCASE text, null lastPlayed as 0,
        // playCount tie-broken by lastPlayed, descending reverses every key.
        val order = when (sort) {
            SongSort.TITLE -> "title COLLATE NOCASE $dir"
            SongSort.ARTIST -> "artistName COLLATE NOCASE $dir"
            SongSort.ALBUM -> "albumName COLLATE NOCASE $dir"
            SongSort.DURATION -> "durationMs $dir"
            SongSort.RECENTLY_ADDED -> "id $dir"
            SongSort.RECENTLY_PLAYED -> "COALESCE(lastPlayed, 0) $dir"
            SongSort.MOST_PLAYED -> "playCount $dir, COALESCE(lastPlayed, 0) $dir"
        }
        // Sources come ONLY from sourcesFor() (internal constants) — never
        // interpolate raw strings into SQL; values ride as bound args.
        if (sources == null) return SimpleSQLiteQuery("SELECT * FROM tracks ORDER BY $order")
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE sourceId IN ($placeholders) ORDER BY $order",
            sorted.toTypedArray(),
        )
    }

    fun searchQuery(raw: String, sources: Set<String>? = null): SupportSQLiteQuery {
        val like = "%${escapeLike(raw)}%"
        val match =
            "title LIKE ? ESCAPE '\\' OR artistName LIKE ? ESCAPE '\\' OR albumName LIKE ? ESCAPE '\\'"
        if (sources == null) {
            return SimpleSQLiteQuery(
                "SELECT * FROM tracks WHERE $match ORDER BY title COLLATE NOCASE",
                arrayOf(like, like, like),
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE ($match) AND sourceId IN ($placeholders) " +
                "ORDER BY title COLLATE NOCASE",
            arrayOf(like, like, like, *sorted.toTypedArray()),
        )
    }

    /** Escapes LIKE metacharacters; caller wraps result in %...%. */
    fun escapeLike(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            if (c == '%' || c == '_' || c == '\\') out.append('\\')
            out.append(c)
        }
        return out.toString()
    }
}
