package com.cadence.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cadence.music.data.source.Track
import com.cadence.music.data.source.resolvePlayable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

class PlayerConnection(context: Context, private val lbTokenProvider: () -> String? = { null }) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    var controller: MediaController? = null
        private set

    init {
        scope.launch {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            controller = MediaController.Builder(appContext, token).buildAsync().await()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                    val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                    val album = mediaItem?.mediaMetadata?.albumTitle?.toString()
                    _state.value = _state.value.copy(title = title, artist = artist)
                    val token = lbTokenProvider()
                    if (token != null && title.isNotBlank() && artist.isNotBlank()) {
                        Thread {
                            com.cadence.music.data.metadata.ListenBrainz.submitBlocking(
                                token, artist, title, album,
                            )
                        }.start()
                    }
                }
            })
        }
    }

    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        scope.launch {
            c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    fun shuffleAll(tracks: List<Track>) {
        val shuffled = tracks.shuffled()
        playNow(shuffled, 0)
    }

    fun togglePlayPause() {
        controller?.takeIf { it.isPlaying }?.pause() ?: controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    val queue: List<MediaItem> get() = controller?.mediaItemCount?.let { n ->
        (0 until n).map { controller!!.getMediaItemAt(it) }
    } ?: emptyList()

    val queueIndex: Int get() = controller?.currentMediaItemIndex ?: 0

    fun jumpTo(index: Int) {
        controller?.seekToDefaultPosition(index)
        controller?.play()
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun release() {
        controller?.release()
        controller = null
    }
}

private suspend fun Track.toMediaItem(): MediaItem {
    val uri = resolvePlayable(this)
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(key)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        )
        .build()
}
