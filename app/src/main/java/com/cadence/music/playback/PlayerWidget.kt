package com.cadence.music.playback

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cadence.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Don't blind-push the placeholder: add/resize/reboot would clobber live state.
        // Read the session first; placeholder only when unreachable.
        val pending = goAsync()
        widgetScope.launch {
            try {
                val c = controller(context)
                val item = c?.currentMediaItem
                push(
                    context,
                    item?.mediaMetadata?.title?.toString() ?: "Cadence",
                    item?.mediaMetadata?.artist?.toString() ?: "",
                    c?.isPlaying == true,
                )
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                // Connecting is async (never block the broadcast thread — that ANRs);
                // goAsync keeps the process alive while the coroutine runs.
                val pending = goAsync()
                widgetScope.launch {
                    try {
                        val c = controller(context) ?: return@launch
                        when (intent.action) {
                            ACTION_TOGGLE -> if (c.isPlaying) c.pause() else c.play()
                            ACTION_NEXT -> c.seekToNextMediaItem()
                            ACTION_PREV -> c.seekToPreviousMediaItem()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cadence.music.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.cadence.music.WIDGET_NEXT"
        const val ACTION_PREV = "com.cadence.music.WIDGET_PREV"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // One app-scoped controller, kept for the widget's lifetime on purpose:
        // reconnecting on every tap costs a full service bind. Concurrent
        // connects share a single Deferred so we never leak extra bindings.
        @Volatile private var cached: MediaController? = null
        @Volatile private var connecting: Deferred<MediaController?>? = null

        // If the session dies while the process lives, taps would silently
        // no-op — clear the cache so the next tap reconnects.
        private val disconnectWatcher = object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                cached = null
                synchronized(this@Companion) { connecting = null }
            }
        }

        private suspend fun controller(context: Context): MediaController? {
            cached?.let { return it }
            val appContext = context.applicationContext
            val deferred = synchronized(this) {
                connecting ?: widgetScope.async {
                    try {
                        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                        MediaController.Builder(appContext, token)
                            .setListener(disconnectWatcher)
                            .buildAsync().await()
                    } catch (_: Exception) {
                        null
                    }
                }.also { connecting = it }
            }
            val c = deferred.await()
            if (c != null) cached = c else synchronized(this) { connecting = null }
            return c
        }

        fun togglePending(context: Context, action: String) =
            android.app.PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, PlayerWidget::class.java).setAction(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )

        /** Push current playback state into every widget instance. */
        fun push(context: Context, title: String, artist: String, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.w_title, title.ifBlank { "Cadence" })
                setTextViewText(R.id.w_artist, artist)
                setImageViewResource(
                    R.id.w_toggle,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                )
                setOnClickPendingIntent(R.id.w_prev, togglePending(context, ACTION_PREV))
                setOnClickPendingIntent(R.id.w_toggle, togglePending(context, ACTION_TOGGLE))
                setOnClickPendingIntent(R.id.w_next, togglePending(context, ACTION_NEXT))
            }
            manager.updateAppWidget(
                ComponentName(context, PlayerWidget::class.java),
                views,
            )
        }
    }
}
