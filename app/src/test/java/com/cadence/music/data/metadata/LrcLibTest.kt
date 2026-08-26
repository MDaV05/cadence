package com.cadence.music.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibTest {

    @Test
    fun `basic timestamps parse to millis`() {
        val lines = LrcLib.parse("[01:02.03]Hello world")
        assertEquals(listOf(SyncedLine(62_030, "Hello world")), lines)
    }

    @Test
    fun `two digit fraction is padded to millis`() {
        val lines = LrcLib.parse("[01:02.50]Half second")
        assertEquals(62_500L, lines.single().timeMs)
    }

    @Test
    fun `missing fraction counts as zero`() {
        val lines = LrcLib.parse("[01:02]No fraction")
        assertEquals(62_000L, lines.single().timeMs)
    }

    @Test
    fun `colon fraction separator accepted`() {
        val lines = LrcLib.parse("[00:05:250]Weird separator")
        assertEquals(5_250L, lines.single().timeMs)
    }

    @Test
    fun `lines are sorted by time regardless of input order`() {
        val lines = LrcLib.parse(
            """
            [00:10.00]later
            [00:02.00]earlier
            [00:06.00]middle
            """.trimIndent(),
        )
        assertEquals(listOf("earlier", "middle", "later"), lines.map { it.text })
    }

    @Test
    fun `empty text lines are skipped`() {
        val lines = LrcLib.parse(
            """
            [00:01.00]
            [00:02.00]real
            [00:03.000]
            """.trimIndent(),
        )
        assertEquals(listOf(SyncedLine(2_000, "real")), lines)
    }

    @Test
    fun `metadata tags and plain text are ignored`() {
        val lines = LrcLib.parse(
            """
            [ti:Some Title]
            [ar:Some Artist]
            just lyrics with no timestamp
            [12:34.56]kept
            """.trimIndent(),
        )
        assertEquals(listOf(SyncedLine((12 * 60 + 34) * 1000 + 560, "kept")), lines)
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(LrcLib.parse("").isEmpty())
    }

    @Test
    fun `lrc text round-trips through parse`() {
        val lines = listOf(
            SyncedLine(0L, "first"),
            SyncedLine(62_500L, "second"),
            SyncedLine(3_725_123L, "third"),
        )
        assertEquals(lines, LrcLib.parse(LrcLib.toLrcText(lines)))
    }
}
