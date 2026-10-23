package com.cadence.music.data.source.online

import android.util.Log
import com.cadence.music.data.source.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class SoundCloudSource(
    private val client: OkHttpClient = OkHttpClient(),
) : OnlineSource {

    override val sourceId: String = "soundcloud"
    override val displayName: String = "SoundCloud"

    @Volatile
    private var clientId: String = DEFAULT_CLIENT_ID

    private fun getOrRefreshClientId(forceRefresh: Boolean = false): String {
        if (!forceRefresh && clientId.isNotBlank()) return clientId
        synchronized(this) {
            if (!forceRefresh && clientId.isNotBlank()) return clientId
            val resolved = fetchClientId()
            if (!resolved.isNullOrBlank()) {
                clientId = resolved
            } else if (clientId.isBlank()) {
                clientId = DEFAULT_CLIENT_ID
            }
            return clientId
        }
    }

    private fun fetchClientId(): String? {
        return runCatching {
            val homeReq = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", USER_AGENT)
                .build()

            val html = client.newCall(homeReq).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }

            // Find script URLs: src="https://a-v2.sndcdn.com/assets/...js"
            val scriptRegex = Regex("""https://a-v2\.sndcdn\.com/assets/[^"']+\.js""")
            val scriptUrls = scriptRegex.findAll(html).map { it.value }.toList()
            if (scriptUrls.isEmpty()) return null

            // Search the scripts in reverse order (app bundle usually near the end)
            for (scriptUrl in scriptUrls.reversed()) {
                val scriptReq = Request.Builder()
                    .url(scriptUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val js = client.newCall(scriptReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: continue

                val idMatch = Regex("""client_id:\s*"([a-zA-Z0-9]{32})"""").find(js)
                    ?: Regex("""client_id=([a-zA-Z0-9]{32})""").find(js)
                if (idMatch != null) {
                    val foundId = idMatch.groupValues[1]
                    Log.d(TAG, "Resolved dynamic SoundCloud clientId: $foundId")
                    return foundId
                }
            }
            null
        }.getOrNull()
    }

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            var currentId = getOrRefreshClientId()
            var url = "https://api-v2.soundcloud.com/search/tracks?q=$encodedQuery&client_id=$currentId&limit=25"

            var req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            var resp = client.newCall(req).execute()

            if (resp.code in listOf(401, 403)) {
                resp.close()
                currentId = getOrRefreshClientId(forceRefresh = true)
                url = "https://api-v2.soundcloud.com/search/tracks?q=$encodedQuery&client_id=$currentId&limit=25"
                req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                resp = client.newCall(req).execute()
            }

            resp.use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "SoundCloud search failed with HTTP ${response.code}")
                    return@runCatching emptyList()
                }
                val bodyStr = response.body?.string() ?: return@runCatching emptyList()
                parseSearchResults(bodyStr)
            }
        }.onFailure {
            Log.e(TAG, "SoundCloud search failed for '$query': ${it.message}", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveStreamUrl(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            var currentId = getOrRefreshClientId()
            var trackUrl = "https://api-v2.soundcloud.com/tracks/$key?client_id=$currentId"
            var trackReq = Request.Builder().url(trackUrl).header("User-Agent", USER_AGENT).build()
            var trackResp = client.newCall(trackReq).execute()

            if (trackResp.code in listOf(401, 403)) {
                trackResp.close()
                currentId = getOrRefreshClientId(forceRefresh = true)
                trackUrl = "https://api-v2.soundcloud.com/tracks/$key?client_id=$currentId"
                trackReq = Request.Builder().url(trackUrl).header("User-Agent", USER_AGENT).build()
                trackResp = client.newCall(trackReq).execute()
            }

            val transcodingEndpoint = trackResp.use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "SoundCloud track info failed with HTTP ${response.code}")
                    return@runCatching null
                }
                val bodyStr = response.body?.string() ?: return@runCatching null
                parseTranscodingUrl(bodyStr)
            } ?: return@runCatching null

            val delim = if (transcodingEndpoint.contains("?")) "&" else "?"
            val streamEndpointUrl = "$transcodingEndpoint${delim}client_id=$currentId"

            val streamReq = Request.Builder().url(streamEndpointUrl).header("User-Agent", USER_AGENT).build()
            val streamResp = client.newCall(streamReq).execute()

            streamResp.use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "SoundCloud stream resolution failed with HTTP ${response.code}")
                    return@runCatching null
                }
                val bodyStr = response.body?.string() ?: return@runCatching null
                parseMediaUrl(bodyStr)
            }
        }.onFailure {
            Log.e(TAG, "SoundCloud resolveStreamUrl failed for key '$key': ${it.message}", it)
        }.getOrNull()
    }

    override suspend fun coverArtUrl(key: String): String? = null

    companion object {
        private const val TAG = "SoundCloudSource"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val DEFAULT_CLIENT_ID = "iZIs9mchVcX5lhVRdekQWmUAUISRa1io"

        fun parseSearchResults(json: String): List<Track> {
            val results = mutableListOf<Track>()
            val root = JSONObject(json)
            val collection = root.optJSONArray("collection") ?: return emptyList()

            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val id = item.optLong("id", -1L)
                if (id <= 0) continue

                val title = item.optString("title").trim()
                if (title.isBlank()) continue

                val userObj = item.optJSONObject("user")
                val artist = userObj?.optString("username")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "SoundCloud"

                val duration = item.optLong("duration", 0L)
                var artworkUrl = item.optString("artwork_url").takeUnless { it.isNullOrBlank() || it == "null" }
                if (artworkUrl == null) {
                    artworkUrl = userObj?.optString("avatar_url").takeUnless { it.isNullOrBlank() || it == "null" }
                }
                val highResArt = artworkUrl?.replace("-large.", "-t500x500.")

                results.add(
                    Track(
                        key = id.toString(),
                        sourceId = "soundcloud",
                        title = title,
                        artist = artist,
                        album = "SoundCloud",
                        albumKey = highResArt,
                        durationMs = duration,
                        localPath = null,
                    )
                )
            }
            return results
        }

        fun parseTranscodingUrl(json: String): String? {
            val root = JSONObject(json)
            val media = root.optJSONObject("media") ?: return null
            val transcodings = media.optJSONArray("transcodings") ?: return null

            var progressiveUrl: String? = null
            var hlsUrl: String? = null

            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val url = t.optString("url")
                if (url.isBlank()) continue

                val format = t.optJSONObject("format")
                val protocol = format?.optString("protocol").orEmpty()

                if (protocol.equals("progressive", ignoreCase = true)) {
                    progressiveUrl = url
                    break
                } else if (protocol.equals("hls", ignoreCase = true) && hlsUrl == null) {
                    hlsUrl = url
                }
            }
            return progressiveUrl ?: hlsUrl
        }

        fun parseMediaUrl(json: String): String? {
            val root = JSONObject(json)
            val url = root.optString("url")
            return url.takeIf { it.isNotBlank() }
        }
    }
}
