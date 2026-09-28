package com.cadence.music.data.source.bale

import android.util.Log
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.source.Album
import com.cadence.music.data.source.MusicSource
import com.cadence.music.data.source.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class BaleSource(
    private val entry: ServerEntry,
) : MusicSource {

    override val id: String = entry.id

    private val baseUrl: String = entry.url.ifBlank { "https://tapi.bale.ai" }.trim().removeSuffix("/")
    private val token: String = (entry.token ?: entry.password).orEmpty().trim()
    private val targetChat: String = entry.user.trim()

    private val albumCache = ConcurrentHashMap<String, List<Track>>()
    private val fileUrlCache = ConcurrentHashMap<String, String>()

    private fun httpGet(path: String): JSONObject? {
        if (token.isBlank()) return null
        return runCatching {
            val endpoint = "$baseUrl/bot$token/$path"
            val conn = URI(endpoint).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(text)
                } else {
                    Log.w(TAG, "Bale API error: HTTP ${conn.responseCode} on $path")
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            Log.e(TAG, "Bale API request failed on $path: ${it.message}", it)
        }.getOrNull()
    }

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val res = httpGet("getMe")
        res?.optBoolean("ok", false) == true
    }

    override suspend fun listAlbums(): List<Album> = withContext(Dispatchers.IO) {
        albumCache.clear()
        val updates = httpGet("getUpdates?limit=100") ?: return@withContext emptyList()
        val resultArr = updates.optJSONArray("result") ?: return@withContext emptyList()

        val chatTracks = mutableMapOf<String, MutableList<Track>>()
        val chatTitles = mutableMapOf<String, String>()

        for (i in 0 until resultArr.length()) {
            val item = resultArr.optJSONObject(i) ?: continue
            val msg = item.optJSONObject("message")
                ?: item.optJSONObject("channel_post")
                ?: item.optJSONObject("edited_message")
                ?: item.optJSONObject("edited_channel_post")
                ?: continue

            val chat = msg.optJSONObject("chat") ?: continue
            val chatId = chat.optString("id", "")
            val chatTitle = chat.optString("title", chat.optString("username", "Bale Music Channel"))
            if (chatId.isBlank()) continue
            chatTitles[chatId] = chatTitle

            val audio = msg.optJSONObject("audio")
                ?: msg.optJSONObject("voice")
                ?: msg.optJSONObject("document")?.takeIf {
                    it.optString("mime_type", "").startsWith("audio/")
                } ?: continue

            val fileId = audio.optString("file_id", "")
            if (fileId.isBlank()) continue

            val title = audio.optString("title").ifBlank {
                audio.optString("file_name", "Track ${msg.optLong("message_id")}").substringBeforeLast('.')
            }
            val performer = audio.optString("performer").ifBlank { chatTitle }
            val duration = audio.optLong("duration", 0L)
            val albumKey = "bale:chat:$chatId"

            val track = Track(
                key = "bale:$fileId",
                sourceId = id,
                title = title,
                artist = performer,
                album = chatTitle,
                albumKey = albumKey,
                durationMs = duration * 1000L,
                localPath = null,
                streamUrl = null,
            )
            chatTracks.getOrPut(albumKey) { mutableListOf() }.add(track)
        }

        // If targetChat is specified, ensure it is represented
        if (targetChat.isNotBlank()) {
            val directChat = httpGet("getChat?chat_id=$targetChat")
            val chatObj = directChat?.optJSONObject("result")
            if (chatObj != null) {
                val cId = chatObj.optString("id", targetChat)
                val cTitle = chatObj.optString("title", chatObj.optString("username", targetChat))
                val aKey = "bale:chat:$cId"
                chatTitles[cId] = cTitle
                if (!chatTracks.containsKey(aKey)) {
                    chatTracks[aKey] = mutableListOf()
                }
            }
        }

        val albums = mutableListOf<Album>()
        for ((albumKey, tracks) in chatTracks) {
            val chatId = albumKey.removePrefix("bale:chat:")
            val title = chatTitles[chatId] ?: "Bale Channel"
            albumCache[albumKey] = tracks
            albums += Album(
                key = albumKey,
                sourceId = id,
                title = title,
                artist = "Bale",
                year = null,
                remoteCreated = null,
            )
        }
        albums
    }

    override suspend fun albumTracksByKey(albumKey: String): List<Track> {
        albumCache[albumKey]?.let { return it }
        listAlbums()
        return albumCache[albumKey] ?: emptyList()
    }

    override suspend fun streamUrl(track: Track): String? = withContext(Dispatchers.IO) {
        val fileId = track.key.removePrefix("bale:")
        fileUrlCache[fileId]?.let { return@withContext it }

        val res = httpGet("getFile?file_id=$fileId") ?: return@withContext null
        val filePath = res.optJSONObject("result")?.optString("file_path", "")
        if (!filePath.isNullOrBlank()) {
            val directUrl = "$baseUrl/file/bot$token/$filePath"
            fileUrlCache[fileId] = directUrl
            directUrl
        } else null
    }

    override suspend fun scan(): List<Track> {
        return listAlbums().flatMap { albumTracksByKey(it.key) }
    }

    override fun downloadUrl(songId: String, format: String, bitrate: Int): String? {
        val fileId = songId.removePrefix("bale:")
        return fileUrlCache[fileId]
    }

    companion object {
        private const val TAG = "BaleSource"
    }
}
