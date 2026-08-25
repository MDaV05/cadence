package com.cadence.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cadence.music.data.source.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    // Player.REPEAT_MODE_OFF / _ALL / _ONE
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

class PlayerConnection(
    context: Context,
    private val lbTokenProvider: () -> String? = { null },
    // Builds fresh authenticated stream URLs for server tracks at play time;
    // stream URLs are not persisted in the DB (they carry per-request tokens).
    private val resolveStreamUrl: suspend (Track) -> String? = { null },
    // Called with the mediaId of each track as playback transitions to it.
    private val onTrackPlayed: suspend (String) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    /** Milliseconds left on the sleep timer, or null when inactive. */
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs
    private var sleepJob: kotlinx.coroutines.Job? = null

    var controller: MediaController? = null
        private set

    init {
        scope.launch {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            controller = MediaController.Builder(appContext, token).buildAsync().await()
            controller?.let { c ->
                _state.value = _state.value.copy(
                    shuffle = c.shuffleModeEnabled,
                    repeatMode = c.repeatMode,
                )
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(isPlaying = isPlaying)
                    }

                    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                        _state.value = _state.value.copy(shuffle = enabled)
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _state.value = _state.value.copy(repeatMode = repeatMode)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                        val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                        val album = mediaItem?.mediaMetadata?.albumTitle?.toString()
                        _state.value = _state.value.copy(title = title, artist = artist)
                        mediaItem?.mediaId?.let { key ->
                            scope.launch { runCatching { onTrackPlayed(key) } }
                        }
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
    }

    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        scope.launch {
            val items = tracks.mapNotNull { it.toMediaItem() }
            if (items.isEmpty()) return@launch
            c.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0)
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

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Cycles repeat OFF -> ALL -> ONE -> OFF. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Pauses playback after [minutes]; shows remaining time via [sleepRemainingMs]. */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepJob = scope.launch {
            val endAt = System.currentTimeMillis() + minutes * 60_000L
            while (true) {
                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) break
                _sleepRemainingMs.value = remaining
                delay(1_000)
            }
            _sleepRemainingMs.value = null
            controller?.pause()
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
    }

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

    /** Null when the track has no playable URI (skipped from the queue instead of crashing). */
    private suspend fun Track.toMediaItem(): MediaItem? {
        val uri = localPath ?: resolveStreamUrl(this) ?: return null
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
}
