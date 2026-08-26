package com.cadence.music

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cadence.music.data.LibraryRepository
import com.cadence.music.data.SyncWorker
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.metadata.ArtResolver
import com.cadence.music.data.metadata.MetadataSync
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.SubsonicSource
import com.cadence.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class CadenceApp : Application(), coil.ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        schedulePeriodicSync()
        MetadataSync.schedule(this)
        // Catch up on library changes since the app was last open.
        if (hasAudioPermission() || container.prefs.server != null) {
            appScope.launch { runCatching { container.library.syncAll() } }
        }
    }

    /** Coil loader sized by the metadata-cache setting; AsyncImage picks this up globally. */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("metadata_images"))
                    .maxSizeBytes(container.prefs.metaCacheMb.coerceIn(50, 1000) * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "library-sync", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

class AppContainer(app: Application) {
    val prefs = Prefs(app)
    val database: AppDatabase = AppDatabase.build(app)
    val localSource = LocalSource(app)
    val subsonic = SubsonicSource { prefs.server }
    val library = LibraryRepository(database, localSource, subsonic, prefs, app)
    val artResolver = ArtResolver(subsonic)
    val player = PlayerConnection(
        app,
        { prefs.listenBrainzToken },
        { track -> subsonic.streamUrl(track) },
        { mediaId ->
            database.trackDao().byServerId(mediaId)?.let { database.trackDao().recordPlay(it.id) }
        },
    )
}
