package com.cadence.music.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val url: String)
data class ReleaseInfo(val tag: String, val htmlUrl: String?, val assets: List<ReleaseAsset>)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
    data class Available(val tag: String, val assetUrl: String, val notesUrl: String?) : UpdateStatus
    data class Failed(val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
}

/** Numeric segments pairwise; first difference decides; unparseable → false (fail safe). */
fun isNewerTag(tag: String, installed: String): Boolean {
    fun parts(s: String): List<Int>? {
        val nums = s.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit) }
        if (nums.any { it.isEmpty() }) return null
        return nums.map { it.toIntOrNull() ?: return null }
    }
    val a = parts(tag) ?: return false
    val b = parts(installed) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}

fun pickApkAsset(assets: List<ReleaseAsset>, tag: String): ReleaseAsset? =
    assets.firstOrNull { it.name == "cadence-$tag-release.apk" }

/** Thin Android shell (HTTP + org.json) — covered by build, not unit tests. */
suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("https://api.github.com/repos/MDaV05/cadence/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Cadence")
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            if (root.optBoolean("prerelease")) return@runCatching null
            val arr = root.optJSONArray("assets") ?: return@runCatching null
            val assets = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReleaseAsset(o.optString("name"), o.optString("browser_download_url"))
            }
            ReleaseInfo(root.getString("tag_name"), root.optString("html_url", null), assets)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
