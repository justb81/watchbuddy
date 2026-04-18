package com.justb81.watchbuddy.core.trakt

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("shows/{id}/seasons?extended=episodes DTOs")
class SeasonsEndpointDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `deserializes Trakt seasons payload including specials`() {
        // Minimal representative payload from `shows/:id/seasons?extended=episodes`:
        // one "specials" season (number 0) plus two numbered seasons. Unknown
        // top-level fields (title, overview) must be ignored.
        val payload = """
            [
              {
                "number": 0,
                "title": "Specials",
                "episodes": [
                  {"season": 0, "number": 1, "title": "Prologue", "ids": {"trakt": 5001, "tvdb": 9001}}
                ]
              },
              {
                "number": 1,
                "episodes": [
                  {"season": 1, "number": 1, "title": "Pilot", "ids": {"trakt": 5101}},
                  {"season": 1, "number": 2, "title": "The Second One", "ids": {"trakt": 5102}}
                ]
              },
              {
                "number": 2,
                "episodes": [
                  {"season": 2, "number": 1, "title": "New Beginnings", "ids": {"trakt": 5201}}
                ]
              }
            ]
        """.trimIndent()

        val seasons = json.decodeFromString<List<TraktSeasonWithEpisodes>>(payload)

        assertEquals(3, seasons.size)
        assertEquals(listOf(0, 1, 2), seasons.map { it.number })
        assertEquals(1, seasons[0].episodes.size)
        assertEquals("Prologue", seasons[0].episodes[0].title)
        assertEquals(5001, seasons[0].episodes[0].ids.trakt)
        assertEquals(2, seasons[1].episodes.size)
        assertEquals("The Second One", seasons[1].episodes[1].title)
    }

    @Test
    fun `round-trip preserves episodes`() {
        val original = TraktSeasonWithEpisodes(
            number = 3,
            episodes = listOf(
                TraktEpisode(season = 3, number = 1, title = "Ep one", ids = TraktIds(trakt = 11)),
                TraktEpisode(season = 3, number = 2, title = "Ep two", ids = TraktIds(trakt = 12))
            )
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<TraktSeasonWithEpisodes>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `defaults to empty episodes when field is omitted`() {
        val decoded = json.decodeFromString<TraktSeasonWithEpisodes>("""{"number":4}""")
        assertEquals(4, decoded.number)
        assertTrue(decoded.episodes.isEmpty())
    }
}
