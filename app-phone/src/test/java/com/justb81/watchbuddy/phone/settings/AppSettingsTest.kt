package com.justb81.watchbuddy.phone.settings

import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AppSettings")
class AppSettingsTest {

    @Test
    fun `default authMode is MANAGED`() {
        assertEquals(AuthMode.MANAGED, AppSettings().authMode)
    }

    @Test
    fun `default backendUrl is empty`() {
        assertEquals("", AppSettings().backendUrl)
    }

    @Test
    fun `default directClientId is empty`() {
        assertEquals("", AppSettings().directClientId)
    }

    @Test
    fun `default companionEnabled is false`() {
        assertFalse(AppSettings().companionEnabled)
    }

    @Test
    fun `default tmdbApiKey is empty`() {
        assertEquals("", AppSettings().tmdbApiKey)
    }

    @Test
    fun `default defaultTmdbApiKeyAvailable is false`() {
        assertFalse(AppSettings().defaultTmdbApiKeyAvailable)
    }

    @Test
    fun `defaultTmdbApiKeyAvailable can be set to true`() {
        val settings = AppSettings(defaultTmdbApiKeyAvailable = true)
        assertTrue(settings.defaultTmdbApiKeyAvailable)
    }

    @Test
    fun `copy with defaultTmdbApiKeyAvailable preserves other fields`() {
        val original = AppSettings(
            tmdbApiKey = "my-key",
            defaultTmdbApiKeyAvailable = false
        )
        val modified = original.copy(defaultTmdbApiKeyAvailable = true)
        assertEquals("my-key", modified.tmdbApiKey)
        assertTrue(modified.defaultTmdbApiKeyAvailable)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = AppSettings(authMode = AuthMode.DIRECT, backendUrl = "https://example.com")
        val modified = original.copy(companionEnabled = true)
        assertEquals(AuthMode.DIRECT, modified.authMode)
        assertEquals("https://example.com", modified.backendUrl)
        assertTrue(modified.companionEnabled)
    }
}
