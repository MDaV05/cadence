package com.cadence.music.data.metadata

import com.cadence.music.data.source.sameOrigin
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private val ua = "Cadence/1.0 ( https://github.com/MDaV05/cadence )"

    private fun get(path: String, params: Map<String, String>): String? = try {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        // R3-07: no auto-follow — requests must stay on the pinned origin.
        val open = { u: URL ->
            (u.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", ua)
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
            }
        }
        var conn = open(URL("$BASE$path?$query&fmt=json"))
        try {
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops < 3) {
                val loc = conn.getHeaderField("Location") ?: break
                val next = URL(conn.getURL(), loc)
                if (!sameOrigin(conn.getURL().toString(), next.toString())) break
                conn.disconnect()
                conn = open(next)
                code = conn.responseCode
                hops++
            }
            if (code != 200) null
            // R3-15: cap the body so a hostile/corrupt response can't exhaust memory.
            else conn.inputStream.use { it.readNBytes(2 * 1024 * 1024) }.decodeToString()
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }

    /** Escape lucene clause characters: backslash first, then double quote. */
    internal fun escapeLucene(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun searchReleaseGroup(artist: String, album: String): String? {
        val body = get(
            "/release-group",
            mapOf(
                "query" to "artist:\"${escapeLucene(artist)}\" AND releasegroup:\"${escapeLucene(album)}\"",
                "limit" to "1",
            ),
        ) ?: return null
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return null
        val id = groups.optJSONObject(0)?.optString("id")
        return id?.ifBlank { null }
    }

    /** Cover Art Archive serves images directly by release-group MBID. */
    fun coverArtUrl(releaseGroupMbid: String): String =
        "https://coverartarchive.org/release-group/$releaseGroupMbid/front-250"
}
