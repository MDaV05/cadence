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
}
