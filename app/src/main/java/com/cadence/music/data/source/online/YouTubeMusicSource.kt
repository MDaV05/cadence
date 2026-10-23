package com.cadence.music.data.source.online

import android.util.Log
import com.cadence.music.data.source.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class YouTubeMusicSource(
    private val client: OkHttpClient = OkHttpClient(),
) : OnlineSource {

    override val sourceId: String = "ytmusic"
    override val displayName: String = "YouTube Music"

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230522.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D")
            }.toString()

            val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(body)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.youtube.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search request failed with HTTP ${response.code}")
                    return@runCatching emptyList()
                }
                val bodyStr = response.body?.string() ?: return@runCatching emptyList()
                parseSearchResults(bodyStr)
            }
        }.onFailure {
            Log.e(TAG, "YouTube Music search failed for '$query': ${it.message}", it)
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveStreamUrl(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // First attempt with IOS client
            val iosPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", key)
            }.toString()

            val iosReq = Request.Builder()
                .url(PLAYER_URL)
                .post(iosPayload.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.youtube.com/")
                .build()

            client.newCall(iosReq).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val directUrl = parseStreamUrl(bodyStr)
                        if (!directUrl.isNullOrBlank()) return@withContext directUrl
                    }
                }
            }

            // Fallback to ANDROID_MUSIC client
            val androidPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "6.43.52")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", key)
            }.toString()

            val androidReq = Request.Builder()
                .url(PLAYER_URL)
                .post(androidPayload.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.youtube.com/")
                .build()

            client.newCall(androidReq).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        return@withContext parseStreamUrl(bodyStr)
                    }
                }
            }
            null
        }.onFailure {
            Log.e(TAG, "YouTube Music resolveStreamUrl failed for '$key': ${it.message}", it)
        }.getOrNull()
    }

    override suspend fun coverArtUrl(key: String): String = "https://i.ytimg.com/vi/$key/hqdefault.jpg"

    companion object {
        private const val TAG = "YouTubeMusicSource"
        private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun parseSearchResults(json: String): List<Track> {
            val results = mutableListOf<Track>()
            val root = JSONObject(json)

            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            for (t in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(t) ?: continue
                val sectionContents = tab.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (s in 0 until sectionContents.length()) {
                    val section = sectionContents.optJSONObject(s) ?: continue
                    val shelfContents = section.optJSONObject("musicShelfRenderer")
                        ?.optJSONArray("contents") ?: continue

                    for (i in 0 until shelfContents.length()) {
                        val item = shelfContents.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        var videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                        if (videoId.isBlank()) {
                            videoId = item.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty()
                        }

                        val flexColumns = item.optJSONArray("flexColumns") ?: continue
                        if (flexColumns.length() == 0) continue

                        val col0 = flexColumns.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val runs0 = col0?.optJSONObject("text")?.optJSONArray("runs")
                        val title = runs0?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
                        if (title.isBlank()) continue

                        if (videoId.isBlank() && runs0 != null && runs0.length() > 0) {
                            videoId = runs0.optJSONObject(0)
                                ?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty()
                        }
                        if (videoId.isBlank()) continue

                        var artist = "YouTube Music"
                        var album = "YouTube Music"
                        var durationMs = 0L

                        if (flexColumns.length() > 1) {
                            val col1 = flexColumns.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val runs1 = col1?.optJSONObject("text")?.optJSONArray("runs")
                            if (runs1 != null) {
                                val parts = mutableListOf<String>()
                                for (r in 0 until runs1.length()) {
                                    val text = runs1.optJSONObject(r)?.optString("text")?.trim().orEmpty()
                                    if (text.isNotEmpty() && text != "•") {
                                        parts.add(text)
                                    }
                                }
                                if (parts.isNotEmpty()) {
                                    artist = parts[0]
                                    val remaining = parts.drop(1)
                                    if (remaining.isNotEmpty()) {
                                        val last = remaining.last()
                                        val parsedDuration = parseDuration(last)
                                        if (parsedDuration > 0) {
                                            durationMs = parsedDuration
                                            val middle = remaining.dropLast(1)
                                            if (middle.isNotEmpty()) {
                                                album = middle.joinToString(" • ")
                                            }
                                        } else {
                                            album = remaining.joinToString(" • ")
                                        }
                                    }
                                }
                            }
                        }

                        var coverUrl: String? = null
                        val thumbs = item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        if (thumbs != null && thumbs.length() > 0) {
                            var maxDim = -1
                            for (th in 0 until thumbs.length()) {
                                val thumb = thumbs.optJSONObject(th) ?: continue
                                val w = thumb.optInt("width", 0)
                                val h = thumb.optInt("height", 0)
                                val dim = maxOf(w, h)
                                if (dim >= maxDim) {
                                    maxDim = dim
                                    val u = thumb.optString("url")
                                    if (u.isNotBlank()) coverUrl = u
                                }
                            }
                            if (coverUrl == null) {
                                coverUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                            }
                        }
                        if (coverUrl.isNullOrBlank()) {
                            coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        }

                        results.add(
                            Track(
                                key = videoId,
                                sourceId = "ytmusic",
                                title = title,
                                artist = artist,
                                album = album,
                                albumKey = coverUrl,
                                durationMs = durationMs,
                                localPath = null,
                            )
                        )
                    }
                }
            }
            return results
        }

        fun parseStreamUrl(json: String): String? {
            val root = JSONObject(json)
            val streamingData = root.optJSONObject("streamingData") ?: return null
            val formats = streamingData.optJSONArray("adaptiveFormats")
                ?: streamingData.optJSONArray("formats")
                ?: return null

            var bestUrl: String? = null
            var bestBitrate = -1

            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                val mimeType = format.optString("mimeType")
                val isAudio = mimeType.startsWith("audio/") || mimeType.contains("audio")
                if (!isAudio) continue

                val url = format.optString("url")
                if (url.isBlank()) continue

                val bitrate = format.optInt("bitrate", format.optInt("averageBitrate", 0))
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = url
                }
            }

            if (bestUrl != null) return bestUrl

            // Fallback: any format with direct url if audio-only didn't have one
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                val url = format.optString("url")
                if (url.isNotBlank()) {
                    val bitrate = format.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = url
                    }
                }
            }
            return bestUrl
        }

        private fun parseDuration(text: String): Long {
            val parts = text.split(":").map { it.trim() }
            if (parts.size !in 2..3) return 0L
            return try {
                if (parts.size == 2) {
                    val m = parts[0].toLong()
                    val s = parts[1].toLong()
                    (m * 60 + s) * 1000L
                } else {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    (h * 3600 + m * 60 + s) * 1000L
                }
            } catch (_: NumberFormatException) {
                0L
            }
        }
    }
}
