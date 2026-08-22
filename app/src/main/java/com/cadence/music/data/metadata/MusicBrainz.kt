package com.cadence.music.data.metadata

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private val ua = "Cadence/0.1 ( https://github.com/MDaV05/cadence )"

    private fun get(path: String, params: Map<String, String>): String? = try {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = URL("$BASE$path?$query&fmt=json").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", ua)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }

    fun searchReleaseGroup(artist: String, album: String): String? {
        val body = get(
            "/release-group",
            mapOf("query" to "artist:\"$artist\" AND releasegroup:\"$album\"", "limit" to "1"),
        ) ?: return null
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return null
        val id = groups.optJSONObject(0)?.optString("id")
        return id?.ifBlank { null }
    }

    /** Cover Art Archive serves images directly by release-group MBID. */
    fun coverArtUrl(releaseGroupMbid: String): String =
        "https://coverartarchive.org/release-group/$releaseGroupMbid/front-250"
}
