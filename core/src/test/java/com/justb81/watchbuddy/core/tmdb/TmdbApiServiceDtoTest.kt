package com.justb81.watchbuddy.core.tmdb

import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TMDB API DTOs")
class TmdbApiServiceDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Nested
    @DisplayName("TmdbSearchResponse")
    inner class SearchResponseTest {
        @Test
        fun `deserializes with results`() {
            val jsonStr = """{"results":[{"id":1,"name":"Show"}],"total_results":1}"""
            val response = json.decodeFromString<TmdbSearchResponse>(jsonStr)
            assertEquals(1, response.results.size)
            assertEquals(1, response.total_results)
            assertEquals("Show", response.results[0].name)
        }

        @Test
        fun `deserializes with empty results`() {
            val jsonStr = """{"results":[],"total_results":0}"""
            val response = json.decodeFromString<TmdbSearchResponse>(jsonStr)
            assertTrue(response.results.isEmpty())
            assertEquals(0, response.total_results)
        }

        @Test
        fun `default total_results is 0`() {
            val jsonStr = """{"results":[]}"""
            val response = json.decodeFromString<TmdbSearchResponse>(jsonStr)
            assertEquals(0, response.total_results)
        }

        @Test
        fun `round-trip`() {
            val response = TmdbSearchResponse(
                results = listOf(TmdbShow(1, "Test")),
                total_results = 1
            )
            val decoded = json.decodeFromString<TmdbSearchResponse>(json.encodeToString(response))
            assertEquals(response, decoded)
        }
    }

    @Nested
    @DisplayName("TmdbSeasonResponse")
    inner class SeasonResponseTest {
        @Test
        fun `deserializes with episodes`() {
            val jsonStr = """{"id":100,"season_number":2,"episodes":[{"id":1,"name":"Ep1","season_number":2,"episode_number":1}]}"""
            val response = json.decodeFromString<TmdbSeasonResponse>(jsonStr)
            assertEquals(100, response.id)
            assertEquals(2, response.season_number)
            assertEquals(1, response.episodes.size)
        }

        @Test
        fun `round-trip with multiple episodes`() {
            val response = TmdbSeasonResponse(
                id = 50, season_number = 1,
                episodes = listOf(
                    TmdbEpisode(1, "Pilot", "Desc", null, 1, 1),
                    TmdbEpisode(2, "Second", "Desc2", null, 1, 2)
                )
            )
            val decoded = json.decodeFromString<TmdbSeasonResponse>(json.encodeToString(response))
            assertEquals(response, decoded)
        }
    }
}
