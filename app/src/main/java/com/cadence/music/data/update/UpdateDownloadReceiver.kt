package com.cadence.music.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.music.CadenceApp

/** "Download" action of the update notification — enqueues the APK, no UI. */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("tag") ?: return
        val assetUrl = intent.getStringExtra("assetUrl") ?: return
        runCatching {
            (context.applicationContext as CadenceApp).container.downloadUpdate(tag, assetUrl)
        }
    }
}
