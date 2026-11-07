package com.cadence.music.data.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * "Wi-Fi only" really means "unmetered": a tethered Ethernet or unmetered
 * mobile link should not block a download the system considers free.
 */
fun isUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/**
 * Live unmetered state for UI copy ("waiting for Wi-Fi", size warnings).
 * A one-shot read goes stale the moment the user leaves the house, which made
 * a queued download look stuck rather than honestly waiting.
 */
fun observeUnmetered(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    trySend(isUnmetered(context))
    if (cm == null) {
        awaitClose()
        return@callbackFlow
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        override fun onLost(network: Network) {
            trySend(isUnmetered(context))
        }
    }
    runCatching { cm.registerDefaultNetworkCallback(callback) }
        .onFailure { close() }
    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
}
