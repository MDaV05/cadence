package com.cadence.music.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cadence.music.CadenceApp
import com.cadence.music.data.db.TrackEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var replayGainJob: kotlinx.coroutines.Job? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        EqManager.apply()
    }

    override fun onCreate() {
        super.onCreate()
        EqManager.init(this)
        getSharedPreferences("cadence", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        val container = (application as? CadenceApp)?.container
        val cacheBytes = container?.prefs?.cacheGb?.coerceIn(1, 20)?.let { it * 1024L * 1024 * 1024 }
            ?: 2L * 1024 * 1024 * 1024
        val upstream: DataSource.Factory = DefaultDataSource.Factory(this)
        val cached: DataSource.Factory = CacheDataSource.Factory()
            .setCache(StreamCache.get(applicationContext, cacheBytes))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        fun attachEq(sessionId: Int) = EqManager.attach(sessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = attachEq(audioSessionId)

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                pushWidget(mediaItem, player.isPlaying)
                applyReplayGain(mediaItem?.mediaId)
                saveResumeState(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushWidget(player.currentMediaItem, isPlaying)
                // Persist the position when playback stops so resumption
                // (media button after swipe-away/reboot) restores it.
                if (!isPlaying) saveResumeState(player)
            }
        })
        attachEq(player.audioSessionId)

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val pkg = controller.packageName
                // The session is exported; only let our own UI and platform media
                // surfaces (lock screen, Bluetooth, Android Auto) attach.
                val trusted = pkg == packageName || pkg in TRUSTED_CONTROLLER_PACKAGES
                return if (trusted) super.onConnect(session, controller)
                else MediaSession.ConnectionResult.reject()
            }

            /** Lets media buttons / Assistant resume playback after swipe-away or reboot. */
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                serviceScope.future { buildResumeQueue() }
        })
            .build()
    }

    private fun pushWidget(mediaItem: androidx.media3.common.MediaItem?, playing: Boolean) {
        PlayerWidget.push(
            this,
            mediaItem?.mediaMetadata?.title?.toString() ?: "",
            mediaItem?.mediaMetadata?.artist?.toString() ?: "",
            playing,
        )
    }

    private fun saveResumeState(player: Player) {
        val sp = getSharedPreferences("cadence", MODE_PRIVATE)
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) {
            sp.edit().remove("resume_ids").apply()
            return
        }
        val ids = JSONArray()
        for (i in 0 until player.mediaItemCount) ids.put(player.getMediaItemAt(i).mediaId)
        sp.edit()
            .putString("resume_ids", ids.toString())
            .putInt("resume_index", player.currentMediaItemIndex)
            .putLong("resume_position_ms", player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    /** Rebuilds the last queue with fresh stream URLs so the system can resume playback. */
    private suspend fun buildResumeQueue(): MediaSession.MediaItemsWithStartPosition {
        val container = (application as? CadenceApp)?.container
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val sp = getSharedPreferences("cadence", MODE_PRIVATE)
        val idsJson = sp.getString("resume_ids", null) ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val savedIndex = sp.getInt("resume_index", 0)
        val savedPosition = sp.getLong("resume_position_ms", 0L)

        val ids = JSONArray(idsJson)
        val items = mutableListOf<MediaItem>()
        var startIndex = 0
        for (i in 0 until ids.length()) {
            val entity = withContext(Dispatchers.IO) {
                runCatching { container.database.trackDao().byServerId(ids.optString(i)) }.getOrNull()
            } ?: continue
            if (i == savedIndex) startIndex = items.size
            entity.toMediaItem(container)?.let { items.add(it) }
        }
        if (items.isEmpty()) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        return MediaSession.MediaItemsWithStartPosition(items, startIndex.coerceIn(0, items.size - 1), savedPosition)
    }

    private suspend fun TrackEntity.toMediaItem(container: com.cadence.music.AppContainer): MediaItem? {
        val uri = path ?: runCatching {
            val track = com.cadence.music.data.source.Track(
                key = serverId, sourceId = sourceId, title = title, artist = artistName,
                album = albumName, albumKey = albumKey, durationMs = durationMs, localPath = null,
            )
            container.subsonic.streamUrl(track)
        }.getOrNull() ?: return null
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(serverId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artistName)
                    .setAlbumTitle(albumName)
                    .build()
            )
            .build()
    }

    private fun applyReplayGain(mediaId: String?) {
        val p = getSharedPreferences("cadence", MODE_PRIVATE)
        if (!p.getBoolean("rg_enabled", false)) {
            replayGainJob?.cancel()
            session?.player?.volume = 1f
            return
        }
        if (mediaId == null) return
        val container = (application as? com.cadence.music.CadenceApp)?.container ?: return
        // Cancel any in-flight lookup so a slow stale read can't overwrite the
        // volume of a track that already transitioned.
        replayGainJob?.cancel()
        replayGainJob = serviceScope.launch {
            val rg = withContext(Dispatchers.IO) {
                runCatching { container.database.trackDao().byServerId(mediaId)?.replayGainDb }
                    .getOrNull()
            }
            val volume = rg?.let { Math.pow(10.0, it.toDouble() / 20.0).toFloat() }
                ?.coerceIn(0f, 1f) ?: 1f
            session?.player?.volume = volume
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        getSharedPreferences("cadence", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        EqManager.detach()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        // Platform components that legitimately bind a media session's controller.
        val TRUSTED_CONTROLLER_PACKAGES = setOf(
            "com.android.systemui",                     // lock screen / media resumption
            "com.android.bluetooth",                    // AVRCP (car + headsets)
            "com.google.android.projection.gearhead",   // Android Auto
            "com.google.android.wearable.app",          // Wear OS companion
            "com.samsung.android.app.watchmanager",     // Samsung Galaxy Watch
            "com.google.android.googlequicksearchbox",  // Google app / Assistant
            "com.google.android.as",                    // Pixel ambient services (Assistant)
        )
    }
}
