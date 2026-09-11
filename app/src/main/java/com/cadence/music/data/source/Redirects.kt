package com.cadence.music.data.source

import java.net.URL

/**
 * Same-origin test for manual redirect following (R3-07): scheme + host + port,
 * host case-insensitive, absent port compared as the scheme's default port.
 * Redirects are only safe to follow (auth headers and query-param credentials
 * included) when they stay on the configured server's origin.
 */
fun sameOrigin(a: String, b: String): Boolean {
    val ua = runCatching { URL(a) }.getOrNull() ?: return false
    val ub = runCatching { URL(b) }.getOrNull() ?: return false
    return ua.protocol.equals(ub.protocol, ignoreCase = true) &&
        ua.host.equals(ub.host, ignoreCase = true) &&
        effectivePort(ua) == effectivePort(ub)
}

private fun effectivePort(u: URL): Int = if (u.port == -1) u.defaultPort else u.port
