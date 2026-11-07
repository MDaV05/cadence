package com.cadence.music.data.downloads

/** Why a download failed, triaged from the worker's diagnostic string (spec D8). */
enum class DownloadCause { NETWORK, AUTH, SERVER, NOT_FOUND, STORAGE, UNKNOWN }

/** Section header for a failure group; one fix per group lives next to it. */
fun groupTitle(cause: DownloadCause): String = when (cause) {
    DownloadCause.NETWORK -> "Connection problem"
    DownloadCause.AUTH -> "Sign-in expired"
    DownloadCause.SERVER -> "Server error"
    DownloadCause.NOT_FOUND -> "Track removed or moved"
    DownloadCause.STORAGE -> "Not enough space"
    DownloadCause.UNKNOWN -> "Download failed"
}

/**
 * Buckets a stored errorText for the Failed section's grouping. Match order
 * matters: NOT_FOUND and AUTH before SERVER because describe() prefixes those
 * rows with "server error:", and SERVER before NETWORK because the cross-server
 * redirect rejection contains "refused" but is the server's fault.
 */
fun downloadCause(errorText: String?): DownloadCause {
    if (errorText.isNullOrBlank()) return DownloadCause.UNKNOWN
    val text = errorText.lowercase()
    return when {
        // A missing id (404/410) or Subsonic code 70 — the track is gone server-side.
        "http 404" in text || "http 410" in text || "(code 70)" in text || "not found" in text ->
            DownloadCause.NOT_FOUND
        // Auth must win over the "server error:" prefix describe() adds.
        "http 401" in text || "http 403" in text || "auth" in text || "sign in" in text ||
            "wrong username" in text || "wrong password" in text ||
            "(code 40)" in text || "(code 41)" in text || "(code 42)" in text ||
            "(code 43)" in text || "(code 44)" in text ->
            DownloadCause.AUTH
        // The server is reachable but misbehaving: 5xx, throttling, resume
        // desync (416 / wrong offset), redirects it should not send, and
        // bodies that are not audio.
        "http 5" in text || "http 425" in text || "http 429" in text || "http 416" in text ||
            "server is not configured" in text ||
            "server resumed at the wrong offset" in text ||
            "server error" in text || "bad redirect" in text ||
            "refused a cross-server redirect" in text ||
            "unexpected content type" in text || "zip archive" in text ||
            "instead of audio" in text || "json instead" in text || "xml instead" in text ||
            "html page" in text || "error page" in text ||
            "not audio" in text || "non-audio" in text ||
            "short response" in text || "too small" in text ->
            DownloadCause.SERVER
        // Everything that never got a real conversation going. 408 is a
        // request timeout — a connection problem, not a server fault.
        "http 408" in text ||
            "unknown host" in text || "unable to resolve" in text || "timeout" in text ||
            "timed out" in text || "refused" in text || "socket" in text ||
            "failed to connect" in text || "connection reset" in text || "broken pipe" in text ||
            "network" in text || "truncated" in text || "interrupted" in text ->
            DownloadCause.NETWORK
        // Local disk: full, or the downloaded file cannot be read or kept.
        "enospc" in text || "no space" in text || "free space" in text || "storage" in text ||
            "could not read" in text || "could not finalize" in text ||
            "unreadable" in text || "not a regular file" in text ->
            DownloadCause.STORAGE
        else -> DownloadCause.UNKNOWN
    }
}

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
