package com.cadence.music.data.metadata

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SyncedLine(val timeMs: Long, val text: String)

object LrcLib {

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
        return try {
            val q = buildString {
                append("https://lrclib.net/api/get?")
                append("artist_name=${URLEncoder.encode(artist, "UTF-8")}")
                append("&track_name=${URLEncoder.encode(title, "UTF-8")}")
                if (durationSec > 0) append("&duration=$durationSec")
            }
            val conn = URL(q).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Cadence/0.1 ( https://github.com/MDaV05/cadence )")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return emptyList()
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val lrc = json.optString("syncedLyrics").ifBlank { null } ?: return emptyList()
                parse(lrc)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { emptyList() }
    }
}
