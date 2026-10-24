package com.cadence.music.data

import com.cadence.music.data.db.TrackEntity
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

private val WHITESPACE = Regex("\\s+")

internal fun normalize(value: String): String =
    value.trim().lowercase().replace(WHITESPACE, " ")

/**
 * Checks whether two track entities represent the same logical song across sources.
 */
fun isSameTrack(a: TrackEntity, b: TrackEntity): Boolean {
    if (a.id != 0L && a.id == b.id) return true
    if (a.sourceId == b.sourceId && a.serverId == b.serverId) return true

    val titleA = normalize(a.title)
    val titleB = normalize(b.title)
    if (titleA.isBlank() || titleA != titleB) return false

    // 1. Same normalized album
    if (a.albumNorm.isNotBlank() && a.albumNorm.equals(b.albumNorm, ignoreCase = true)) {
        if (a.trackNumber > 0 && b.trackNumber > 0) {
            return a.trackNumber == b.trackNumber
        }
        return true
    }

    // 2. Same primary artist across sources
    val artistA = normalize(primaryArtist(a.artistName))
    val artistB = normalize(primaryArtist(b.artistName))
    if (artistA.isNotBlank() && artistA == artistB) {
        val durDiff = Math.abs(a.durationMs - b.durationMs)
        if (durDiff <= 5000 || a.durationMs <= 0 || b.durationMs <= 0) {
            return true
        }
    }

    return false
}

/**
 * Merges two duplicate representations of the same track into a single enriched entity.
 * Always prefers a local MediaStore or downloaded file for instant offline playback.
 */
fun mergeTrackEntities(primary: TrackEntity, secondary: TrackEntity): TrackEntity {
    val preferA = when {
        primary.sourceId == "local" && secondary.sourceId != "local" -> true
        secondary.sourceId == "local" && primary.sourceId != "local" -> false
        !primary.path.isNullOrEmpty() && secondary.path.isNullOrEmpty() -> true
        !secondary.path.isNullOrEmpty() && primary.path.isNullOrEmpty() -> false
        else -> true
    }
    val (base, other) = if (preferA) primary to secondary else secondary to primary

    return base.copy(
        trackNumber = if (base.trackNumber > 0) base.trackNumber else other.trackNumber,
        replayGainDb = base.replayGainDb ?: other.replayGainDb,
        albumMediaId = base.albumMediaId ?: other.albumMediaId,
        albumKey = base.albumKey ?: other.albumKey,
        playCount = maxOf(base.playCount, other.playCount),
        lastPlayed = maxOf(base.lastPlayed ?: 0L, other.lastPlayed ?: 0L).takeIf { it > 0L },
        starred = base.starred || other.starred,
        genre = base.genre?.takeIf { it.isNotBlank() } ?: other.genre,
        artistRaw = base.artistRaw?.takeIf { it.isNotBlank() } ?: other.artistRaw,
    )
}

/**
 * Deduplicates and merges a list of tracks, preserving order and enriched metadata.
 */
fun mergeTrackList(tracks: List<TrackEntity>): List<TrackEntity> {
    if (tracks.size <= 1) return tracks
    val result = mutableListOf<TrackEntity>()
    val visited = BooleanArray(tracks.size)
    // isSameTrack always requires equal normalized titles, so only compare
    // within one title bucket. This keeps shuffle/liked lists near O(n)
    // instead of scanning every pair in a 10k-track library.
    val byTitle = tracks.indices.groupBy { normalize(tracks[it].title) }

    for (i in tracks.indices) {
        if (visited[i]) continue
        var current = tracks[i]
        visited[i] = true
        for (j in byTitle.getValue(normalize(current.title))) {
            if (j > i && !visited[j] && isSameTrack(current, tracks[j])) {
                current = mergeTrackEntities(current, tracks[j])
                visited[j] = true
            }
        }
        result.add(current)
    }
    return result
}
