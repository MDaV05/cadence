package com.cadence.music.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cadence.music.data.prefs.Prefs.SongSort

/** Pure-SQL builders for paged track queries. No Android calls — JVM-testable. */
object TrackQueries {

    // Deliberately ignores trackNumber: local tags often lack it, and a
    // cross-source duplicate must still collapse. isSameTrack() is stricter
    // only when both rows have a positive track number.
    const val DEDUP_KEY_SQL =
        "CASE WHEN albumNorm != '' THEN albumNorm || '::' || LOWER(TRIM(title)) ELSE '::' || LOWER(TRIM(artistName)) || '::' || LOWER(TRIM(title)) END"

    const val PRIORITY_SQL =
        "(CASE WHEN sourceId = 'local' THEN 1 WHEN (path IS NOT NULL AND path != '') THEN 2 ELSE 3 END) * 1000000000000 + id"

    fun tracksQuery(
        sort: SongSort,
        ascending: Boolean,
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
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
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        val (whereClause, whereArgs) = if (sources == null) {
            if (extraSql.isEmpty()) {
                "" to emptyArray<Any>()
            } else {
                "WHERE ${extraSql.removePrefix(" AND ")}" to extraArgs
            }
        } else {
            val sorted = sources.sorted()
            val placeholders = sorted.joinToString(", ") { "?" }
            val downloaded = if (includeDownloaded) " OR (sourceId != 'local' AND path LIKE 'file:%')" else ""
            "WHERE (sourceId IN ($placeholders)$downloaded)$extraSql" to arrayOf<Any>(*sorted.toTypedArray(), *extraArgs)
        }
        val sql = "SELECT * FROM tracks WHERE id IN (" +
            "SELECT id FROM (SELECT id, MIN($PRIORITY_SQL) FROM tracks $whereClause GROUP BY $DEDUP_KEY_SQL)" +
            ") ORDER BY $order"
        return SimpleSQLiteQuery(sql, whereArgs)
    }

    fun searchQuery(
        raw: String,
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val like = "%${escapeLike(raw)}%"
        val match =
            "title LIKE ? ESCAPE '\\' OR artistName LIKE ? ESCAPE '\\' OR albumName LIKE ? ESCAPE '\\'"
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        val (whereClause, whereArgs) = if (sources == null) {
            if (extraSql.isEmpty()) {
                "WHERE $match" to arrayOf<Any>(like, like, like)
            } else {
                "WHERE ($match)$extraSql" to arrayOf<Any>(like, like, like, *extraArgs)
            }
        } else {
            val sorted = sources.sorted()
            val placeholders = sorted.joinToString(", ") { "?" }
            val downloaded = if (includeDownloaded) " OR (sourceId != 'local' AND path LIKE 'file:%')" else ""
            "WHERE ($match) AND (sourceId IN ($placeholders)$downloaded)$extraSql" to arrayOf<Any>(like, like, like, *sorted.toTypedArray(), *extraArgs)
        }
        val sql = "SELECT * FROM tracks WHERE id IN (" +
            "SELECT id FROM (SELECT id, MIN($PRIORITY_SQL) FROM tracks $whereClause GROUP BY $DEDUP_KEY_SQL)" +
            ") ORDER BY title COLLATE NOCASE"
        return SimpleSQLiteQuery(sql, whereArgs)
    }

