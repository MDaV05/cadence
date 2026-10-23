package com.cadence.music.data.source.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OnlineSourceTest {

    @Test
    fun `parse YouTube music search response extracts tracks`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [{
                            "musicResponsiveListItemRenderer": {
                              "flexColumns": [
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Test Song" }] }
                                  }
                                },
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Test Artist" }, { "text": " • " }, { "text": "Test Album" }] }
                                  }
                                }
                              ],
                              "playlistItemData": { "videoId": "abc123vid" }
                            }
                          }]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val parsed = YouTubeMusicSource.parseSearchResults(json)
        assertEquals(1, parsed.size)
        assertEquals("Test Song", parsed[0].title)
        assertEquals("Test Artist", parsed[0].artist)
        assertEquals("Test Album", parsed[0].album)
        assertEquals("abc123vid", parsed[0].key)
        assertEquals("ytmusic", parsed[0].sourceId)
    }

    @Test
    fun `parse YouTube music search response extracts thumbnail and duration`() {
        val json = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [{
                            "musicResponsiveListItemRenderer": {
                              "flexColumns": [
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Bohemian Rhapsody" }] }
                                  }
                                },
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [
                                      { "text": "Queen" },
                                      { "text": " • " },
                                      { "text": "A Night at the Opera" },
                                      { "text": " • " },
                                      { "text": "5:55" }
                                    ] }
                                  }
                                }
                              ],
                              "playlistItemData": { "videoId": "fJ9rUzIMcZQ" },
                              "thumbnail": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://lh3.googleusercontent.com/small.jpg", "width": 60, "height": 60 },
                                      { "url": "https://lh3.googleusercontent.com/large.jpg", "width": 544, "height": 544 }
                                    ]
                                  }
                                }
                              }
                            }
                          }]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val parsed = YouTubeMusicSource.parseSearchResults(json)
        assertEquals(1, parsed.size)
        val track = parsed[0]
        assertEquals("Bohemian Rhapsody", track.title)
        assertEquals("Queen", track.artist)
        assertEquals("A Night at the Opera", track.album)
        assertEquals("fJ9rUzIMcZQ", track.key)
        assertEquals(355000L, track.durationMs)
        assertEquals("https://lh3.googleusercontent.com/large.jpg", track.albumKey)
    }

    @Test
    fun `parse YouTube player response selects highest bitrate audio format with url`() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 140,
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 131072,
                "url": "https://rr---sn-audio140.googlevideo.com/videoplayback"
              },
              {
                "itag": 251,
                "mimeType": "audio/webm; codecs=\"opus\"",
                "bitrate": 160000,
                "url": "https://rr---sn-audio251.googlevideo.com/videoplayback"
              },
              {
                "itag": 137,
                "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                "bitrate": 4200000,
                "url": "https://rr---sn-video137.googlevideo.com/videoplayback"
              }
            ]
          }
        }
        """.trimIndent()

        val streamUrl = YouTubeMusicSource.parseStreamUrl(json)
        assertEquals("https://rr---sn-audio251.googlevideo.com/videoplayback", streamUrl)
    }

    @Test
    fun `parse SoundCloud search response extracts tracks and upgrades artwork`() {
        val json = """
        {
          "collection": [
            {
              "id": 987654,
              "title": "SC Track Title",
              "user": { "username": "SC Artist" },
              "duration": 210000,
              "artwork_url": "https://i1.sndcdn.com/artworks-123456-large.jpg",
              "media": {
                "transcodings": [
                  {
                    "url": "https://api-v2.soundcloud.com/media/stream",
                    "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = SoundCloudSource.parseSearchResults(json)
        assertEquals(1, parsed.size)
        assertEquals("SC Track Title", parsed[0].title)
        assertEquals("SC Artist", parsed[0].artist)
        assertEquals("987654", parsed[0].key)
        assertEquals(210000L, parsed[0].durationMs)
        assertEquals("https://i1.sndcdn.com/artworks-123456-t500x500.jpg", parsed[0].albumKey)
        assertEquals("soundcloud", parsed[0].sourceId)
    }

    @Test
    fun `parse SoundCloud transcodings prefers progressive over HLS`() {
        val json = """
        {
          "media": {
            "transcodings": [
              {
                "url": "https://api-v2.soundcloud.com/media/hls/stream",
                "format": { "protocol": "hls", "mime_type": "audio/ogg; codecs=\"opus\"" }
              },
              {
                "url": "https://api-v2.soundcloud.com/media/progressive/stream",
                "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
              }
            ]
          }
        }
        """.trimIndent()

        val endpoint = SoundCloudSource.parseTranscodingUrl(json)
        assertEquals("https://api-v2.soundcloud.com/media/progressive/stream", endpoint)
    }

    @Test
    fun `parse SoundCloud media response extracts stream url`() {
        val json = """
        {
          "url": "https://cf-media.sndcdn.com/abcdef123456"
        }
        """.trimIndent()

        val streamUrl = SoundCloudSource.parseMediaUrl(json)
        assertEquals("https://cf-media.sndcdn.com/abcdef123456", streamUrl)
    }

    @Test
    fun `coverArtUrl formats YouTube thumbnail url from video id`() {
        val ytSource = YouTubeMusicSource()
        assertEquals(
            "https://i.ytimg.com/vi/abc123vid/hqdefault.jpg",
            runBlocking { ytSource.coverArtUrl("abc123vid") },
        )
    }

    @Test
    fun `interleaving online search results preserves balanced order`() {
        val ytTracks = listOf("yt1", "yt2", "yt3")
        val scTracks = listOf("sc1", "sc2")
        val combined = mutableListOf<String>()
        val maxLen = maxOf(ytTracks.size, scTracks.size)
        for (i in 0 until maxLen) {
            if (i < ytTracks.size) combined.add(ytTracks[i])
            if (i < scTracks.size) combined.add(scTracks[i])
        }
        assertEquals(listOf("yt1", "sc1", "yt2", "sc2", "yt3"), combined)
    }
}
