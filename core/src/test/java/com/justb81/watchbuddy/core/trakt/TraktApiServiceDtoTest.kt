package com.justb81.watchbuddy.core.trakt

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Trakt API DTOs")
class TraktApiServiceDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Nested
    @DisplayName("DeviceCodeRequest")
    inner class DeviceCodeRequestTest {
        @Test
        fun `serializes correctly`() {
            val req = DeviceCodeRequest("my-client-id")
            val encoded = json.encodeToString(req)
            assertTrue(encoded.contains("my-client-id"))
        }

        @Test
        fun `round-trip`() {
            val req = DeviceCodeRequest("abc123")
            assertEquals(req, json.decodeFromString<DeviceCodeRequest>(json.encodeToString(req)))
        }
    }

    @Nested
    @DisplayName("DeviceCodeResponse")
    inner class DeviceCodeResponseTest {
        @Test
        fun `deserializes all fields`() {
            val jsonStr = """{"device_code":"dc","user_code":"1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}"""
            val resp = json.decodeFromString<DeviceCodeResponse>(jsonStr)
            assertEquals("dc", resp.device_code)
            assertEquals("1234", resp.user_code)
            assertEquals("https://trakt.tv/activate", resp.verification_url)
            assertEquals(600, resp.expires_in)
            assertEquals(5, resp.interval)
        }
    }

    @Nested
    @DisplayName("DeviceTokenRequest")
    inner class DeviceTokenRequestTest {
        @Test
        fun `includes client_secret`() {
            val req = DeviceTokenRequest("code", "client_id", "secret")
            val encoded = json.encodeToString(req)
            assertTrue(encoded.contains("secret"))
        }

        @Test
        fun `round-trip`() {
            val req = DeviceTokenRequest("c", "id", "sec")
            assertEquals(req, json.decodeFromString<DeviceTokenRequest>(json.encodeToString(req)))
        }
    }

    @Nested
    @DisplayName("DeviceTokenResponse")
    inner class DeviceTokenResponseTest {
        @Test
        fun `round-trip`() {
            val resp = DeviceTokenResponse("token", "Bearer", 7776000, "refresh", "public")
            assertEquals(resp, json.decodeFromString<DeviceTokenResponse>(json.encodeToString(resp)))
        }
    }

    @Nested
    @DisplayName("RefreshTokenRequest")
    inner class RefreshTokenRequestTest {
        @Test
        fun `default grant_type is refresh_token`() {
            val req = RefreshTokenRequest("rt", "cid", "csec")
            assertEquals("refresh_token", req.grant_type)
        }

        @Test
        fun `round-trip preserves grant_type`() {
            val req = RefreshTokenRequest("rt", "cid", "csec")
            val decoded = json.decodeFromString<RefreshTokenRequest>(json.encodeToString(req))
            assertEquals("refresh_token", decoded.grant_type)
        }
    }

    @Nested
    @DisplayName("TraktUserProfile")
    inner class TraktUserProfileTest {
        @Test
        fun `deserializes with nested images`() {
            val jsonStr = """{"username":"user1","name":"User One","vip":true,"images":{"avatar":{"full":"https://example.com/avatar.jpg"}}}"""
            val profile = json.decodeFromString<TraktUserProfile>(jsonStr)
            assertEquals("user1", profile.username)
            assertEquals("User One", profile.name)
            assertTrue(profile.vip)
            assertEquals("https://example.com/avatar.jpg", profile.images?.avatar?.full)
        }

        @Test
        fun `deserializes with null optional fields`() {
            val jsonStr = """{"username":"minimal"}"""
            val profile = json.decodeFromString<TraktUserProfile>(jsonStr)
            assertNull(profile.name)
            assertFalse(profile.vip)
            assertNull(profile.images)
        }
    }

    @Nested
    @DisplayName("ScrobbleBody")
    inner class ScrobbleBodyTest {
        @Test
        fun `serializes with progress`() {
            val body = ScrobbleBody(
                show = TraktShow("Test", 2024, TraktIds()),
                episode = TraktEpisode(1, 1),
                progress = 50.5f
            )
            val encoded = json.encodeToString(body)
            assertTrue(encoded.contains("50.5"))
        }

        @Test
        fun `round-trip`() {
            val body = ScrobbleBody(
                show = TraktShow("Show", null, TraktIds(trakt = 1)),
                episode = TraktEpisode(2, 3, "Title"),
                progress = 100f
            )
            assertEquals(body, json.decodeFromString<ScrobbleBody>(json.encodeToString(body)))
        }
    }

    @Nested
    @DisplayName("ScrobbleResponse")
    inner class ScrobbleResponseTest {
        @Test
        fun `round-trip with optional id`() {
            val resp = ScrobbleResponse(
                id = 12345L,
                action = "start",
                progress = 0f,
                show = TraktShow("Test", null, TraktIds()),
                episode = TraktEpisode(1, 1)
            )
            assertEquals(resp, json.decodeFromString<ScrobbleResponse>(json.encodeToString(resp)))
        }

        @Test
        fun `deserializes with null id`() {
            val jsonStr = """{"action":"stop","progress":100.0,"show":{"title":"X","ids":{}},"episode":{"season":1,"number":1}}"""
            val resp = json.decodeFromString<ScrobbleResponse>(jsonStr)
            assertNull(resp.id)
            assertEquals("stop", resp.action)
        }
    }

    @Nested
    @DisplayName("TraktSearchResult")
    inner class TraktSearchResultTest {
        @Test
        fun `deserializes with null show`() {
            val jsonStr = """{"type":"show"}"""
            val result = json.decodeFromString<TraktSearchResult>(jsonStr)
            assertNull(result.show)
            assertNull(result.score)
        }

        @Test
        fun `round-trip with all fields`() {
            val result = TraktSearchResult("show", 95.5f, TraktShow("Test", null, TraktIds()))
            assertEquals(result, json.decodeFromString<TraktSearchResult>(json.encodeToString(result)))
        }
    }
}
