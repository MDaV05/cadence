package com.cadence.music.data.source

import com.cadence.music.data.prefs.ServerEntry

class EmbySource(entry: ServerEntry, deviceId: String = "") : EmbyLikeSource(entry, deviceId) {
    override val id = "emby"
    override fun prefix() = "emby:"

    override suspend fun authenticate(): Pair<String, String>? {
        val body = org.json.JSONObject()
            .put("Username", entry.user)
            .put("Pw", entry.password ?: "").toString()
        return authenticatePost(body)
    }
}
