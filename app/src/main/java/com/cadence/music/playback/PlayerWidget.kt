package com.cadence.music.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import com.cadence.music.CadenceApp
import com.cadence.music.MainActivity
import com.cadence.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
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

        @Volatile private var cached: MediaController? = null
        @Volatile private var connecting: Deferred<MediaController?>? = null

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

        fun requestUpdate(context: Context) {
            widgetScope.launch {
                try {
                    val c = controller(context)
                    val item = c?.currentMediaItem
                    val mediaId = item?.mediaId
                    val container = (context.applicationContext as? CadenceApp)?.container
                    val artUrl = if (mediaId != null && container != null) {
                        withContext(Dispatchers.IO) {
                            val entity = container.database.trackDao().byServerId(mediaId)
                            if (entity != null) container.artResolver.urlFor(entity) else null
                        }
                    } else null

                    push(
                        context = context,
                        title = item?.mediaMetadata?.title?.toString() ?: "Cadence",
                        artist = item?.mediaMetadata?.artist?.toString() ?: "Ready to play",
                        playing = c?.isPlaying == true,
                        artUrl = artUrl,
                    )
                } catch (_: Exception) {
                    push(
                        context = context,
                        title = "Cadence",
                        artist = "Ready to play",
                        playing = false,
                        artUrl = null,
                    )
                }
            }
        }

        private fun togglePending(context: Context, action: String) =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, PlayerWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openAppPending(context: Context) =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        internal fun roundBitmap(src: Bitmap, cornerRadiusPx: Float): Bitmap {
            return try {
                val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                paint.shader = shader
                val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
                output
            } catch (_: Exception) {
                src
            }
        }

        /** Push current playback state into every compact and large widget instance. */
        fun push(
            context: Context,
            title: String,
            artist: String,
            playing: Boolean,
            artUrl: String? = null,
        ) {
            val appContext = context.applicationContext
            widgetScope.launch {
                val manager = AppWidgetManager.getInstance(appContext)
                val compactComponent = ComponentName(appContext, PlayerWidget::class.java)
                val largeComponent = ComponentName(appContext, LargePlayerWidget::class.java)

                val compactIds = manager.getAppWidgetIds(compactComponent)
                val largeIds = manager.getAppWidgetIds(largeComponent)

                if (compactIds.isEmpty() && largeIds.isEmpty()) return@launch

                val rawBitmap = if (!artUrl.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val request = ImageRequest.Builder(appContext)
                                .data(artUrl)
                                .size(300, 300)
                                .allowHardware(false) // RemoteViews requires software bitmaps
                                .build()
                            val result = appContext.imageLoader.execute(request)
                            (result.drawable as? BitmapDrawable)?.bitmap
                        } catch (_: Exception) {
                            null
                        }
                    }
                } else null

                val roundedBitmap = rawBitmap?.let { roundBitmap(it, 32f) }
                val openIntent = openAppPending(appContext)
                val prevIntent = togglePending(appContext, ACTION_PREV)
                val toggleIntent = togglePending(appContext, ACTION_TOGGLE)
                val nextIntent = togglePending(appContext, ACTION_NEXT)

                val playPauseRes = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play

                // Update Compact Widget instances
                if (compactIds.isNotEmpty()) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_player).apply {
                        setTextViewText(R.id.w_title, title.ifBlank { "Cadence" })
                        setTextViewText(R.id.w_artist, artist.ifBlank { "Ready to play" })
                        setImageViewResource(R.id.w_toggle, playPauseRes)
                        if (roundedBitmap != null) {
                            setImageViewBitmap(R.id.w_art, roundedBitmap)
                        } else {
                            setImageViewResource(R.id.w_art, R.drawable.ic_widget_music)
                        }
                        setOnClickPendingIntent(R.id.w_root, openIntent)
                        setOnClickPendingIntent(R.id.w_art, openIntent)
                        setOnClickPendingIntent(R.id.w_info, openIntent)
                        setOnClickPendingIntent(R.id.w_prev, prevIntent)
                        setOnClickPendingIntent(R.id.w_toggle, toggleIntent)
                        setOnClickPendingIntent(R.id.w_next, nextIntent)
                    }
                    manager.updateAppWidget(compactComponent, views)
                }

                // Update Large Widget instances
                if (largeIds.isNotEmpty()) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_large_player).apply {
                        setTextViewText(R.id.w_title, title.ifBlank { "Cadence" })
                        setTextViewText(R.id.w_artist, artist.ifBlank { "Ready to play" })
                        setImageViewResource(R.id.w_toggle, playPauseRes)
                        if (roundedBitmap != null) {
                            setImageViewBitmap(R.id.w_art, roundedBitmap)
                        } else {
                            setImageViewResource(R.id.w_art, R.drawable.ic_widget_music)
                        }
                        setOnClickPendingIntent(R.id.w_root, openIntent)
                        setOnClickPendingIntent(R.id.w_art, openIntent)
                        setOnClickPendingIntent(R.id.w_info, openIntent)
                        setOnClickPendingIntent(R.id.w_prev, prevIntent)
                        setOnClickPendingIntent(R.id.w_toggle, toggleIntent)
                        setOnClickPendingIntent(R.id.w_next, nextIntent)
                    }
                    manager.updateAppWidget(largeComponent, views)
                }
            }
        }
    }
}
