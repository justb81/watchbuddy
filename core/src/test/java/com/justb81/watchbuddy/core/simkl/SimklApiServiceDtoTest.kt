package com.justb81.watchbuddy.core.simkl

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SIMKL API DTOs")
class SimklApiServiceDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Nested
    @DisplayName("SimklPinResponse")
    inner class SimklPinResponseTest {

        @Test
        fun `deserializes all fields`() {
            val raw = """{"user_code":"ABCDEF","verification_url":"https://simkl.com/pin/ABCDEF","expires_in":600,"interval":5}"""
            val dto = json.decodeFromString<SimklPinResponse>(raw)
            assertEquals("ABCDEF", dto.userCode)
            assertEquals("https://simkl.com/pin/ABCDEF", dto.verificationUrl)
            assertEquals(600, dto.expiresIn)
            assertEquals(5, dto.interval)
        }
    }

    @Nested
    @DisplayName("SimklPinPoll")
    inner class SimklPinPollTest {

        @Test
        fun `deserializes pending state (result = KO)`() {
            val raw = """{"result":"KO"}"""
            val dto = json.decodeFromString<SimklPinPoll>(raw)
            assertEquals("KO", dto.result)
            assertNull(dto.accessToken)
        }

        @Test
        fun `deserializes success state (result = OK)`() {
            val raw = """{"result":"OK","access_token":"tok123"}"""
            val dto = json.decodeFromString<SimklPinPoll>(raw)
            assertEquals("OK", dto.result)
            assertEquals("tok123", dto.accessToken)
        }
    }

    @Nested
    @DisplayName("SimklUserSettings")
    inner class SimklUserSettingsTest {

        @Test
        fun `deserializes user with all fields`() {
            val raw = """{"user":{"name":"John","username":"johndoe","avatar":"https://example.com/avatar.jpg"}}"""
            val dto = json.decodeFromString<SimklUserSettings>(raw)
            assertNotNull(dto.user)
            assertEquals("John", dto.user!!.name)
            assertEquals("johndoe", dto.user.username)
            assertEquals("https://example.com/avatar.jpg", dto.user.avatar)
        }

        @Test
        fun `user is null when absent`() {
            val dto = json.decodeFromString<SimklUserSettings>("{}")
            assertNull(dto.user)
        }
    }

    @Nested
    @DisplayName("SimklIds")
    inner class SimklIdsTest {

        @Test
        fun `deserializes simkl field`() {
            val raw = """{"simkl":123,"tmdb":456,"imdb":"tt0000001","tvdb":789,"slug":"test-show"}"""
            val dto = json.decodeFromString<SimklIds>(raw)
            assertEquals(123, dto.simkl)
            assertEquals(456, dto.tmdb)
            assertEquals("tt0000001", dto.imdb)
            assertEquals(789, dto.tvdb)
            assertEquals("test-show", dto.slug)
        }

        @Test
        fun `deserializes simkl_id field`() {
            val raw = """{"simkl_id":999}"""
            val dto = json.decodeFromString<SimklIds>(raw)
            assertNull(dto.simkl)
            assertEquals(999, dto.simklId)
            assertEquals(999, dto.canonicalSimklId)
        }

        @Test
        fun `ignores unknown keys`() {
            val raw = """{"simkl":1,"unknown_field":"value","another_unknown":42}"""
            val dto = json.decodeFromString<SimklIds>(raw)
            assertEquals(1, dto.simkl)
        }
    }

    @Nested
    @DisplayName("SimklAllItems")
    inner class SimklAllItemsTest {

        @Test
        fun `deserializes empty shows list`() {
            val raw = """{"shows":[]}"""
            val dto = json.decodeFromString<SimklAllItems>(raw)
            assertEquals(0, dto.shows.size)
        }

        @Test
        fun `deserializes show item with seasons`() {
            val raw = """
            {
              "shows": [
                {
                  "show": {"title": "Breaking Bad", "year": 2008, "ids": {"simkl": 100, "tmdb": 1396}},
                  "status": "watching",
                  "seasons": [
                    {
                      "number": 1,
                      "episodes": [
                        {"number": 1, "watched_at": "2023-01-01T00:00:00Z"},
                        {"number": 2}
                      ]
                    }
                  ],
                  "last_watched_at": "2023-01-02T00:00:00Z",
                  "watched_episodes_count": 2
                }
              ]
            }
            """.trimIndent()
            val dto = json.decodeFromString<SimklAllItems>(raw)
            assertEquals(1, dto.shows.size)
            val show = dto.shows[0]
            assertEquals("Breaking Bad", show.show.title)
            assertEquals(2008, show.show.year)
            assertEquals(100, show.show.ids.simkl)
            assertEquals("watching", show.status)
            assertEquals(1, show.seasons.size)
            assertEquals(2, show.seasons[0].episodes.size)
            assertEquals("2023-01-01T00:00:00Z", show.seasons[0].episodes[0].watchedAt)
            assertNull(show.seasons[0].episodes[1].watchedAt)
        }
    }

    @Nested
    @DisplayName("SimklSyncResult")
    inner class SimklSyncResultTest {

        @Test
        fun `deserializes added and deleted counts`() {
            val raw = """{"added":{"episodes":5,"shows":1},"deleted":{"episodes":0,"shows":0}}"""
            val dto = json.decodeFromString<SimklSyncResult>(raw)
            assertEquals(5, dto.added?.episodes)
            assertEquals(1, dto.added?.shows)
            assertEquals(0, dto.deleted?.episodes)
        }

        @Test
        fun `deserializes not_found shows`() {
            val raw = """{"not_found":{"shows":[{"ids":{"simkl":999}}]}}"""
            val dto = json.decodeFromString<SimklSyncResult>(raw)
            assertEquals(1, dto.notFound?.shows?.size)
            assertEquals(999, dto.notFound?.shows?.get(0)?.ids?.simkl)
        }

        @Test
        fun `empty response is valid`() {
            val dto = json.decodeFromString<SimklSyncResult>("{}")
            assertNull(dto.added)
            assertNull(dto.deleted)
            assertNull(dto.notFound)
        }
    }

    @Nested
    @DisplayName("SimklSearchResult")
    inner class SimklSearchResultTest {

        @Test
        fun `deserializes full search result`() {
            val raw = """
            {
              "title": "Westworld",
              "year": 2016,
              "ids": {"simkl": 300, "tmdb": 63247, "imdb": "tt0475784"},
              "type": "show",
              "scores": {"best": 0.95}
            }
            """.trimIndent()
            val dto = json.decodeFromString<SimklSearchResult>(raw)
            assertEquals("Westworld", dto.title)
            assertEquals(2016, dto.year)
            assertEquals(300, dto.ids?.simkl)
            assertEquals("show", dto.type)
            assertEquals(0.95f, dto.scores?.best ?: 0f, 0.001f)
        }

        @Test
        fun `nullable fields are absent gracefully`() {
            val dto = json.decodeFromString<SimklSearchResult>("{}")
            assertNull(dto.title)
            assertNull(dto.year)
            assertNull(dto.ids)
            assertNull(dto.type)
            assertNull(dto.scores)
        }
    }
}
