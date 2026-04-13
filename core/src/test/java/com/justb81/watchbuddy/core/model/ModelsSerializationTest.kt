package com.justb81.watchbuddy.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("Models serialization")
class ModelsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Nested
    @DisplayName("TraktShow")
    inner class TraktShowTest {
        @Test
        fun `round-trip with all fields`() {
            val show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1, slug = "breaking-bad", tmdb = 1396))
            val encoded = json.encodeToString(show)
            val decoded = json.decodeFromString<TraktShow>(encoded)
            assertEquals(show, decoded)
        }

        @Test
        fun `deserializes with null year`() {
            val jsonStr = """{"title":"Test","ids":{"trakt":1}}"""
            val show = json.decodeFromString<TraktShow>(jsonStr)
            assertNull(show.year)
            assertEquals("Test", show.title)
        }

        @Test
        fun `ignores unknown keys`() {
            val jsonStr = """{"title":"Test","year":2024,"ids":{"trakt":1},"unknown_field":"ignored"}"""
            val show = json.decodeFromString<TraktShow>(jsonStr)
            assertEquals("Test", show.title)
        }
    }

    @Nested
    @DisplayName("TraktIds")
    inner class TraktIdsTest {
        @Test
        fun `deserializes with all null optional fields`() {
            val jsonStr = """{}"""
            val ids = json.decodeFromString<TraktIds>(jsonStr)
            assertNull(ids.trakt)
            assertNull(ids.slug)
            assertNull(ids.tvdb)
            assertNull(ids.imdb)
            assertNull(ids.tmdb)
        }

        @Test
        fun `round-trip with partial fields`() {
            val ids = TraktIds(trakt = 42, slug = "test", imdb = "tt1234567")
            val decoded = json.decodeFromString<TraktIds>(json.encodeToString(ids))
            assertEquals(ids, decoded)
        }
    }

    @Nested
    @DisplayName("TraktEpisode")
    inner class TraktEpisodeTest {
        @Test
        fun `round-trip with all fields`() {
            val ep = TraktEpisode(season = 2, number = 5, title = "Madrigal", ids = TraktIds(trakt = 99))
            val decoded = json.decodeFromString<TraktEpisode>(json.encodeToString(ep))
            assertEquals(ep, decoded)
        }

        @Test
        fun `deserializes with default ids`() {
            val jsonStr = """{"season":1,"number":1}"""
            val ep = json.decodeFromString<TraktEpisode>(jsonStr)
            assertEquals(TraktIds(), ep.ids)
            assertNull(ep.title)
        }
    }

    @Nested
    @DisplayName("TraktWatchedEntry")
    inner class TraktWatchedEntryTest {
        @Test
        fun `round-trip with nested structure`() {
            val entry = TraktWatchedEntry(
                show = TraktShow("Test", 2024, TraktIds(trakt = 1)),
                seasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1, 2, "2024-01-01T00:00:00.000Z")))
                )
            )
            val decoded = json.decodeFromString<TraktWatchedEntry>(json.encodeToString(entry))
            assertEquals(entry, decoded)
        }

        @Test
        fun `deserializes with empty seasons`() {
            val jsonStr = """{"show":{"title":"X","ids":{}}}"""
            val entry = json.decodeFromString<TraktWatchedEntry>(jsonStr)
            assertTrue(entry.seasons.isEmpty())
        }
    }

    @Nested
    @DisplayName("TraktWatchedEpisode")
    inner class TraktWatchedEpisodeTest {
        @Test
        fun `default plays is 1`() {
            val jsonStr = """{"number":3}"""
            val ep = json.decodeFromString<TraktWatchedEpisode>(jsonStr)
            assertEquals(1, ep.plays)
            assertNull(ep.last_watched_at)
        }

        @Test
        fun `round-trip preserves all fields`() {
            val ep = TraktWatchedEpisode(5, 3, "2024-06-15T10:30:00.000Z")
            val decoded = json.decodeFromString<TraktWatchedEpisode>(json.encodeToString(ep))
            assertEquals(ep, decoded)
        }
    }

    @Nested
    @DisplayName("TmdbShow")
    inner class TmdbShowTest {
        @Test
        fun `round-trip with all fields`() {
            val show = TmdbShow(100, "Test", "Overview", "/poster.jpg", "/backdrop.jpg", "2024-01-01")
            val decoded = json.decodeFromString<TmdbShow>(json.encodeToString(show))
            assertEquals(show, decoded)
        }

        @Test
        fun `deserializes with null optional fields`() {
            val jsonStr = """{"id":1,"name":"Minimal"}"""
            val show = json.decodeFromString<TmdbShow>(jsonStr)
            assertNull(show.overview)
            assertNull(show.poster_path)
            assertNull(show.backdrop_path)
            assertNull(show.first_air_date)
        }
    }

    @Nested
    @DisplayName("TmdbEpisode")
    inner class TmdbEpisodeTest {
        @Test
        fun `round-trip`() {
            val ep = TmdbEpisode(1, "Pilot", "First episode", "/still.jpg", 1, 1, "2024-01-01")
            val decoded = json.decodeFromString<TmdbEpisode>(json.encodeToString(ep))
            assertEquals(ep, decoded)
        }

        @Test
        fun `deserializes with null optional fields`() {
            val jsonStr = """{"id":1,"name":"Test","season_number":1,"episode_number":1}"""
            val ep = json.decodeFromString<TmdbEpisode>(jsonStr)
            assertNull(ep.overview)
            assertNull(ep.still_path)
            assertNull(ep.air_date)
        }
    }

    @Nested
    @DisplayName("DeviceCapability")
    inner class DeviceCapabilityTest {
        @Test
        fun `round-trip with all fields`() {
            val cap = DeviceCapability("d1", "user", null, "Pixel", LlmBackend.AICORE, 150, 8000, true)
            val decoded = json.decodeFromString<DeviceCapability>(json.encodeToString(cap))
            assertEquals(cap, decoded)
        }

        @Test
        fun `default isAvailable is true`() {
            val jsonStr = """{"deviceId":"d1","userName":"u","deviceName":"P","llmBackend":"NONE","modelQuality":0,"freeRamMb":1000}"""
            val cap = json.decodeFromString<DeviceCapability>(jsonStr)
            assertTrue(cap.isAvailable)
        }
    }

    @Nested
    @DisplayName("LlmBackend enum")
    inner class LlmBackendTest {
        @ParameterizedTest
        @EnumSource(LlmBackend::class)
        fun `all enum values serialize as strings`(backend: LlmBackend) {
            val cap = DeviceCapability("d", "u", null, "P", backend, 0, 0, true)
            val encoded = json.encodeToString(cap)
            assertTrue(encoded.contains("\"${backend.name}\""))
        }

        @Test
        fun `has exactly 3 values`() {
            assertEquals(3, LlmBackend.entries.size)
        }
    }

    @Nested
    @DisplayName("ScrobbleCandidate")
    inner class ScrobbleCandidateTest {
        @Test
        fun `round-trip with all fields`() {
            val candidate = ScrobbleCandidate(
                "com.netflix", "Show S01E01", 0.95f,
                TraktShow("Show", 2024, TraktIds()), TraktEpisode(1, 1)
            )
            val decoded = json.decodeFromString<ScrobbleCandidate>(json.encodeToString(candidate))
            assertEquals(candidate, decoded)
        }

        @Test
        fun `deserializes with null optional fields`() {
            val jsonStr = """{"packageName":"com.test","mediaTitle":"Test","confidence":0.5}"""
            val candidate = json.decodeFromString<ScrobbleCandidate>(jsonStr)
            assertNull(candidate.matchedShow)
            assertNull(candidate.matchedEpisode)
        }
    }

    @Nested
    @DisplayName("StreamingService")
    inner class StreamingServiceTest {
        @Test
        fun `round-trip`() {
            val service = StreamingService("netflix", "Netflix", "com.netflix.ninja", "https://netflix.com/{tmdb_id}")
            val decoded = json.decodeFromString<StreamingService>(json.encodeToString(service))
            assertEquals(service, decoded)
        }
    }
}
