package com.cadence.music.playback

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cadence.music.R
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking

class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // State push comes from PlaybackService; render placeholder until then.
        push(context, "Cadence", "", false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> controller(context)?.let { c -> if (c.isPlaying) c.pause() else c.play() }
            ACTION_NEXT -> controller(context)?.seekToNextMediaItem()
            ACTION_PREV -> controller(context)?.seekToPreviousMediaItem()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cadence.music.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.cadence.music.WIDGET_NEXT"
        const val ACTION_PREV = "com.cadence.music.WIDGET_PREV"

        private var cached: MediaController? = null

        private fun controller(context: Context): MediaController? {
            cached?.let { return it }
            return try {
                runBlocking {
                    val token = SessionToken(
                        context.applicationContext,
                        ComponentName(context.applicationContext, PlaybackService::class.java),
                    )
                    MediaController.Builder(context.applicationContext, token)
                        .buildAsync().await()
                }.also { cached = it }
            } catch (_: Exception) { null }
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
