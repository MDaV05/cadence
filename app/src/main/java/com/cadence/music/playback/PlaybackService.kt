package com.cadence.music.playback

import android.content.Intent
import kotlinx.coroutines.runBlocking
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cadence.music.CadenceApp

class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        EqManager.apply()
    }

    override fun onCreate() {
        super.onCreate()
        EqManager.init(this)
        getSharedPreferences("cadence", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        val upstream: DataSource.Factory = DefaultDataSource.Factory(this)
        val cached: DataSource.Factory = CacheDataSource.Factory()
            .setCache(StreamCache.get(applicationContext))
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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushWidget(player.currentMediaItem, isPlaying)
            }
        })
        attachEq(player.audioSessionId)

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {})
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

    private fun applyReplayGain(mediaId: String?) {
        val p = getSharedPreferences("cadence", MODE_PRIVATE)
        if (!p.getBoolean("rg_enabled", false)) {
            session?.player?.volume = 1f
            return
        }
        if (mediaId == null) return
        val container = (application as? com.cadence.music.CadenceApp)?.container ?: return
        Thread {
            val rg = runBlocking {
                runCatching { container.database.trackDao().byServerId(mediaId)?.replayGainDb }
                    .getOrNull()
            }
            val volume = rg?.let { Math.pow(10.0, it.toDouble() / 20.0).toFloat() }
                ?.coerceIn(0f, 1f) ?: 1f
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                session?.player?.volume = volume
            }
        }.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
}
