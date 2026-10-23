package com.cadence.music.data.metadata

import org.json.JSONArray
import org.json.JSONObject

data class SyncedLine(val timeMs: Long, val text: String)

object LrcLib {

    /** Serializes lines back to .lrc text so they can be cached and re-parsed. */
    fun toLrcText(lines: List<SyncedLine>): String = lines.joinToString("\n") { l ->
        val m = l.timeMs / 60_000
        val rem = l.timeMs % 60_000
        "[%02d:%02d.%03d]".format(m, rem / 1000, rem % 1000) + l.text
    }

    /** Finds the active lyric line index for a given playback position in milliseconds. */
    fun activeLineIndex(lines: List<SyncedLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < 0) return -1
        return lines.indexOfLast { it.timeMs <= positionMs }.takeIf { it >= 0 } ?: -1
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
                append("artist_name=${MetadataHttpClient.encode(artist)}")
                append("&track_name=${MetadataHttpClient.encode(title)}")
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
                append("artist_name=${MetadataHttpClient.encode(artist)}")
                append("&track_name=${MetadataHttpClient.encode(title)}")
            }
            val res = MetadataHttpClient.get(q)
            if (res.code != 200 || res.body.isNullOrBlank()) return emptyList()
            val results = JSONArray(res.body)
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
        val res = MetadataHttpClient.get(url)
        if (res.code != 200 || res.body.isNullOrBlank()) return emptyList()
        val json = runCatching { JSONObject(res.body) }.getOrNull() ?: return emptyList()
        if (json.optString("type") != "standard" && json.optString("syncedLyrics").isBlank()) {
            return emptyList()
        }
        val lrc = json.optString("syncedLyrics").ifBlank { null } ?: return emptyList()
        return parse(lrc)
    }
}
