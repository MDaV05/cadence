package com.cadence.music.data.metadata

import com.cadence.music.data.stats.ArtistPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JSON-string fixtures only — no network, no Android. */
class ListenBrainzParseTest {

    // -- parseValidate --------------------------------------------------------

    @Test
    fun validateReturnsUserNameForValidToken() {
        val (valid, user) = ListenBrainz.parseValidate("""{"valid":true,"user_name":"nicodemus"}""")
        assertTrue(valid)
        assertEquals("nicodemus", user)
    }

    @Test
    fun validateFalseReturnsSentinel() {
        val (valid, user) = ListenBrainz.parseValidate("""{"valid":false,"message":"Token not found"}""")
        assertEquals(false, valid)
        assertNull(user)
    }

    @Test
    fun validateGarbageOrNullReturnsSentinel() {
        assertEquals(false to null, ListenBrainz.parseValidate("not json"))
        assertEquals(false to null, ListenBrainz.parseValidate(""))
        assertEquals(false to null, ListenBrainz.parseValidate("[1,2]"))
        assertEquals(false to null, ListenBrainz.parseValidate(null))
    }

    @Test
    fun validateMissingFieldsReturnsSentinel() {
        assertEquals(false to null, ListenBrainz.parseValidate("{}"))
        assertEquals(false to null, ListenBrainz.parseValidate("""{"message":"hi"}"""))
    }

    @Test
    fun validateNeverReturnsUserNameWhenInvalid() {
        val (valid, user) = ListenBrainz.parseValidate("""{"valid":false,"user_name":"secret"}""")
        assertEquals(false, valid)
        assertNull(user)
    }

    // -- parseRecentListens ---------------------------------------------------

    @Test
    fun recentListensMapsApiOrder() {
        val json = """
            {"payload":{"listens":[
              {"listened_at":1700000300,"track_metadata":{"artist_name":"Alive Ali","track_name":"Later"}},
              {"listened_at":1700000100,"track_metadata":{"artist_name":"Alive Ali","track_name":"Earlier","release_name":"R"}}
            ]}}
        """.trimIndent()
        val listens = ListenBrainz.parseRecentListens(json)
        assertEquals(
            listOf(
                ListenBrainz.LbListen(1700000300L, "Alive Ali", "Later"),
                ListenBrainz.LbListen(1700000100L, "Alive Ali", "Earlier"),
            ),
            listens,
        )
    }

    @Test
    fun recentListensEmptyArrayIsEmpty() {
        assertEquals(emptyList<Any>(), ListenBrainz.parseRecentListens("""{"payload":{"listens":[]}}"""))
    }

    @Test
    fun recentListensSkipsMalformedEntries() {
        val json = """
            {"payload":{"listens":[
              {"listened_at":1,"track_metadata":{"artist_name":"A","track_name":"ok"}},
              {"listened_at":2},
              {"listened_at":3,"track_metadata":{"artist_name":"A"}},
              {"listened_at":4,"track_metadata":{"artist_name":"  ","track_name":"B"}},
              {"listened_at":5,"track_metadata":{"artist_name":null,"track_name":"B"}},
              {"listened_at":6,"track_metadata":{"track_name":"B"}},
              {"listened_at":7,"track_metadata":{"artist_name":"A","track_name":"  "}}
            ]}}
        """.trimIndent()
        val listens = ListenBrainz.parseRecentListens(json)
        assertEquals(1, listens.size)
        assertEquals(ListenBrainz.LbListen(1L, "A", "ok"), listens[0])
    }

    @Test
    fun recentListensMissingPayloadOrGarbageIsEmpty() {
        assertEquals(emptyList<Any>(), ListenBrainz.parseRecentListens("{}"))
        assertEquals(emptyList<Any>(), ListenBrainz.parseRecentListens("""{"payload":{}}"""))
        assertEquals(emptyList<Any>(), ListenBrainz.parseRecentListens("garbage"))
        assertEquals(emptyList<Any>(), ListenBrainz.parseRecentListens(null))
    }

    @Test
    fun recentListensCapsAt25() {
        val entries = (1..30).joinToString(",") {
            """{"listened_at":${1000 + it},"track_metadata":{"artist_name":"A","track_name":"T$it"}}"""
        }
        val listens = ListenBrainz.parseRecentListens("""{"payload":{"listens":[$entries]}}""")
        assertEquals(25, listens.size)
        assertEquals("T1", listens.first().title)
    }

    // -- parseLbStats ---------------------------------------------------------

    @Test
    fun statsParsesTotalAndTopArtists() {
        val json = """
            {"payload":{"total_listen_count":4321,"top_artists":[
              {"artist":{"artist_name":"Alive Ali"},"listen_count":42},
              {"artist":{"artist_name":"Bored Bee"},"listen_count":7}
            ]}}
        """.trimIndent()
        val stats = ListenBrainz.parseLbStats(json)
        assertEquals(4321L, stats?.totalListens)
        assertEquals(listOf(ArtistPlays("Alive Ali", 42), ArtistPlays("Bored Bee", 7)), stats?.topArtists)
    }

    @Test
    fun statsMissingPayloadReturnsNull() {
        assertNull(ListenBrainz.parseLbStats("{}"))
        assertNull(ListenBrainz.parseLbStats("""{"message":"Rate limited. Retry after a while."}"""))
        assertNull(ListenBrainz.parseLbStats("garbage"))
        assertNull(ListenBrainz.parseLbStats(null))
    }

    @Test
    fun statsEmptyTopArtistsIsValid() {
        val stats = ListenBrainz.parseLbStats("""{"payload":{"total_listen_count":5,"top_artists":[]}}""")
        assertEquals(5L, stats?.totalListens)
        assertEquals(emptyList<Any>(), stats?.topArtists)
        // top_artists entirely absent is still a usable stats blob.
        assertEquals(emptyList<Any>(), ListenBrainz.parseLbStats("""{"payload":{"total_listen_count":5}}""")?.topArtists)
    }

    @Test
    fun statsSkipsPartialArtistEntries() {
        val json = """
            {"payload":{"total_listen_count":50,"top_artists":[
              {"artist":{"artist_name":"Good"},"listen_count":10},
              {"listen_count":9},
              {"artist":{"genre":"nah"},"listen_count":8},
              {"artist":{"artist_name":"  "},"listen_count":7},
              null
            ]}}
        """.trimIndent()
        val stats = ListenBrainz.parseLbStats(json)
        assertEquals(listOf(ArtistPlays("Good", 10)), stats?.topArtists)
    }
}
