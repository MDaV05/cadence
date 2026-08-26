package com.cadence.music.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cadence.music.CadenceApp

/** Refreshes local + server library state in the background. */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? CadenceApp)?.container ?: return Result.failure()
        val hasAudioPermission = if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        // Nothing to do without either a readable media store or a server.
        if (!hasAudioPermission && container.prefs.server == null) return Result.success()
        return try {
            container.library.syncAll()
            container.flushPendingScrobbles()
            Result.success()
        } catch (_: Exception) {
            // Transient network/server failures are worth another attempt later.
            Result.retry()
        }
    }
}
