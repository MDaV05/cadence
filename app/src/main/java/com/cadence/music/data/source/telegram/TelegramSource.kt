package com.cadence.music.data.source.telegram

import android.content.Context
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.source.Album
import com.cadence.music.data.source.MusicSource
import com.cadence.music.data.source.Track
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

class TelegramSource(
    private val context: Context,
    private val entry: ServerEntry,
) : MusicSource {

    override val id: String = "telegram"

    private val manager = TelegramManager.get(context)
    private val proxy = TelegramStreamProxy.get(context)

    // Cache of fetched audio messages by album key
    private val albumCache = ConcurrentHashMap<String, List<Track>>()

    private fun targetChatId(): Long {
        val trimmed = entry.url.trim()
        if (trimmed.isBlank() || trimmed.equals("me", ignoreCase = true) || trimmed.equals("saved", ignoreCase = true)) {
            // "me" / Saved Messages: in Telegram, user's own chat id equals their user id
            return entry.userId?.toLongOrNull() ?: 0L
        }
        return trimmed.toLongOrNull() ?: 0L
    }

    override suspend fun ping(): Boolean {
        return manager.isReady()
    }

    override suspend fun listAlbums(): List<Album> {
        if (!manager.isReady()) return emptyList()

        val chatId = targetChatId()
        val messages = manager.getAudioMessages(chatId, maxCount = 1000)
        albumCache.clear()

        val grouped = mutableMapOf<String, MutableList<Track>>()

        for (msg in messages) {
            val content = msg.content
            if (content !is TdApi.MessageAudio) continue
            val audio = content.audio
            val remoteId = audio.audio?.remote?.id ?: continue

            val title = audio.title.ifBlank {
                audio.fileName.ifBlank { "Track ${msg.id}" }.substringBeforeLast('.')
            }
            val artist = audio.performer.ifBlank { "Telegram Cloud" }
            val albumName = entry.user.ifBlank { "Telegram Music" }
            val albumKey = "tg:chat:$chatId"

            val track = Track(
                key = "tg:$remoteId",
                sourceId = id,
                title = title,
                artist = artist,
                album = albumName,
                albumKey = albumKey,
                durationMs = audio.duration * 1000L,
                localPath = null,
                streamUrl = proxy.streamUrl(remoteId),
            )

            grouped.getOrPut(albumKey) { mutableListOf() }.add(track)
        }

        val albums = mutableListOf<Album>()
        for ((key, tracks) in grouped) {
            albumCache[key] = tracks
            albums += Album(
                key = key,
                sourceId = id,
                title = entry.user.ifBlank { "Telegram Music" },
                artist = "Various Artists",
                year = null,
                remoteCreated = null,
            )
        }
        return albums
    }

    override suspend fun albumTracksByKey(albumKey: String): List<Track> {
        albumCache[albumKey]?.let { return it }
        listAlbums()
        return albumCache[albumKey] ?: emptyList()
    }

    override suspend fun streamUrl(track: Track): String? {
        val remoteId = track.key.removePrefix("tg:")
        return proxy.streamUrl(remoteId)
    }

    override fun downloadUrl(songId: String, format: String, bitrate: Int): String? {
        val remoteId = songId.removePrefix("tg:")
        return proxy.streamUrl(remoteId)
    }

    override suspend fun coverArtUrl(albumKey: String): String? = null
}
