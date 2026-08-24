package com.cadence.music.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Audio reading permission for this API level (READ_MEDIA_AUDIO on 33+, legacy below). */
fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * True when another request would be silently auto-denied ("don't ask again"),
 * i.e. the user denied before and the system stopped showing the dialog.
 */
fun permanentlyDenied(activity: Activity, permission: String): Boolean =
    !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

/** Opens the app's page in system Settings, the only way out of a permanent denial. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    )
}

/** The hosting activity for permission APIs, or null when unavailable. */
fun Context.hostActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
