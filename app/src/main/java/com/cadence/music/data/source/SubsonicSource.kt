package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class SubsonicSource(private val configProvider: () -> ServerConfig?) : MusicSource {

    override val id = "subsonic"

    private val config: ServerConfig
        get() = configProvider() ?: throw IllegalStateException("No server configured")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun url(endpoint: String, params: Map<String, String> = emptyMap()): String {
        val cfg = config
        // Deterministic per-server salt: stream URLs must be byte-identical
        // across plays, or Media3's URI-keyed cache never gets a hit.
        val salt = md5("${cfg.user}:${cfg.password}").substring(0, 12)
        val token = md5(cfg.password + salt)
        val all = buildMap {
            putAll(params)
            put("u", cfg.user); put("t", token); put("s", salt)
            put("v", "1.16.1"); put("c", "Cadence"); put("f", "json")
        }
        val query = all.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "${cfg.url.trimEnd('/')}/rest/$endpoint?$query"
    }

    // All network I/O happens here so callers can't accidentally block the main thread.
    private suspend fun get(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val conn = URL(url(endpoint, params)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode !in 200..299) throw IOException("${conn.responseCode} $endpoint")
                val body = conn.inputStream.bufferedReader().readText()
                val resp = JSONObject(body).getJSONObject("subsonic-response")
                if (resp.getString("status") != "ok") {
                    throw IOException("Subsonic error: ${resp.optJSONObject("error")}")
                }
                resp
            } finally {
                conn.disconnect()
            }
        }

    suspend fun ping(): Boolean = try { get("ping"); true } catch (_: Exception) { false }

    override suspend fun scan(): List<Track> {
        val tracks = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val resp = get(
                "getAlbumList2",
                mapOf("type" to "alphabeticalByName", "size" to "500", "offset" to offset.toString()),
            )
            val albums = resp.optJSONObject("albumList2")?.optJSONArray("album") ?: break
            if (albums.length() == 0) break
            for (i in 0 until albums.length()) {
                val albumId = albums.getJSONObject(i).getString("id")
                tracks += albumTracks(albumId)
            }
            offset += albums.length()
        }
        return tracks
    }

    suspend fun listAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val resp = get(
                "getAlbumList2",
                mapOf("type" to "alphabeticalByName", "size" to "500", "offset" to offset.toString()),
            )
            val albums = resp.optJSONObject("albumList2")?.optJSONArray("album") ?: break
            if (albums.length() == 0) break
            for (i in 0 until albums.length()) {
                val a = albums.getJSONObject(i)
                out += Album(
                    key = "sub:${a.getString("id")}",
                    sourceId = id,
                    title = a.optString("name", "Unknown"),
                    artist = a.optString("artist", ""),
                    year = a.optInt("year", 0).takeIf { it > 0 },
                    remoteCreated = a.optString("created", null),
                )
            }
            offset += albums.length()
        }
        return out
    }

    suspend fun albumTracksByKey(albumKey: String): List<Track> =
        albumTracks(albumKey.removePrefix("sub:"))

    private suspend fun albumTracks(albumId: String): List<Track> {
        val resp = get("getAlbum", mapOf("id" to albumId))
        val album = resp.getJSONObject("album")
        val albumName = album.optString("name", "Unknown")
        val key = "sub:$albumId"
        val songs = album.optJSONArray("song") ?: return emptyList()
        return (0 until songs.length()).map { i ->
            val s = songs.getJSONObject(i)
            Track(
                key = "sub:${s.getString("id")}",
                sourceId = id,
                title = s.optString("title", "Unknown"),
                artist = s.optString("artist", ""),
                album = albumName,
                albumKey = key,
                durationMs = s.optLong("duration", 0) * 1000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("id")),
                starred = s.has("starred"),
            )
        }
    }

    /** Stars/unstars a song on the server. */
    suspend fun setStarred(songId: String, starred: Boolean) {
        get(if (starred) "star" else "unstar", mapOf("id" to songId))
    }

    fun coverArtUrl(albumKey: String): String =
        url("getCoverArt", mapOf("id" to albumKey.removePrefix("sub:")))

    override suspend fun search(query: String): List<Track> {
        val resp = get("search3", mapOf("query" to query, "songCount" to "50"))
        val songs = resp.getJSONObject("searchResult3").optJSONArray("song") ?: return emptyList()
        return (0 until songs.length()).map { i ->
            val s = songs.getJSONObject(i)
            Track(
                key = "sub:${s.getString("id")}",
                sourceId = id,
                title = s.optString("title", "Unknown"),
                artist = s.optString("artist", ""),
                album = s.optString("album", ""),
                albumKey = s.optString("albumId", null)?.let { "sub:$it" },
                durationMs = s.optLong("duration", 0) * 1000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("id")),
            )
        }
    }

    private fun streamUrlFor(songId: String) = url("stream", mapOf("id" to songId))

    fun downloadUrl(songId: String, format: String, bitrate: Int): String =
        if (format == "raw") url("download", mapOf("id" to songId))
        else url(
            "download",
            mapOf("id" to songId, "format" to format, "maxBitRate" to bitrate.toString()),
        )

    override suspend fun streamUrl(track: Track): String? =
        track.streamUrl ?: track.key.removePrefix("sub:").let { streamUrlFor(it) }
}
