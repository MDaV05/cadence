package com.cadence.music.data.metadata

import com.cadence.music.data.stats.ArtistPlays
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ListenBrainz {

    private const val ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"
    private const val API_BASE = "https://api.listenbrainz.org/1"
    private const val USER_AGENT = "Cadence/1.0"

    /** The exact submit-listens body; `listened_at` must be a JSON integer (seconds). */
    fun buildSubmitPayload(artist: String, title: String, album: String?, listenedAtSec: Long): String =
        JSONObject().apply {
            put("listen_type", "single")
            put(
                "payload",
                JSONArray().put(
                    JSONObject().apply {
                        put("listened_at", listenedAtSec)
                        put(
                            "track_metadata",
                            JSONObject().apply {
                                put("artist_name", artist)
                                put("track_name", title)
                                if (!album.isNullOrBlank()) put("release_name", album)
                            }
                        )
                    }
                )
            )
        }.toString()

    /**
     * Fire-and-forget single listen submission (now, at submit time).
     * Returns the HTTP response code, or -1 on transport error.
     */
    fun submitBlocking(token: String, artist: String, title: String, album: String?): Int {
        val payload = buildSubmitPayload(artist, title, album, System.currentTimeMillis() / 1000)
        return try {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Token $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { -1 }
    }

    // -- Read API: pure parsers + thin HTTP wrappers --------------------------
    // The parsers take the raw response body and never throw; the *Blocking
    // wrappers fetch the body (or fail) and delegate. The token only ever goes
    // into the validate Authorization header — never a URL, log, or return value.

    data class LbListen(val listenedAtSec: Long, val artist: String, val title: String)

    data class LbStats(val totalListens: Long, val topArtists: List<ArtistPlays>)

    /** Parse validate-user-token body -> (valid, userName?); userName null unless valid. */
    fun parseValidate(json: String?): Pair<Boolean, String?> = runCatching {
        val obj = json?.let { JSONObject(it) } ?: return@runCatching false to null
        val valid = obj.optBoolean("valid", false)
        val user = if (valid) obj.optString("user_name").ifBlank { null } else null
        valid to user
    }.getOrDefault(false to null)

    /**
     * Validate a token. Returns null on transport failure (couldn't reach LB,
     * non-200, timeout) — a non-null pair is a real verdict from the API.
     */
    fun validateBlocking(token: String): Pair<Boolean, String?>? {
        val body = getJson("$API_BASE/validate-user-token/", token = token) ?: return null
        return parseValidate(body)
    }

    /** Parse recent-listens body -> listens newest first (API order preserved), max 25. */
    fun parseRecentListens(json: String?): List<LbListen> = runCatching {
        val payload = json?.let { JSONObject(it) }?.optJSONObject("payload") ?: return@runCatching emptyList()
        val listens = payload.optJSONArray("listens") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until listens.length()) {
                val listen = listens.optJSONObject(i) ?: continue
                val meta = listen.optJSONObject("track_metadata") ?: continue
                // `opt(...) as? String` also rejects JSON-null values on every platform.
                val artist = (meta.opt("artist_name") as? String)?.trim()
                val title = (meta.opt("track_name") as? String)?.trim()
                if (artist.isNullOrEmpty() || title.isNullOrEmpty()) continue
                add(LbListen(listen.optLong("listened_at"), artist, title))
                if (size >= 25) break
            }
        }
    }.getOrDefault(emptyList())

    fun recentListensBlocking(user: String): List<LbListen> = runCatching {
        parseRecentListens(getJson("$API_BASE/user/${enc(user)}/recent-listens"))
    }.getOrDefault(emptyList())

    /** Parse statistics body (LB v2 shape) -> stats, or null when no payload. */
    fun parseLbStats(json: String?): LbStats? = runCatching {
        val payload = json?.let { JSONObject(it) }?.optJSONObject("payload") ?: return@runCatching null
        val top = payload.optJSONArray("top_artists")
        val artists = buildList {
            if (top != null) for (i in 0 until top.length()) {
                val entry = top.optJSONObject(i) ?: continue
                val name = (entry.optJSONObject("artist")?.opt("artist_name") as? String)?.trim()
                if (name.isNullOrEmpty()) continue
                add(ArtistPlays(name, entry.optInt("listen_count")))
            }
        }
        LbStats(payload.optLong("total_listen_count"), artists)
    }.getOrNull()

    fun lbStatsBlocking(user: String): LbStats? = runCatching {
        parseLbStats(
            getJson(
                "$API_BASE/user/${enc(user)}/statistics/statistics" +
                    "?stats_range=half_year&count=5&offset=0",
            ),
        )
    }.getOrNull()

    // Path segment, not a form field: a space must become %20, not '+'.
    private fun enc(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault("")

    private fun getJson(url: String, token: String? = null): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (token != null) conn.setRequestProperty("Authorization", "Token $token")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
