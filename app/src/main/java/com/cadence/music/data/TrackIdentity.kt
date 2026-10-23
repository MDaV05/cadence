package com.cadence.music.data

import com.cadence.music.data.tags.primaryArtist

/**
 * Stable-ish identity for a track inside one album, used to carry a download
 * over when the server hands out a new id (Navidrome ids change when a file is
 * moved or retagged). Title/artist are normalized only enough to survive case
 * and whitespace differences; duration is compared at second precision because
 * that is what Subsonic reports.
 */
internal fun trackFingerprint(title: String, artist: String, durationMs: Long): String =
    "${normalize(title)}|${normalize(primaryArtist(artist))}|${durationMs / 1000}"

private fun normalize(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")
