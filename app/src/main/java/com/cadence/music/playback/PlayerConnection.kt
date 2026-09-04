package com.cadence.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cadence.music.data.source.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Submits one listen; implementers queue it when offline. Called after the
    // listen threshold so skips never reach it.
    private val submitScrobble: suspend (artist: String, title: String, album: String?) -> Unit = { _, _, _ -> },
    // Builds fresh authenticated stream URLs for server tracks at play time;
    // stream URLs are not persisted in the DB (they carry per-request tokens).
    private val resolveStreamUrl: suspend (Track) -> String? = { null },
    // Called with the mediaId of each track once it counts as "listened".
    private val onTrackPlayed: suspend (String) -> Unit = {},
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, _ -> /* playback glue must never crash the app */ }
    )
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    /** Milliseconds left on the sleep timer, or null when inactive. */
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs
    private var sleepJob: Job? = null

    // Live queue snapshot driven by controller timeline events, so UI observes
    // changes instead of re-reading the controller on every recomposition.
    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queueItems: StateFlow<List<MediaItem>> = _queue
    private val _queueIndex = MutableStateFlow(0)
    val queueIndexFlow: StateFlow<Int> = _queueIndex

    // Pending "completed listen" for the current item. Cancelled when the user
    // skips before the threshold so skipped tracks never scrobble or count.
    private var listenJob: Job? = null
    private var pendingListenKey: String? = null

    var controller: MediaController? = null
        private set

    init {
        scope.launch {
            val c = try {
                val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                MediaController.Builder(appContext, token).buildAsync().await()
            } catch (_: Exception) {
                return@launch // service unavailable; stay inert rather than crash at startup
            }
            controller = c
            // Reconnects (e.g. process restart while the service plays on) get
            // no transition event — seed the state from the current item.
            val current = c.currentMediaItem
            _state.value = _state.value.copy(
                title = current?.mediaMetadata?.title?.toString() ?: "",
                artist = current?.mediaMetadata?.artist?.toString() ?: "",
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
            )
            refreshQueue(c)
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
                    _state.value = _state.value.copy(title = title, artist = artist)
                    scheduleListen(mediaItem)
                    refreshQueue(c)
                }

                override fun onPositionDiscontinuity(
                    newPosition: Player.PositionInfo,
                    oldPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // Repeat-one replays the same item with no transition event:
                    // an auto transition inside one item is a completed loop.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                        newPosition.mediaItem?.mediaId != null &&
                        newPosition.mediaItem?.mediaId == pendingListenKey
                    ) {
                        finishListenNow()
                        scheduleListen(c.currentMediaItem)
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    refreshQueue(c)
                }
            })
        }
    }

    /** Arms the listen timer; a track only scrobbles/counts after [threshold]. */
    private fun scheduleListen(item: MediaItem?) {
        listenJob?.cancel()
        listenJob = null
        val key = item?.mediaId
        pendingListenKey = key
        val c = controller ?: return
        if (item == null || key == null) return
        listenJob = scope.launch {
            // Transition fires before prepare finishes, so duration isn't known
            // yet — wait for it (or fall back to the 4-minute cap) rather than
            // collapsing the threshold to its minimum.
            var durMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            var waitedMs = 0L
            while (durMs == 0L && waitedMs < DURATION_WAIT_MS) {
                delay(250)
                waitedMs += 250
                durMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            }
            if (c.currentMediaItem?.mediaId != key) return@launch
            // Scrobble rule: half the track or 4 minutes, whichever comes first.
            // ponytail: capped by duration so sub-second tracks still count.
            val thresholdMs = minOf(
                if (durMs > 0) durMs / 2 else SCROBBLE_MAX_MS,
                SCROBBLE_MAX_MS,
            ).coerceAtLeast(MIN_LISTEN_MS)
                .coerceAtMost(durMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            delay(thresholdMs)
            if (c.currentMediaItem?.mediaId != key) return@launch
            listenJob = null
            completeListen(item)
        }
    }

    /** Counts an already-completed play immediately (repeat-one loop). */
    private fun finishListenNow() {
        listenJob?.cancel()
        listenJob = null
        val item = controller?.currentMediaItem ?: return
        scope.launch { completeListen(item) }
    }

    private suspend fun completeListen(item: MediaItem) {
        runCatching { onTrackPlayed(item.mediaId) }
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val album = item.mediaMetadata.albumTitle?.toString()
        if (title.isNotBlank() && artist.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { submitScrobble(artist, title, album) }
            }
        }
    }

    private fun refreshQueue(c: MediaController) {
        _queue.value = List(c.mediaItemCount) { c.getMediaItemAt(it) }
        _queueIndex.value = c.currentMediaItemIndex
    }

    fun playNow(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        scope.launch {
            if (tracks.isEmpty()) return@launch
            val idx = startIndex.coerceIn(0, tracks.size - 1)
            // Resolve the chosen track first so the tapped song always leads,
            // even when resolution of other items fails.
            val startItem = tracks[idx].toMediaItem() ?: return@launch
            val rest = tracks.mapIndexedNotNull { i, t -> if (i == idx) null else t.toMediaItem() }
            c.setMediaItems(listOf(startItem) + rest, 0, 0)
            c.prepare()
            c.play()
        }
    }

    fun shuffleAll(tracks: List<Track>) {
        playNow(tracks.shuffled(), 0)
    }

    /** Inserts a track right after the current one without disturbing the rest. */
    fun playNext(track: Track) {
        val c = controller ?: return
        scope.launch {
            val item = track.toMediaItem() ?: return@launch
            c.addMediaItem(c.currentMediaItemIndex + 1, item)
        }
    }

    /** Appends a track to the end of the queue. */
    fun addToQueue(track: Track) {
        val c = controller ?: return
        scope.launch {
            val item = track.toMediaItem() ?: return@launch
            c.addMediaItem(item)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        controller?.moveMediaItem(from, to.coerceIn(0, (controller?.mediaItemCount ?: 1) - 1))
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

    fun jumpTo(index: Int) {
        controller?.seekToDefaultPosition(index)
        controller?.play()
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun release() {
        listenJob?.cancel()
        cancelSleepTimer()
        controller?.release()
        controller = null
        scope.cancel()
    }

    /** Null when the track has no playable URI (skipped from the queue instead of crashing). */
    private suspend fun Track.toMediaItem(): MediaItem? {
        val uri = localPath ?: runCatching { resolveStreamUrl(this) }.getOrNull() ?: return null
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

    private companion object {
        const val SCROBBLE_MAX_MS = 240_000L
        const val MIN_LISTEN_MS = 1_000L
        const val DURATION_WAIT_MS = 5_000L
    }
}
