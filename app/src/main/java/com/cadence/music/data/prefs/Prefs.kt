package com.cadence.music.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class LibraryMode { LOCAL_ONLY, API_ONLY, HYBRID }

data class ServerConfig(
    val url: String,
    val user: String,
    val password: String,
)

enum class ServerType { SUBSONIC, JELLYFIN, EMBY, PLEX }

data class ServerEntry(
    val id: String,
    val type: ServerType,
    val url: String,
    val user: String,
    val password: String? = null, // subsonic only; jelly/emby/plex use token
    val token: String? = null,
    val userId: String? = null, // jelly/emby remote user id
    val active: Boolean = true,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("type", type.name).put("url", url).put("user", user)
        .put("password", password).put("token", token).put("userId", userId).put("active", active)

    companion object {
        fun fromJson(o: org.json.JSONObject): ServerEntry = ServerEntry(
            id = o.getString("id"),
            type = ServerType.valueOf(o.getString("type")),
            url = o.getString("url"),
            user = o.optString("user", ""),
            password = o.optString("password", null),
            token = o.optString("token", null),
            userId = o.optString("userId", null),
            active = o.optBoolean("active", true),
        )
    }
}

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

    var servers: List<ServerEntry>
        get() {
            migrateLegacyServer()
            return runCatching {
                val arr = org.json.JSONArray(sp.getString("servers_json", "[]") ?: "[]")
                (0 until arr.length()).map { ServerEntry.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        set(value) = sp.edit().putString(
            "servers_json",
            org.json.JSONArray(value.map { it.toJson() }.toList()).toString(),
        ).apply()

    fun entry(id: String): ServerEntry? = servers.firstOrNull { it.id == id }

    // One-shot: legacy single server becomes entry "primary", then legacy keys are dropped.
    private fun migrateLegacyServer() {
        if (sp.contains("servers_json")) return
        val url = sp.getString("server_url", null) ?: return
        servers = listOf(
            ServerEntry(
                id = "primary", type = ServerType.SUBSONIC, url = url,
                user = sp.getString("server_user", "") ?: "",
                password = sp.getString("server_pass", null),
            )
        )
        sp.edit().remove("server_url").remove("server_user").remove("server_pass").apply()
    }

    var mode: LibraryMode
        get() = runCatching { LibraryMode.valueOf(sp.getString("mode", null) ?: "") }
            .getOrDefault(LibraryMode.HYBRID)
        set(value) = sp.edit().putString("mode", value.name).apply()

    /** Emits the current mode first, then re-emits on every mode change. */
    fun observeMode(): Flow<LibraryMode> = callbackFlow {
        trySend(mode)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") trySend(mode)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

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

    // Equalizer: band levels in milliBel, comma-separated (e.g. "0,-300,0,200,400")
    var eqEnabled: Boolean
        get() = sp.getBoolean("eq_enabled", false)
        set(value) = sp.edit().putBoolean("eq_enabled", value).apply()

    var eqBands: List<Int>
        get() = (sp.getString("eq_bands", null) ?: "0,0,0,0,0")
            .split(",").mapNotNull { it.toIntOrNull() }
        set(value) = sp.edit().putString("eq_bands", value.joinToString(",")).apply()

    // Bass boost strength 0..1000 (per android.media.audiofx.BassBoost)
    var eqBassBoost: Int
        get() = sp.getInt("eq_bass", 0)
        set(value) = sp.edit().putInt("eq_bass", value).apply()

    // ReplayGain volume normalization from file tags
    var rgEnabled: Boolean
        get() = sp.getBoolean("rg_enabled", false)
        set(value) = sp.edit().putBoolean("rg_enabled", value).apply()

    // ---- Metadata & lyrics auto-downloader ----

    /** Hours between background metadata fetches; 0 disables the periodic job. */
    var metaIntervalHours: Int
        get() = sp.getInt("meta_interval_h", 12)
        set(value) = sp.edit().putInt("meta_interval_h", value).apply()

    var metaWifiOnly: Boolean
        get() = sp.getBoolean("meta_wifi_only", true)
        set(value) = sp.edit().putBoolean("meta_wifi_only", value).apply()

    /** Budget for pre-fetched album art (Coil disk cache), in MB. */
    var metaCacheMb: Int
        get() = sp.getInt("meta_cache_mb", 200)
        set(value) = sp.edit().putInt("meta_cache_mb", value).apply()

    var metaArtPrewarm: Boolean
        get() = sp.getBoolean("meta_art_prewarm", true)
        set(value) = sp.edit().putBoolean("meta_art_prewarm", value).apply()

    // ---- Appearance ----

    /** Selected theme id: "iris" (built-in default) or "custom:<name>". */
    var themeId: String
        get() = sp.getString("theme_id", null) ?: "iris"
        set(value) = sp.edit().putString("theme_id", value).apply()

    /** Whether the app follows the system dark mode instead of a fixed mode. */
    var themeFollowSystem: Boolean
        get() = sp.getBoolean("theme_follow_system", true)
        set(value) = sp.edit().putBoolean("theme_follow_system", value).apply()

    var themeDarkOverride: Boolean
        get() = sp.getBoolean("theme_dark_override", false)
        set(value) = sp.edit().putBoolean("theme_dark_override", value).apply()

    // ---- Search history (JSON array of queries, newest first) ----

    var searchHistory: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("search_history", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(value) = sp.edit()
            .putString("search_history", org.json.JSONArray(value).toString())
            .apply()

    // ---- Library sorting (songs tab) ----

    enum class SongSort { TITLE, ARTIST, ALBUM, DURATION, RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED }

    var songSort: SongSort
        get() = runCatching { SongSort.valueOf(sp.getString("song_sort", null) ?: "") }
            .getOrDefault(SongSort.TITLE)
        set(value) = sp.edit().putString("song_sort", value.name).apply()

    var songSortAscending: Boolean
        get() = sp.getBoolean("song_sort_asc", true)
        set(value) = sp.edit().putBoolean("song_sort_asc", value).apply()

    var updateAutoCheck: Boolean
        get() = sp.getBoolean("update_auto_check", true)
        set(value) = sp.edit().putBoolean("update_auto_check", value).apply()
}
