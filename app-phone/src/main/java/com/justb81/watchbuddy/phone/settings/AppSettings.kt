package com.justb81.watchbuddy.phone.settings

import com.justb81.watchbuddy.phone.ui.settings.AuthMode

data class AppSettings(
    val authMode: AuthMode = AuthMode.MANAGED,
    val backendUrl: String = "",
    val directClientId: String = "",
    val companionEnabled: Boolean = false,
    val ollamaUrl: String = DEFAULT_OLLAMA_URL,
    val modelBaseUrl: String = ""
) {
    companion object {
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    }
}
