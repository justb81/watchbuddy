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
    fun `default ollamaUrl is localhost 11434`() {
        assertEquals("http://localhost:11434", AppSettings().ollamaUrl)
    }

    @Test
    fun `DEFAULT_OLLAMA_URL constant`() {
        assertEquals("http://localhost:11434", AppSettings.DEFAULT_OLLAMA_URL)
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
