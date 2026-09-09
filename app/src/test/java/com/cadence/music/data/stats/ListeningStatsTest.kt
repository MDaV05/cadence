package com.cadence.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed UTC epoch-millis constants — no wall-clock in these tests; `now` is passed explicitly.
 * All dates fall in the ISO week Mon 2026-09-07 .. Sun 2026-09-13.
 */
class ListeningStatsTest {

    private companion object {
        const val DAY = 86_400_000L
        const val WEEK = 7 * DAY

        /** Wed 2026-09-09 12:00 UTC — mid current week. */
        const val NOW = 1_788_955_200_000L

        /** Mon 2026-09-07 00:00 UTC — start of the current week. */
        const val THIS_MON = 1_788_739_200_000L

        /** Mon 2026-08-31 — previous week. */
        const val PREV_MON = 1_788_134_400_000L

        /** Mon 2026-08-24 — two weeks back. */
        const val PREV2_MON = 1_787_529_600_000L

        /** Mon 2026-08-17 — three weeks back. */
        const val PREV3_MON = 1_786_924_800_000L

        const val SEVEN_DAYS = 604_800_000L

        fun row(plays: Int, durationMs: Long, lastPlayed: Long? = null) =
            PlayRow(playCount = plays, durationMs = durationMs, lastPlayed = lastPlayed)
    }

    // ---- computeStats: totals ----

    @Test
    fun `empty rows give all zeros`() {
        val s = computeStats(emptyList(), NOW)
        assertEquals(0L, s.totalMinutes)
        assertEquals(0, s.totalPlays)
        assertEquals(0, s.uniquePlayed)
        assertEquals(0, s.playsLast7Days)
        assertEquals(0, s.weekStreak)
    }

    @Test
    fun `single row totals`() {
        // 3 plays x 200 000 ms = 600 000 ms = exactly 10 min.
        val s = computeStats(listOf(row(3, 200_000L, NOW)), NOW)
        assertEquals(10L, s.totalMinutes)
        assertEquals(3, s.totalPlays)
        assertEquals(1, s.uniquePlayed)
    }

    @Test
    fun `minutes truncate down`() {
        // 1 play x 119 999 ms → 1 min, not 2.
        val s = computeStats(listOf(row(1, 119_999L)), NOW)
        assertEquals(1L, s.totalMinutes)
    }

    @Test
    fun `multi-row sums with unplayed rows excluded from unique count`() {
        val rows = listOf(
            row(5, 180_000L, NOW),          // 900 000 ms
            row(2, 300_000L, PREV_MON),     // 600 000 ms
            row(0, 240_000L, null),         // never played
        )
        val s = computeStats(rows, NOW)
        assertEquals(7, s.totalPlays)
        assertEquals(2, s.uniquePlayed)
        assertEquals((1_500_000L / 60_000L), s.totalMinutes) // 25 min
    }

    // ---- computeStats: playsLast7Days window (now - 7d, now] ----

    @Test
    fun `row at exactly seven days before now is excluded`() {
        val s = computeStats(listOf(row(4, 200_000L, NOW - SEVEN_DAYS)), NOW)
        assertEquals(0, s.playsLast7Days)
    }

    @Test
    fun `row one ms after the boundary is included with its full playCount`() {
        val s = computeStats(listOf(row(5, 200_000L, NOW - SEVEN_DAYS + 1)), NOW)
        assertEquals(5, s.playsLast7Days)
    }

    @Test
    fun `row at exactly now is included`() {
        val s = computeStats(listOf(row(2, 200_000L, NOW)), NOW)
        assertEquals(2, s.playsLast7Days)
    }

    @Test
    fun `future row is excluded and unplayed row adds nothing`() {
        val rows = listOf(
            row(3, 200_000L, NOW + 1),
            row(0, 200_000L, NOW),
            row(7, 200_000L, NOW - DAY),
        )
        assertEquals(7, computeStats(rows, NOW).playsLast7Days)
    }

    // ---- computeStats: weekStreak (ISO Mon..Sun weeks in UTC) ----

    @Test
    fun `streak zero when no play timestamps`() {
        assertEquals(0, computeStats(listOf(row(9, 200_000L, null)), NOW).weekStreak)
    }

    @Test
    fun `streak one for a play this week`() {
        assertEquals(1, computeStats(listOf(row(1, 200_000L, NOW)), NOW).weekStreak)
    }

    @Test
    fun `play only last week gives streak one - empty current week does not break it`() {
        assertEquals(1, computeStats(listOf(row(1, 200_000L, PREV_MON)), NOW).weekStreak)
    }

