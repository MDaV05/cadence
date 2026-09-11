package com.cadence.music.data.prefs

/**
 * Server URLs flow straight into the media player and HTTP clients, so only
 * http(s) targets are ever accepted. Scheme-less input keeps the historical
 * behavior (http:// prefix); anything with an explicit but non-http scheme
 * (file://, content://, …) or no usable authority is rejected as null.
 */
fun sanitizeServerUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    val url = runCatching { java.net.URL(candidate) }.getOrNull() ?: return null
    if (url.host.isBlank() || url.host.any { it.isWhitespace() }) return null
    return when (url.protocol.lowercase()) {
        "http", "https" -> candidate
        else -> null
    }
}

/**
 * Failover scheme guard: adopting a new active URL must never silently turn an
 * https connection into cleartext. https → https and http → http are fine;
 * https → http is refused (the URL may still be used for the current session).
 */
fun canAdoptActiveUrl(from: String?, to: String): Boolean {
    val fromHttps = from?.trim()?.lowercase()?.startsWith("https://") ?: false
    return !fromHttps || to.trim().lowercase().startsWith("https://")
}
