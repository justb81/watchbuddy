package com.justb81.watchbuddy.core.trakt

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TokenProxyService DTOs")
class TokenProxyServiceDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ProxyTokenRequest serializes correctly`() {
        val req = ProxyTokenRequest("device-code-123")
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("device-code-123"))
    }

    @Test
    fun `ProxyTokenRequest round-trip`() {
        val req = ProxyTokenRequest("abc")
        assertEquals(req, json.decodeFromString<ProxyTokenRequest>(json.encodeToString(req)))
    }

    @Test
    fun `ProxyRefreshRequest serializes correctly`() {
        val req = ProxyRefreshRequest("refresh-token-xyz")
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("refresh-token-xyz"))
    }

    @Test
    fun `ProxyRefreshRequest round-trip`() {
        val req = ProxyRefreshRequest("rt")
        assertEquals(req, json.decodeFromString<ProxyRefreshRequest>(json.encodeToString(req)))
    }

    @Test
    fun `ProxyTokenResponse deserializes all fields`() {
        val jsonStr = """{"access_token":"at","refresh_token":"rt","expires_in":7776000,"token_type":"Bearer","scope":"public"}"""
        val resp = json.decodeFromString<ProxyTokenResponse>(jsonStr)
        assertEquals("at", resp.access_token)
        assertEquals("rt", resp.refresh_token)
        assertEquals(7776000, resp.expires_in)
        assertEquals("Bearer", resp.token_type)
        assertEquals("public", resp.scope)
    }

    @Test
    fun `ProxyTokenResponse round-trip`() {
        val resp = ProxyTokenResponse("access", "refresh", 3600, "Bearer", "public")
        assertEquals(resp, json.decodeFromString<ProxyTokenResponse>(json.encodeToString(resp)))
    }
}