    @Test
    fun `streak two across this and previous week`() {
        val rows = listOf(row(1, 200_000L, THIS_MON), row(1, 200_000L, PREV_MON))
        assertEquals(2, computeStats(rows, NOW).weekStreak)
    }

    @Test
    fun `full empty gap week breaks the streak`() {
        // Plays two and three weeks back; the week right before NOW's week is empty.
        val rows = listOf(row(1, 200_000L, PREV2_MON), row(1, 200_000L, PREV3_MON))
        assertEquals(0, computeStats(rows, NOW).weekStreak)
    }

    @Test
    fun `newest play older than last week gives streak zero`() {
        assertEquals(0, computeStats(listOf(row(1, 200_000L, PREV2_MON + 3 * DAY)), NOW).weekStreak)
    }

    @Test
    fun `week boundary is the UTC Sunday-to-Monday midnight`() {
        // One ms before THIS_MON belongs to the previous week: tail streak = 1, not 2.
        val rows = listOf(row(1, 200_000L, THIS_MON - 1), row(1, 200_000L, PREV_MON))
        assertEquals(1, computeStats(rows, NOW).weekStreak)
        // End-of-week Sunday 23:59:59.999 still counts as THIS week, so with last
        // week's play the streak reaches 2 (if it spilled to last week it would be 1).
        val sundayEnd = listOf(row(1, 200_000L, THIS_MON + WEEK - 1), row(1, 200_000L, PREV_MON))
        assertEquals(2, computeStats(sundayEnd, NOW).weekStreak)
    }

    // ---- mergeRecentPlays ----

    private fun entry(ts: Long, artist: String, title: String) = ts to (artist to title)

    @Test
    fun `merge of empty inputs is empty`() {
        assertEquals(emptyList<Pair<Long, Pair<String, String>>>(), mergeRecentPlays(emptyList(), emptyList()))
    }

    @Test
    fun `merge sorts newest first across both lists`() {
        val merged = mergeRecentPlays(
            local = listOf(entry(300L, "A", "three"), entry(100L, "B", "one")),
            remote = listOf(entry(200L, "C", "two")),
        )
        assertEquals(listOf(300L, 200L, 100L), merged.map { it.first })
    }

    @Test
    fun `dedupe is case-insensitive on artist and title keeping the newer entry`() {
        val merged = mergeRecentPlays(
            local = listOf(entry(100L, "The Beatles", "Come Together")),
            remote = listOf(entry(200L, "the beatles", "come TOGETHER")),
        )
        assertEquals(1, merged.size)
        assertEquals(200L, merged[0].first)
        assertEquals("the beatles" to "come TOGETHER", merged[0].second)
        // Newer in local wins symmetrically.
        val merged2 = mergeRecentPlays(
            local = listOf(entry(900L, "ab", "cd")),
            remote = listOf(entry(100L, "AB", "CD")),
        )
        assertEquals(900L, merged2.single().first)
    }

    @Test
    fun `same key different case dedupes to one entry and distinct keys survive`() {
        val merged = mergeRecentPlays(
            local = listOf(entry(1L, "Kanye West", "Gold")),
            remote = listOf(entry(2L, "kanye west", "GOLD"), entry(3L, "Kanye West", "Silver")),
        )
        assertEquals(2, merged.size)
        assertEquals(listOf(3L, 2L), merged.map { it.first })
    }

    @Test
    fun `cap is applied after dedupe`() {
        val local = (1L..20L).map { entry(it, "artist$it", "title") }
        val merged = mergeRecentPlays(local = local, remote = emptyList(), cap = 10)
        assertEquals(10, merged.size)
        assertEquals((20L downTo 11L).toList(), merged.map { it.first })
    }

    // ---- formatListenMinutes ----

    @Test
    fun `minutes below an hour render as whole minutes`() {
        assertEquals("0 min", formatListenMinutes(0))
        assertEquals("59 min", formatListenMinutes(59))
    }

    @Test
    fun `hours render with one truncated decimal`() {
        assertEquals("1.0 hr", formatListenMinutes(60))
        assertEquals("1.5 hr", formatListenMinutes(90))
        assertEquals("1.9 hr", formatListenMinutes(119))   // truncation, not rounding
        assertEquals("23.9 hr", formatListenMinutes(1_439))
    }

    @Test
    fun `days render with one truncated decimal`() {
        assertEquals("1.0 days", formatListenMinutes(1_440))
        assertEquals("1.0 days", formatListenMinutes(1_500))
        assertEquals("1.0 days", formatListenMinutes(1_559))
        assertEquals("1.1 days", formatListenMinutes(1_600))
    }
}
