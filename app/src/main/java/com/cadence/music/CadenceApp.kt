package com.cadence.music

import android.Manifest
import android.app.DownloadManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
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
import com.cadence.music.data.update.UpdateStatus
import com.cadence.music.data.update.UpdateStatus.Available
import com.cadence.music.data.update.UpdateStatus.Checking
import com.cadence.music.data.update.UpdateStatus.Failed
import com.cadence.music.data.update.UpdateStatus.Idle
import com.cadence.music.data.update.UpdateStatus.UpToDate
import com.cadence.music.data.update.fetchLatest
import com.cadence.music.data.update.isNewerTag
import com.cadence.music.data.update.pickApkAsset
import com.cadence.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
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
        if (hasAudioPermission() || container.prefs.servers.any { it.active }) {
            appScope.launch {
                runCatching { container.library.syncAll() }
                runCatching { container.flushPendingScrobbles() }
            }
        }
        appScope.launch { container.loadCustomThemes() }
        // Update check: tiny JSON, any network, never blocks startup, never dialogs.
        if (container.prefs.updateAutoCheck) {
            appScope.launch { runCatching { container.refreshUpdateStatus() } }
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "library-sync", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

class AppContainer(app: Application) {
    private val appContext = app.applicationContext
    val prefs = Prefs(app)
    val database: AppDatabase = AppDatabase.build(app)
    val localSource = LocalSource(app)
    val library = LibraryRepository(database, localSource, prefs, app)
    val artResolver = ArtResolver(library)
    val player = PlayerConnection(
        app,
        submitScrobble = { artist, title, album -> submitScrobble(artist, title, album) },
        resolveStreamUrl = { track -> library.streamUrlFor(track) },
        onTrackPlayed = { mediaId ->
            database.trackDao().byServerId(mediaId)?.let { database.trackDao().recordPlay(it.id) }
        },
    )

    private val _updateStatus = MutableStateFlow<UpdateStatus>(Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus

    fun installedVersion(): String = runCatching {
        val pm = appContext.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(appContext.packageName, 0)
        }
        info.versionName ?: "0.2.0"
    }.getOrDefault("0.2.0")

    /** Runs one check; every failure path lands on Failed, never throws. */
    suspend fun refreshUpdateStatus() {
        _updateStatus.value = Checking
        val rel = fetchLatest()
        val installed = installedVersion()
        val status = if (rel == null || !isNewerTag(rel.tag, installed)) {
            if (rel == null) Failed() else UpToDate()
        } else {
            val asset = pickApkAsset(rel.assets, rel.tag)
            if (asset == null) Failed() else Available(rel.tag, asset.url, rel.htmlUrl)
        }
        _updateStatus.value = status
    }

    /** Enqueues the APK in DownloadManager; progress/completion UI is the system's. */
    fun downloadUpdate(tag: String, assetUrl: String) {
        val req = DownloadManager.Request(Uri.parse(assetUrl))
            .setTitle("Cadence $tag")
            .setDescription("App update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "cadence-$tag.apk")
            .setMimeType("application/vnd.android.package-archive")
        (appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }

    /** In-app install intent for a finished download; null when the file is absent. */
    fun installIntent(tag: String): Intent? {
        val file = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "cadence-$tag.apk")
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun filesDir(): File = appContext.filesDir

    fun cacheDir(): File = appContext.cacheDir

    /** Submits a listen; failed submissions are queued for a later flush. */
    suspend fun submitScrobble(artist: String, title: String, album: String?) {
        val token = prefs.listenBrainzToken ?: return
        val ok = com.cadence.music.data.metadata.ListenBrainz.submitBlocking(token, artist, title, album)
        if (!ok) {
            database.pendingScrobbleDao().insert(
                com.cadence.music.data.db.PendingScrobbleEntity(artist = artist, title = title, album = album)
            )
        }
    }

    /** Retries every queued scrobble; drops the ones that finally go through. */
    suspend fun flushPendingScrobbles() {
        val token = prefs.listenBrainzToken ?: return
        for (p in database.pendingScrobbleDao().all()) {
            if (com.cadence.music.data.metadata.ListenBrainz.submitBlocking(token, p.artist, p.title, p.album)) {
                database.pendingScrobbleDao().delete(p.id)
            }
        }
    }

    // ---- Theme state (read by CadenceTheme; mutate + refreshTheme()) ----

    /** Bumped on every theme selection or custom-theme list change. */
    val themeTick = androidx.compose.runtime.mutableIntStateOf(0)

    var customThemes: List<com.cadence.music.data.db.CustomThemeEntity> by mutableStateOf(
        emptyList<com.cadence.music.data.db.CustomThemeEntity>()
    )
        private set

    suspend fun loadCustomThemes() {
        customThemes = runCatching {
            database.themeDao().observeAll().first()
        }.getOrDefault(emptyList())
    }

    fun refreshTheme() {
        themeTick.intValue++
    }
}
