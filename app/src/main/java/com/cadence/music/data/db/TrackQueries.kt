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
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
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
        // interpolate raw strings into SQL; values ride as bound args. The downloaded
        // clause uses literals for the same reason (fixed internal strings only).
        // activePrefixes holds entry ids (internal); each rides as a bound "id:%" arg.
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        if (sources == null) {
            if (activeSql.isEmpty()) return SimpleSQLiteQuery("SELECT * FROM tracks ORDER BY $order")
            return SimpleSQLiteQuery(
                "SELECT * FROM tracks WHERE ${activeSql.removePrefix(" AND ")} ORDER BY $order",
                activeArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        val downloaded = if (includeDownloaded) " OR (sourceId != 'local' AND path LIKE 'file:%')" else ""
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE (sourceId IN ($placeholders)$downloaded)$activeSql ORDER BY $order",
            arrayOf(*sorted.toTypedArray(), *activeArgs),
        )
    }

    fun searchQuery(
        raw: String,
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val like = "%${escapeLike(raw)}%"
        val match =
            "title LIKE ? ESCAPE '\\' OR artistName LIKE ? ESCAPE '\\' OR albumName LIKE ? ESCAPE '\\'"
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        if (sources == null) {
            if (activeSql.isEmpty()) {
                return SimpleSQLiteQuery(
                    "SELECT * FROM tracks WHERE $match ORDER BY title COLLATE NOCASE",
                    arrayOf(like, like, like),
                )
            }
            return SimpleSQLiteQuery(
                "SELECT * FROM tracks WHERE ($match)$activeSql ORDER BY title COLLATE NOCASE",
                arrayOf(like, like, like, *activeArgs),
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        val downloaded = if (includeDownloaded) " OR (sourceId != 'local' AND path LIKE 'file:%')" else ""
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE ($match) AND (sourceId IN ($placeholders)$downloaded)$activeSql " +
                "ORDER BY title COLLATE NOCASE",
            arrayOf(like, like, like, *sorted.toTypedArray(), *activeArgs),
        )
    }

    fun countQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        if (sources == null) {
            if (activeSql.isEmpty()) return SimpleSQLiteQuery("SELECT COUNT(*) FROM tracks")
            return SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM tracks WHERE ${activeSql.removePrefix(" AND ")}",
                activeArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        val entryFilter = "sourceId IN ($placeholders) " +
            "OR (? AND sourceId != 'local' AND path LIKE 'file:%')"
        if (activeSql.isEmpty()) {
            return SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM tracks WHERE $entryFilter",
                arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0),
            )
        }
        return SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM tracks WHERE ($entryFilter)$activeSql",
            arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *activeArgs),
        )
    }

    fun artistNamesQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        if (sources == null) {
            if (activeSql.isEmpty()) {
                return SimpleSQLiteQuery(
                    "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' ORDER BY artistName"
                )
            }
            return SimpleSQLiteQuery(
                "SELECT DISTINCT artistName FROM tracks WHERE artistName != ''$activeSql ORDER BY artistName",
                activeArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT DISTINCT artistName FROM tracks WHERE artistName != '' AND (sourceId IN ($placeholders) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%'))$activeSql ORDER BY artistName",
            arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *activeArgs),
        )
    }

    fun albumGroupsQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        if (sources == null) {
            if (activeSql.isEmpty()) {
                return SimpleSQLiteQuery(
                    "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                        "FROM tracks WHERE albumName != '' GROUP BY albumName ORDER BY name COLLATE NOCASE"
                )
            }
            return SimpleSQLiteQuery(
                "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                    "FROM tracks WHERE albumName != ''$activeSql GROUP BY albumName ORDER BY name COLLATE NOCASE",
                activeArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT albumName AS name, MIN(artistName) AS artistName, COUNT(*) AS trackCount " +
                "FROM tracks WHERE albumName != '' AND (sourceId IN ($placeholders) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%'))$activeSql " +
                "GROUP BY albumName ORDER BY name COLLATE NOCASE",
            arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *activeArgs),
        )
    }

    fun albumsForQuery(
        sources: Set<String>,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        return SimpleSQLiteQuery(
            "SELECT * FROM albums WHERE sourceId IN ($placeholders)$activeSql ORDER BY year DESC, title",
            arrayOf(*sorted.toTypedArray(), *activeArgs),
        )
    }

    /**
     * Entry-active filter: (" AND (serverId LIKE ? OR ...)", ["id:%", ...]).
     * Empty in = no filter (SQL byte-identical to the unfiltered builders).
     */
    private fun activeFilter(activePrefixes: Set<String>): Pair<String, Array<String>> {
        if (activePrefixes.isEmpty()) return "" to emptyArray()
        val sorted = activePrefixes.sorted()
        val clause = sorted.joinToString(" OR ", prefix = " AND (", postfix = ")") { "serverId LIKE ?" }
        return clause to sorted.map { "$it:%" }.toTypedArray()
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
