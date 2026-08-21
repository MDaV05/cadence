package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random

class SubsonicSource(private val configProvider: () -> ServerConfig?) : MusicSource {

    override val id = "subsonic"

    private val config: ServerConfig
        get() = configProvider() ?: throw IllegalStateException("No server configured")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun url(endpoint: String, params: Map<String, String> = emptyMap()): String {
        val cfg = config
        val salt = Random.nextBytes(6).joinToString("") { "%02x".format(it) }
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

    private fun get(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject {
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
            return resp
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

    private fun albumTracks(albumId: String): List<Track> {
        val resp = get("getAlbum", mapOf("id" to albumId))
        val album = resp.getJSONObject("album")
        val albumName = album.optString("name", "Unknown")
        val songs = album.optJSONArray("song") ?: return emptyList()
        return (0 until songs.length()).map { i ->
            val s = songs.getJSONObject(i)
            Track(
                key = "sub:${s.getString("id")}",
                sourceId = id,
                title = s.optString("title", "Unknown"),
                artist = s.optString("artist", ""),
                album = albumName,
                durationMs = s.optLong("duration", 0) * 1000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("id")),
            )
        }
    }

    suspend fun albums(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
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
                out += a.getString("id") to a.optString("name", "Unknown")
            }
            offset += albums.length()
        }
        return out
    }

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
                durationMs = s.optLong("duration", 0) * 1000,
                localPath = null,
                streamUrl = streamUrlFor(s.getString("id")),
            )
        }
    }

    private fun streamUrlFor(songId: String) = url("stream", mapOf("id" to songId))

    override suspend fun streamUrl(track: Track): String? =
        track.streamUrl ?: track.key.removePrefix("sub:").let { streamUrlFor(it) }
}