    fun countQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        val (whereClause, whereArgs) = if (sources == null) {
            if (extraSql.isEmpty()) {
                "" to emptyArray<Any>()
            } else {
                "WHERE ${extraSql.removePrefix(" AND ")}" to extraArgs
            }
        } else {
            val sorted = sources.sorted()
            val placeholders = sorted.joinToString(", ") { "?" }
            val entryFilter = "sourceId IN ($placeholders) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%')"
            if (extraSql.isEmpty()) {
                "WHERE $entryFilter" to arrayOf<Any>(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0)
            } else {
                "WHERE ($entryFilter)$extraSql" to arrayOf<Any>(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *extraArgs)
            }
        }
        val wherePart = if (whereClause.isEmpty()) "" else " $whereClause"
        val sql = "SELECT COUNT(DISTINCT $DEDUP_KEY_SQL) FROM tracks$wherePart"
        return SimpleSQLiteQuery(sql, whereArgs)
    }

    fun artistPagesQuery(
        names: List<String>,
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        // names is non-empty (caller guards). Every name rides as a bound arg —
        // one placeholder per name, never concatenated into the SQL text.
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        val namePlaceholders = names.joinToString(", ") { "?" }
        val nameArgs = names.toTypedArray()
        if (sources == null) {
            return SimpleSQLiteQuery(
                "SELECT * FROM tracks WHERE artistName IN ($namePlaceholders)$extraSql ORDER BY artistName",
                arrayOf(*nameArgs, *extraArgs),
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT * FROM tracks WHERE artistName IN ($namePlaceholders) AND (sourceId IN ($placeholders) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%'))$extraSql ORDER BY artistName",
            arrayOf(*nameArgs, *sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *extraArgs),
        )
    }

    fun downloadableTracksQuery(
        sources: Set<String>? = null,
        activePrefixes: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        // Server tracks with no local copy. path IS NULL already excludes
        // downloaded rows, so there is deliberately no includeDownloaded param.
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        val base = "SELECT * FROM tracks WHERE sourceId != 'local' AND path IS NULL"
        val order = " ORDER BY artistName COLLATE NOCASE, albumName COLLATE NOCASE, trackNumber"
        if (sources == null) {
            return SimpleSQLiteQuery("$base$activeSql$order", activeArgs)
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "$base AND sourceId IN ($placeholders)$activeSql$order",
            arrayOf(*sorted.toTypedArray(), *activeArgs),
        )
    }

    fun artistTilesQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        val select = "SELECT DISTINCT t.artistName AS name, COALESCE('file://' || o.imagePath, a.imageUrl) AS imageUrl FROM tracks t " +
            "LEFT JOIN artist_info a ON a.name = t.artistName " +
            "LEFT JOIN artist_override o ON o.name = t.artistName WHERE t.artistName != ''"
        if (sources == null) {
            return SimpleSQLiteQuery(
                "$select$extraSql ORDER BY t.artistName COLLATE NOCASE",
                extraArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "$select AND (t.sourceId IN ($placeholders) " +
                "OR (? AND t.sourceId != 'local' AND t.path LIKE 'file:%'))$extraSql " +
                "ORDER BY t.artistName COLLATE NOCASE",
            arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *extraArgs),
        )
    }

    fun albumGroupsQuery(
        sources: Set<String>? = null,
        includeDownloaded: Boolean = false,
        activePrefixes: Set<String> = emptySet(),
        activeSources: Set<String> = emptySet(),
        offlineServerIds: Set<String> = emptySet(),
    ): SupportSQLiteQuery {
        val (extraSql, extraArgs) = combineFilters(activePrefixes, activeSources, offlineServerIds)
        if (sources == null) {
            if (extraSql.isEmpty()) {
                return SimpleSQLiteQuery(
                    "SELECT MIN(albumName) AS name, MIN(artistName) AS artistName, albumNorm AS norm, COUNT(DISTINCT LOWER(TRIM(title))) AS trackCount " +
                        "FROM tracks WHERE albumName != '' GROUP BY albumNorm ORDER BY name COLLATE NOCASE"
                )
            }
            return SimpleSQLiteQuery(
                "SELECT MIN(albumName) AS name, MIN(artistName) AS artistName, albumNorm AS norm, COUNT(DISTINCT LOWER(TRIM(title))) AS trackCount " +
                    "FROM tracks WHERE albumName != ''$extraSql GROUP BY albumNorm ORDER BY name COLLATE NOCASE",
                extraArgs,
            )
        }
        val sorted = sources.sorted()
        val placeholders = sorted.joinToString(", ") { "?" }
        return SimpleSQLiteQuery(
            "SELECT MIN(albumName) AS name, MIN(artistName) AS artistName, albumNorm AS norm, COUNT(DISTINCT LOWER(TRIM(title))) AS trackCount " +
                "FROM tracks WHERE albumName != '' AND (sourceId IN ($placeholders) " +
                "OR (? AND sourceId != 'local' AND path LIKE 'file:%'))$extraSql " +
                "GROUP BY albumNorm ORDER BY name COLLATE NOCASE",
            arrayOf(*sorted.toTypedArray(), if (includeDownloaded) 1 else 0, *extraArgs),
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

    /**
     * Source and offline filter: builds WHERE clause fragment and bound args.
     * Empty in = no filter ("" to emptyArray()).
     */
    fun activeSourceFilter(
        activeSources: Set<String>,
        offlineServerIds: Set<String>,
    ): Pair<String, Array<String>> {
        if (activeSources.isEmpty() && offlineServerIds.isEmpty()) return "" to emptyArray()

        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (activeSources.isNotEmpty()) {
            if ("local" in activeSources) {
                parts.add("sourceId = 'local'")
            }
            val serverSources = activeSources.filter { it != "local" }.sorted()
            for (srv in serverSources) {
                if (srv in offlineServerIds) {
                    parts.add("(serverId LIKE ? AND (path IS NOT NULL AND path != ''))")
                } else {
                    parts.add("(serverId LIKE ?)")
                }
                args.add("$srv:%")
            }
        } else if (offlineServerIds.isNotEmpty()) {
            for (srv in offlineServerIds.sorted()) {
                parts.add("(serverId LIKE ? AND (path IS NOT NULL AND path != ''))")
                args.add("$srv:%")
            }
        }

        if (parts.isEmpty()) return "" to emptyArray()
        return " AND (" + parts.joinToString(" OR ") + ")" to args.toTypedArray()
    }

    private fun combineFilters(
        activePrefixes: Set<String>,
        activeSources: Set<String>,
        offlineServerIds: Set<String>,
    ): Pair<String, Array<String>> {
        val (activeSql, activeArgs) = activeFilter(activePrefixes)
        val (sourceSql, sourceArgs) = activeSourceFilter(activeSources, offlineServerIds)
        val combinedSql = activeSql + sourceSql
        val combinedArgs = if (activeArgs.isEmpty()) sourceArgs
        else if (sourceArgs.isEmpty()) activeArgs
        else arrayOf(*activeArgs, *sourceArgs)
        return combinedSql to combinedArgs
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
