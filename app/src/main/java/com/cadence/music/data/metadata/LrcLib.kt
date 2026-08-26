package com.cadence.music.data.metadata

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SyncedLine(val timeMs: Long, val text: String)

object LrcLib {

    /** Serializes lines back to .lrc text so they can be cached and re-parsed. */
    fun toLrcText(lines: List<SyncedLine>): String = lines.joinToString("\n") { l ->
        val m = l.timeMs / 60_000
        val rem = l.timeMs % 60_000
        "[%02d:%02d.%03d]".format(m, rem / 1000, rem % 1000) + l.text
    }

    fun parse(lrc: String): List<SyncedLine> {
        val out = mutableListOf<SyncedLine>()
        for (line in lrc.lines()) {
            val m = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?](.*)""").find(line) ?: continue
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3].padEnd(3, '0').ifEmpty { "0" }.toLong()
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) out += SyncedLine(min * 60_000 + sec * 1000 + frac, text)
        }
        return out.sortedBy { it.timeMs }
    }

    /** Returns parsed synced lyrics, or empty list if none found. */
    fun fetchBlocking(artist: String, title: String, durationSec: Long): List<SyncedLine> {
        val exact = try {
            val q = buildString {
                append("https://lrclib.net/api/get?")
                append("artist_name=${URLEncoder.encode(artist, "UTF-8")}")
                append("&track_name=${URLEncoder.encode(title, "UTF-8")}")
                if (durationSec > 0) append("&duration=$durationSec")
            }
            getLrcJson(q)
        } catch (_: Exception) {
            emptyList()
        }
        if (exact.isNotEmpty()) return exact
        // Exact match misses on fuzzy titles; the search endpoint usually finds them.
        return try {
            val q = buildString {
                append("https://lrclib.net/api/search?")
                append("artist_name=${URLEncoder.encode(artist, "UTF-8")}")
                append("&track_name=${URLEncoder.encode(title, "UTF-8")}")
            }
            val body = httpGet(q)
            val results = JSONArray(body)
            for (i in 0 until results.length()) {
                val lrc = results.optJSONObject(i)?.optString("syncedLyrics").orEmpty()
                if (lrc.isNotBlank()) return parse(lrc)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getLrcJson(url: String): List<SyncedLine> {
        val body = httpGet(url)
        val json = JSONObject(body)
        if (json.optString("type") != "standard" && json.optString("syncedLyrics").isBlank()) {
            return emptyList()
        }
        val lrc = json.optString("syncedLyrics").ifBlank { null } ?: return emptyList()
        return parse(lrc)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Cadence/0.1 ( https://github.com/MDaV05/cadence )")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) return ""
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
