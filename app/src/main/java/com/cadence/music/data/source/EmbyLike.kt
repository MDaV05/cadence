package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerEntry

/** Value of the X-Emby-Authorization header (both Jellyfin and Emby accept it). */
fun EmbyLikeAuthHeader(client: String, version: String, deviceId: String, token: String?): String =
    buildString {
        append("MediaBrowser Client=\"$client\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"$version\"")
        if (token != null) append(", Token=\"$token\"")
    }

/**
 * Shared Jellyfin/Emby client core. Subclasses differ only in [id]/key prefix.
 * Network + org.json stay in thin private fns (same ruling as SubsonicSource.get);
 * URL builders are public for tests.
 */
abstract class EmbyLikeSource(
    protected val entry: ServerEntry,
    protected val deviceId: String = "",
) : MusicSource {

    protected fun base(): String = entry.url.trimEnd('/')
    protected fun token(): String = entry.token ?: ""
    protected fun uid(): String = entry.userId ?: ""

    /**
     * POST Users/AuthenticateByName — exchanges user+password for token.
     * Returns (token, userId) or null; the caller persists them via prefs.
     * The source never writes prefs.
     */
    abstract suspend fun authenticate(): Pair<String, String>?

    suspend fun ping(): Boolean =
        runCatching { get("Users/${uid()}") != null }.getOrDefault(false)

    fun streamUrlFor(remoteId: String): String = "${base()}/Audio/$remoteId/stream?api_key=${token()}"
    fun downloadUrl(songId: String): String =
        "${base()}/Items/${songId.removePrefix(prefix())}/Download?api_key=${token()}"
    fun coverArtUrl(albumKey: String): String =
        "${base()}/Items/${albumKey.removePrefix(prefix())}/Images/Primary?api_key=${token()}"

    protected abstract fun prefix(): String // "jelly:" or "emby:"

    override suspend fun streamUrl(track: Track): String? =
        track.streamUrl ?: track.key.removePrefix(prefix()).let { streamUrlFor(it) }

    suspend fun setStarred(songId: String, starred: Boolean) {
        val id = songId.removePrefix(prefix())
        if (starred) post("Users/${uid()}/FavoriteItems/$id") else delete("Users/${uid()}/FavoriteItems/$id")
    }

    // scan() no-op: library sync uses listAlbums+albumTracksByKey (same as Subsonic callers).
    override suspend fun scan(): List<Track> = emptyList()

    suspend fun listAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        var start = 0
        while (true) {
            val obj = get(
                "Users/${uid()}/Items?Recursive=true&IncludeItemTypes=MusicAlbum" +
                    "&SortBy=SortName&Fields=ProductionYear&StartIndex=$start&Limit=500",
            ) ?: break
            val items = obj.optJSONArray("Items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val a = items.getJSONObject(i)
                out += Album(
                    key = "${prefix()}${a.getString("Id")}",
                    sourceId = id,
                    title = a.optString("Name", "Unknown"),
                    artist = a.optString("AlbumArtist", ""),
                    year = a.optInt("ProductionYear", 0).takeIf { it > 0 },
                )
            }
            start += items.length()
        }
        return out
    }

    suspend fun albumTracksByKey(albumKey: String): List<Track> {
        val albumId = albumKey.removePrefix(prefix())
        val obj = get(
            "Users/${uid()}/Items?ParentId=$albumId&Recursive=true" +
                "&IncludeItemTypes=Audio&Fields=RunTimeTicks,IndexNumber",
        ) ?: return emptyList()
        val items = obj.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val s = items.getJSONObject(i)
            Track(
                key = "${prefix()}${s.getString("Id")}",
                sourceId = id,
                title = s.optString("Name", "Unknown"),
                artist = s.optString("AlbumArtist", ""),
                album = s.optString("Album", ""),
                albumKey = s.optString("AlbumId", null)?.let { "${prefix()}$it" } ?: albumKey,
                durationMs = s.optLong("RunTimeTicks", 0) / 10_000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("Id")),
            )
        }
    }

    override suspend fun search(query: String): List<Track> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val obj = get(
            "Users/${uid()}/Items?Recursive=true&IncludeItemTypes=Audio&SearchTerm=$q&Limit=50",
        ) ?: return emptyList()
        val items = obj.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val s = items.getJSONObject(i)
            Track(
                key = "${prefix()}${s.getString("Id")}",
                sourceId = id,
                title = s.optString("Name", "Unknown"),
                artist = s.optString("AlbumArtist", ""),
                album = s.optString("Album", ""),
                albumKey = s.optString("AlbumId", null)?.let { "${prefix()}$it" },
                durationMs = s.optLong("RunTimeTicks", 0) / 10_000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("Id")),
            )
        }
    }

    protected suspend fun get(path: String): org.json.JSONObject? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("${base()}/$path").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("X-Emby-Authorization", EmbyLikeAuthHeader("Cadence", "0.2.0", deviceId, null))
                if (token().isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token())
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    protected suspend fun post(path: String, body: String = ""): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("${base()}/$path").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.requestMethod = "POST"
                conn.doOutput = body.isNotEmpty()
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Emby-Authorization", EmbyLikeAuthHeader("Cadence", "0.2.0", deviceId, null))
                if (token().isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token())
                try {
                    if (body.isNotEmpty()) conn.outputStream.bufferedWriter().use { it.write(body) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        }

    protected suspend fun delete(path: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("${base()}/$path").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("X-Emby-Authorization", EmbyLikeAuthHeader("Cadence", "0.2.0", deviceId, null))
                if (token().isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token())
                try {
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        }

    protected suspend fun authenticatePost(body: String): Pair<String, String>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("${base()}/Users/AuthenticateByName")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Emby-Authorization", EmbyLikeAuthHeader("Cadence", "0.2.0", deviceId, null))
                try {
                    conn.outputStream.bufferedWriter().use { it.write(body) }
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val obj = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    val token = obj.optString("AccessToken", null) ?: return@runCatching null
                    val userId = obj.optJSONObject("User")?.optString("Id", null) ?: return@runCatching null
                    token to userId
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
}
