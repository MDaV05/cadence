package com.cadence.music.data.downloads

/**
 * Turns the worker's diagnostic string into something a listener can act on.
 * The raw string stays in the database for stale-id detection and bug reports.
 */
fun humanizeDownloadError(errorText: String?): String {
    if (errorText.isNullOrBlank()) return "Download failed"
    val text = errorText.lowercase()
    return when {
        "(code 70)" in text || "http 404" in text || "http 410" in text ->
            "This track moved or was removed on the server"
        "http 401" in text || "http 403" in text || "unauthorized" in text || "forbidden" in text ||
            "not authorized" in text || "wrong username" in text || "wrong password" in text ||
            "(code 40)" in text || "(code 41)" in text || "(code 42)" in text ||
            "(code 43)" in text || "(code 44)" in text ->
            "The server rejected the sign-in. Check your account settings"
        "not enough free storage" in text || "not enough free space" in text ->
            "Not enough free space on this device"
        "timeout" in text || "timed out" in text ->
            "The connection timed out"
        "unknown host" in text || "refused" in text || "failed to connect" in text ||
            "unable to resolve" in text ->
            "Couldn't reach the server"
        "http 5" in text || "server error" in text ->
            "The server is having trouble. Try again later"
        "truncated" in text || "interrupted" in text ->
            "The download was interrupted"
        "json instead" in text || "xml instead" in text || "html page" in text ||
            "error page" in text || "not audio" in text || "non-audio" in text ->
            "The server sent a web page instead of audio"
        "short response" in text ->
            "The server returned an empty or incomplete file"
        "could not finalize" in text ->
            "Couldn't save the file"
        "stopped" in text ->
            "Download stopped"
        else -> errorText
    }
}
