package com.cadence.music.data.metadata

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.cadence.music.CadenceApp
import com.cadence.music.data.db.ArtistInfoEntity
import com.cadence.music.data.db.LyricsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pre-fetches lyrics, artist bios and album art so the library works offline.
 * Lyrics/bios live in Room (tiny); art goes into Coil's disk cache, whose size
 * budget is the user-facing "metadata cache" setting.
 */
class MetadataWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? CadenceApp)?.container ?: return Result.failure()
        val db = container.database
        try {
            fetchMissingLyrics(db)
            fetchArtistInfo(db)
            if (container.prefs.metaArtPrewarm) prewarmArt(container)
        } catch (_: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    private suspend fun fetchMissingLyrics(db: com.cadence.music.data.db.AppDatabase) {
        // ponytail: bounded per run — completed rows are negative-cached, so the
        // queue drains across runs instead of blowing the 10-min worker limit.
        val missing = withContext(Dispatchers.IO) { db.lyricsDao().trackIdsMissingLyrics() }.take(LYRICS_BATCH)
        for (id in missing) {
            if (isStopped) return
            val t = withContext(Dispatchers.IO) { db.trackDao().byId(id) } ?: continue
            val lines = runCatching {
                LrcLib.fetchBlocking(t.artistName, t.title, t.durationMs / 1000)
            }.getOrDefault(emptyList())
            // Empty text is stored too — a negative cache so we never re-spam LRCLIB.
            withContext(Dispatchers.IO) {
                db.lyricsDao().upsert(LyricsEntity(trackId = id, syncedLrc = LrcLib.toLrcText(lines)))
            }
            delay(250)
        }
    }

    private suspend fun fetchArtistInfo(db: com.cadence.music.data.db.AppDatabase) {
        val staleBefore = System.currentTimeMillis() - STALE_AFTER_MS
        val names = (
            withContext(Dispatchers.IO) { db.artistInfoDao().missingArtistNames() } +
                withContext(Dispatchers.IO) { db.artistInfoDao().staleArtistNames(staleBefore) }
            ).distinct().take(ARTIST_BATCH)
        for (name in names) {
            if (isStopped) return
            val info = runCatching { Wikipedia.artistInfoBlocking(name) }.getOrNull()
            // No-miss-cache: an all-null lookup is a miss, not data — skip it so
            // the artist retries on the next visit/run instead of poisoning for 30 days.
            if (info?.bio == null && info?.imageUrl == null) continue
            withContext(Dispatchers.IO) {
                db.artistInfoDao().upsert(
                    ArtistInfoEntity(name = name, bio = info?.bio, imageUrl = info?.imageUrl),
                )
            }
            delay(250)
        }
    }

    private suspend fun prewarmArt(container: com.cadence.music.AppContainer) {
        val context = applicationContext
        val db = container.database
        val albums = withContext(Dispatchers.IO) {
            listOf("subsonic", "jellyfin", "emby", "plex").flatMap { db.albumDao().bySource(it) }
        }.take(ART_BATCH)
        for (album in albums) {
            if (isStopped) return
            val track = withContext(Dispatchers.IO) { db.trackDao().byAlbumKey(album.serverId).firstOrNull() }
                ?: continue
            val url = runCatching { container.artResolver.urlFor(track) }.getOrNull() ?: continue
            if (!url.startsWith("http")) continue // local content:// URIs need no warming
            val request = ImageRequest.Builder(context)
                .data(url)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            runCatching { context.imageLoader.execute(request) }
            delay(150)
        }
    }

    companion object {
        private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        // Per-run budgets: ~250ms/item + HTTP keeps each phase inside the worker limit.
        private const val LYRICS_BATCH = 200
        private const val ARTIST_BATCH = 100
        // Art repeats are Coil disk-cache hits; the cap bounds fresh-network time.
        private const val ART_BATCH = 100
    }
}

/** Registers the periodic metadata job; call again whenever settings change. */
object MetadataSync {

    fun schedule(context: Context) {
        val prefs = (context.applicationContext as CadenceApp).container.prefs
        val wm = WorkManager.getInstance(context)
        val hours = prefs.metaIntervalHours
        if (hours <= 0) {
            wm.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<MetadataWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(prefs))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        val prefs = (context.applicationContext as CadenceApp).container.prefs
        val request = OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(constraints(prefs))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints(prefs: com.cadence.music.data.prefs.Prefs) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.metaWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    private const val PERIODIC_WORK = "metadata-periodic"
    private const val ONE_SHOT_WORK = "metadata-now"
}
