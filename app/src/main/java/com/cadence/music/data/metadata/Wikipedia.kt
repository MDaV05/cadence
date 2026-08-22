package com.cadence.music.data.metadata

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArtistInfo(val bio: String?, val imageUrl: String?)

object Wikipedia {

    private const val BASE = "https://en.wikipedia.org/api/rest_v1"

    fun summaryBlocking(title: String): ArtistInfo? {
        return try {
            val conn = URL("$BASE/page/summary/${URLEncoder.encode(title, "UTF-8")}").openConnection()
                    as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Cadence/0.1 ( https://github.com/MDaV05/cadence )")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                if (json.optString("type") != "standard") return null
                ArtistInfo(
                    bio = json.optString("extract").ifBlank { null },
                    imageUrl = json.optJSONObject("thumbnail")?.optString("source"),
                )
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { null }
    }

    /** Search Wikipedia for the best-matching article title, then summarize it. */
    fun artistInfoBlocking(name: String): ArtistInfo? = try {
        val q = URLEncoder.encode("$name musician band", "UTF-8")
        val conn = URL("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=1&format=json")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Cadence/0.1 ( https://github.com/MDaV05/cadence )")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val title = try {
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().readText())
                .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)
                ?.optString("title")
        } finally {
            conn.disconnect()
        }
        title?.let { summaryBlocking(it) }
    } catch (_: Exception) { null }
}
