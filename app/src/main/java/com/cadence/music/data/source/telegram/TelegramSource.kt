package com.cadence.music.data.source.telegram

import android.content.Context
import com.cadence.music.data.prefs.ServerEntry
import com.cadence.music.data.source.Album
import com.cadence.music.data.source.MusicSource
import com.cadence.music.data.source.Track
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses comma- or semicolon-separated chat IDs or 'me' into distinct Long chat IDs.
 */
fun parseTelegramChatIds(rawUrl: String, myUserId: Long?): List<Long> {
    val raw = rawUrl.trim()
    if (raw.isBlank() || raw.equals("me", ignoreCase = true) || raw.equals("saved", ignoreCase = true)) {
        return if (myUserId != null && myUserId != 0L) listOf(myUserId) else emptyList()
    }
    return raw.split(',', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { token ->
            if (token.equals("me", ignoreCase = true) || token.equals("saved", ignoreCase = true)) {
                if (myUserId != null && myUserId != 0L) myUserId else null
            } else {
                token.toLongOrNull()
            }
        }
        .distinct()
}

class TelegramSource(
    private val context: Context,
    private val entry: ServerEntry,
) : MusicSource {

    override val id: String = "telegram"

    private val manager = TelegramManager.get(context)
    private val proxy = TelegramStreamProxy.get(context)

    // Cache of fetched audio messages by album key
    private val albumCache = ConcurrentHashMap<String, List<Track>>()

    private suspend fun targetChatIds(): List<Long> {
        val myUserId = entry.userId?.toLongOrNull() ?: manager.getMyUserId()
        return parseTelegramChatIds(entry.url, myUserId)
    }

    override suspend fun ping(): Boolean {
        return manager.isReady()
    }

    override suspend fun listAlbums(): List<Album> {
        if (!manager.isReady()) return emptyList()

        val chatIds = targetChatIds()
        if (chatIds.isEmpty()) return emptyList()
        albumCache.clear()

        val albums = mutableListOf<Album>()

        for (chatId in chatIds) {
            val chatTitle = manager.getChatTitle(chatId) ?: if (chatId == entry.userId?.toLongOrNull()) "Saved Messages" else "Chat $chatId"
            val messages = manager.getAudioMessages(chatId, maxCount = 1000)
            val tracks = mutableListOf<Track>()

            for (msg in messages) {
                val content = msg.content
                if (content !is TdApi.MessageAudio) continue
                val audio = content.audio
                val remoteId = audio.audio?.remote?.id ?: continue

                val title = audio.title.ifBlank {
                    audio.fileName.ifBlank { "Track ${msg.id}" }.substringBeforeLast('.')
                }
                val artist = audio.performer.ifBlank { chatTitle }
                val albumKey = "tg:chat:$chatId"

                val track = Track(
                    key = "tg:$remoteId",
                    sourceId = id,
                    title = title,
                    artist = artist,
                    album = chatTitle,
                    albumKey = albumKey,
                    durationMs = audio.duration * 1000L,
                    localPath = null,
                    streamUrl = proxy.streamUrl(remoteId),
                )
                tracks += track
            }

            if (tracks.isNotEmpty()) {
                val albumKey = "tg:chat:$chatId"
                albumCache[albumKey] = tracks
                albums += Album(
                    key = albumKey,
                    sourceId = id,
                    title = chatTitle,
                    artist = "Telegram ($chatTitle)",
                    year = null,
                    remoteCreated = null,
                )
            }
        }
        return albums
    }

    override suspend fun albumTracksByKey(albumKey: String): List<Track> {
        albumCache[albumKey]?.let { return it }
        val chatId = albumKey.removePrefix("tg:chat:").toLongOrNull()
        if (chatId != null && manager.isReady()) {
            val chatTitle = manager.getChatTitle(chatId) ?: "Chat $chatId"
            val messages = manager.getAudioMessages(chatId, maxCount = 1000)
            val tracks = messages.mapNotNull { msg ->
                val content = msg.content as? TdApi.MessageAudio ?: return@mapNotNull null
                val audio = content.audio
                val remoteId = audio.audio?.remote?.id ?: return@mapNotNull null
                val title = audio.title.ifBlank {
                    audio.fileName.ifBlank { "Track ${msg.id}" }.substringBeforeLast('.')
                }
                val artist = audio.performer.ifBlank { chatTitle }
                Track(
                    key = "tg:$remoteId",
                    sourceId = id,
                    title = title,
                    artist = artist,
                    album = chatTitle,
                    albumKey = albumKey,
                    durationMs = audio.duration * 1000L,
                    localPath = null,
                    streamUrl = proxy.streamUrl(remoteId),
                )
            }
            albumCache[albumKey] = tracks
            return tracks
        }
        return emptyList()
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
