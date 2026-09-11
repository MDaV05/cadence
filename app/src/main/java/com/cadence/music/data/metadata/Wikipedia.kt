package com.cadence.music.data.metadata

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArtistInfo(val bio: String?, val imageUrl: String?)

object Wikipedia {

    private const val BASE = "https://en.wikipedia.org/api/rest_v1"
    private const val USER_AGENT = "Cadence/0.1 ( https://github.com/MDaV05/cadence )"

    private val MUSIC_KEYWORDS = setOf(
        "band", "musician", "singer", "rapper", "music", "composer", "group",
        "orchestra", "dj", "duo", "trio", "quartet", "record", "album", "vocalist",
        "producer", "song", "disc jockey", "rock", "pop", "metal", "jazz", "hip hop",
        "soundtrack", "guitarist", "drummer", "bassist", "pianist", "songwriter",
    )

    private fun encodePath(title: String): String =
        URLEncoder.encode(title.trim().replace(' ', '_'), "UTF-8").replace("+", "%20")

    private data class PageSummary(
        val title: String,
        val bio: String?,
        val imageUrl: String?,
        val isMusic: Boolean,
    )

    private fun fetchPageSummary(title: String): PageSummary? {
        if (title.isBlank()) return null
        return try {
            val enc = encodePath(title)
            val conn = URL("$BASE/page/summary/$enc").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return null
                // R3-15: cap the body so a hostile/corrupt response can't exhaust memory.
                val json = JSONObject(conn.inputStream.use { it.readNBytes(2 * 1024 * 1024) }.decodeToString())
                if (json.optString("type") != "standard") return null
                val desc = json.optString("description", "")
                val extract = json.optString("extract", "").ifBlank { null }
                val img = json.optJSONObject("thumbnail")?.optString("source")
                    ?: json.optJSONObject("originalimage")?.optString("source")
                val text = "$desc ${extract.orEmpty()}".lowercase()
                val isMusic = MUSIC_KEYWORDS.any { it in text }
                PageSummary(
                    title = json.optString("title", title),
                    bio = extract,
                    imageUrl = img,
                    isMusic = isMusic,
                )
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { null }
    }

    fun summaryBlocking(title: String): ArtistInfo? {
        val summary = fetchPageSummary(title) ?: return null
        return ArtistInfo(bio = summary.bio, imageUrl = summary.imageUrl)
    }

    /** Search Wikipedia for the best-matching artist page, then summarize it. */
    fun artistInfoBlocking(name: String): ArtistInfo? {
        val clean = name.trim()
        if (clean.isBlank()) return null

        // 1. Try direct title lookup. If clearly music-related, we're done.
        val direct = fetchPageSummary(clean)
        if (direct != null && direct.isMusic) {
            return ArtistInfo(bio = direct.bio, imageUrl = direct.imageUrl)
        }

        // 2. Try common disambiguation qualifiers for musical artists.
        val qualifiers = listOf(
            "$clean (band)",
            "$clean (musician)",
            "$clean (singer)",
            "$clean (rapper)",
            "$clean (music group)",
            "$clean (duo)",
        )
        for (q in qualifiers) {
            val cand = fetchPageSummary(q)
            if (cand != null && cand.isMusic) {
                return ArtistInfo(bio = cand.bio, imageUrl = cand.imageUrl)
            }
        }

        // 3. Fallback to OpenSearch prefix suggestions.
        try {
            val q = URLEncoder.encode(clean, "UTF-8")
            val conn = URL("https://en.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=5&namespace=0&format=json")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val titles = try {
                if (conn.responseCode == 200) {
                    // R3-15: cap the body so a hostile/corrupt response can't exhaust memory.
                    val arr = JSONArray(conn.inputStream.use { it.readNBytes(2 * 1024 * 1024) }.decodeToString())
                    val list = arr.optJSONArray(1)
                    if (list != null) {
                        (0 until list.length()).mapNotNull { list.optString(it) }
                    } else emptyList()
                } else emptyList()
            } finally {
                conn.disconnect()
            }

            for (t in titles) {
                if (t.equals(clean, ignoreCase = true)) continue
                val cand = fetchPageSummary(t)
                if (cand != null && cand.isMusic) {
                    return ArtistInfo(bio = cand.bio, imageUrl = cand.imageUrl)
                }
            }
        } catch (_: Exception) { }

        // 4. Fallback to direct match if it exists, even if music keywords didn't trigger
        if (direct != null && (direct.bio != null || direct.imageUrl != null)) {
            return ArtistInfo(bio = direct.bio, imageUrl = direct.imageUrl)
        }

        return null
    }
}
