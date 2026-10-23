package com.cadence.music.data.metadata

import com.cadence.music.data.stats.ArtistPlays
import org.json.JSONArray
import org.json.JSONObject

object ListenBrainz {

    private const val ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"
    private const val API_BASE = "https://api.listenbrainz.org/1"

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
     * Fire-and-forget single listen submission. `listenedAtSec` defaults to now;
     * replayed queue entries pass their original createdAt instead, so a late
     * flush does not backdate every listen to the retry time.
     * Returns the HTTP response code, or -1 on transport error.
     */
    fun submitBlocking(
        token: String,
        artist: String,
        title: String,
        album: String?,
        listenedAtSec: Long = System.currentTimeMillis() / 1000,
    ): Int {
        val payload = buildSubmitPayload(artist, title, album, listenedAtSec)
        val res = MetadataHttpClient.post(
            url = ENDPOINT,
            body = payload,
            headers = mapOf(
                "Authorization" to "Token $token",
                "Content-Type" to "application/json",
            ),
        )
        return res.code
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
        val res = MetadataHttpClient.get(
            url = "$API_BASE/validate-user-token/",
            headers = mapOf("Authorization" to "Token $token"),
        )
        if (res.code != 200 || res.body == null) return null
        return parseValidate(res.body)
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
        val res = MetadataHttpClient.get("$API_BASE/user/${MetadataHttpClient.encode(user)}/recent-listens")
        if (res.code != 200 || res.body == null) emptyList()
        else parseRecentListens(res.body)
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
        val res = MetadataHttpClient.get(
            "$API_BASE/user/${MetadataHttpClient.encode(user)}/statistics/statistics" +
                "?stats_range=half_year&count=5&offset=0",
        )
        if (res.code != 200 || res.body == null) null
        else parseLbStats(res.body)
    }.getOrNull()
}
