package com.cadence.music.data.stats

/** Projected track columns for local listening stats (Room maps these by name). */
data class PlayRow(val playCount: Int, val durationMs: Long, val lastPlayed: Long?)

/** Projected top-artist row: display name and summed play count. */
data class ArtistPlays(val name: String, val plays: Int)

data class ListeningStats(
    val totalMinutes: Long,
    val totalPlays: Int,
    val uniquePlayed: Int,
    val playsLast7Days: Int,
    val weekStreak: Int,
)

private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 7 * DAY_MS

/**
 * Aggregate the played-track rows into library listening stats at [now] (epoch millis).
 *
 * `lastPlayed` is only the most-recent-play hint per track, so [playsLast7Days] approximates
 * by attributing all of a track's plays to that one timestamp when it falls in the window.
 */
fun computeStats(rows: List<PlayRow>, now: Long): ListeningStats {
    val totalPlays = rows.sumOf { it.playCount }
    val uniquePlayed = rows.count { it.playCount > 0 }
    val totalMinutes = rows.sumOf { it.playCount.toLong() * it.durationMs } / 60_000L
    val playsLast7Days = rows.sumOf {
        val ts = it.lastPlayed
        if (it.playCount > 0 && ts != null && ts > now - WEEK_MS && ts <= now) it.playCount else 0
    }
    return ListeningStats(totalMinutes, totalPlays, uniquePlayed, playsLast7Days, weekStreak(rows, now))
}

/** Index of the ISO (Monday-anchored) UTC week containing [ms]; 0 is the week of 1969-12-29. */
private fun weekIndex(ms: Long): Long {
    val day = Math.floorDiv(ms, DAY_MS)
    // 1970-01-01 was a Thursday, i.e. day offset 3 from that week's Monday.
    return Math.floorDiv(day - Math.floorMod(day + 3, 7), 7)
}

/**
 * Consecutive Mon..Sun (UTC) weeks containing at least one play, ending at the current week
 * or — since the young week may legitimately be empty so far — the previous week.
 * Null `lastPlayed` values are ignored; a fully empty gap week resets the streak to 0.
 */
private fun weekStreak(rows: List<PlayRow>, now: Long): Int {
    val weeks = rows.mapNotNull { if (it.playCount > 0) it.lastPlayed else null }
        .map { weekIndex(it) }
        .toSet()
    var week = weekIndex(now)
    if (!weeks.contains(week)) week -= 1
    var streak = 0
    while (weeks.contains(week)) {
        streak++
        week -= 1
    }
    return streak
}

/**
 * Merge local + ListenBrainz recent-play labels, newest first, deduped case-insensitively
 * by "artist :: title"; on collision the entry with the newer [timestampMs] wins.
 * Both lists are epoch millis (the caller converts LB's epoch-seconds).
 */
fun mergeRecentPlays(
    local: List<Pair<Long, Pair<String, String>>>,   // (timestampMs, (artist, title))
    remote: List<Pair<Long, Pair<String, String>>>,  // same shape, epoch-seconds converted by caller
    cap: Int = 10,
): List<Pair<Long, Pair<String, String>>> {
    val newestByKey = LinkedHashMap<String, Pair<Long, Pair<String, String>>>()
    for (entry in local + remote) {
        val (artist, title) = entry.second
        val key = "$artist :: $title".lowercase()
        val prev = newestByKey[key]
        if (prev == null || entry.first > prev.first) newestByKey[key] = entry
    }
    return newestByKey.values.sortedByDescending { it.first }.take(cap)
}

/** Human listening-time bucket: minutes under an hour, hours under a day, else days — truncated. */
fun formatListenMinutes(min: Long): String = when {
    min < 60 -> "$min min"
    min < 1_440 -> tenths(min * 10 / 60) + " hr"
    else -> tenths(min * 10 / 1_440) + " days"
}

/** Render a value already scaled to tenths as "X.Y". */
private fun tenths(v: Long): String = "${v / 10}.${v % 10}"
