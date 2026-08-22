package com.cadence.music.data.prefs

import android.content.Context

enum class LibraryMode { LOCAL_ONLY, API_ONLY, HYBRID }

data class ServerConfig(
    val url: String,
    val user: String,
    val password: String,
)

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("cadence", Context.MODE_PRIVATE)

    var server: ServerConfig?
        get() {
            val url = sp.getString("server_url", null) ?: return null
            return ServerConfig(url, sp.getString("server_user", "") ?: "", sp.getString("server_pass", "") ?: "")
        }
        set(value) {
            sp.edit()
                .putString("server_url", value?.url)
                .putString("server_user", value?.user)
                .putString("server_pass", value?.password)
                .apply()
        }

    var mode: LibraryMode
        get() = runCatching { LibraryMode.valueOf(sp.getString("mode", null) ?: "") }
            .getOrDefault(LibraryMode.HYBRID)
        set(value) = sp.edit().putString("mode", value.name).apply()

    // "raw" keeps original bitrate; otherwise a Subsonic transcode format like "opus" or "mp3"
    var downloadFormat: String
        get() = sp.getString("download_format", null) ?: "raw"
        set(value) = sp.edit().putString("download_format", value).apply()

    var downloadBitrate: Int
        get() = sp.getInt("download_bitrate", 128)
        set(value) = sp.edit().putInt("download_bitrate", value).apply()

    var cacheGb: Int
        get() = sp.getInt("cache_gb", 2)
        set(value) = sp.edit().putInt("cache_gb", value).apply()

    var listenBrainzToken: String?
        get() = sp.getString("lb_token", null)
        set(value) = sp.edit().putString("lb_token", value).apply()

    enum class TrackGesture { HORIZONTAL, VERTICAL }

    var trackGesture: TrackGesture
        get() = runCatching { TrackGesture.valueOf(sp.getString("track_gesture", null) ?: "") }
            .getOrDefault(TrackGesture.HORIZONTAL)
        set(value) = sp.edit().putString("track_gesture", value.name).apply()
}
