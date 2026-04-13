package com.justb81.watchbuddy.phone.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenRepository: TokenRepository
) {
    private object Keys {
        val AUTH_MODE = stringPreferencesKey("auth_mode")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val DIRECT_CLIENT_ID = stringPreferencesKey("direct_client_id")
        val COMPANION_ENABLED = booleanPreferencesKey("companion_enabled")
        val OLLAMA_URL = stringPreferencesKey("ollama_url")
        val MODEL_BASE_URL = stringPreferencesKey("model_base_url")
        val MODEL_READY = booleanPreferencesKey("model_ready")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    init {
        scope.launch {
            context.dataStore.data.map { it[Keys.MODEL_READY] ?: false }.collect {
                _modelReady.value = it
            }
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            authMode = prefs[Keys.AUTH_MODE]
                ?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
                ?: AuthMode.MANAGED,
            backendUrl = prefs[Keys.BACKEND_URL] ?: "",
            directClientId = prefs[Keys.DIRECT_CLIENT_ID] ?: "",
            companionEnabled = prefs[Keys.COMPANION_ENABLED] ?: false,
            ollamaUrl = prefs[Keys.OLLAMA_URL] ?: AppSettings.DEFAULT_OLLAMA_URL,
            modelBaseUrl = prefs[Keys.MODEL_BASE_URL] ?: "",
            tmdbApiKey = prefs[Keys.TMDB_API_KEY] ?: ""
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_MODE] = settings.authMode.name
            prefs[Keys.BACKEND_URL] = settings.backendUrl
            prefs[Keys.DIRECT_CLIENT_ID] = settings.directClientId
            prefs[Keys.COMPANION_ENABLED] = settings.companionEnabled
            prefs[Keys.OLLAMA_URL] = settings.ollamaUrl
            prefs[Keys.MODEL_BASE_URL] = settings.modelBaseUrl
            prefs[Keys.TMDB_API_KEY] = settings.tmdbApiKey
        }
    }

    fun getClientSecret(): String = tokenRepository.getClientSecret()

    fun saveClientSecret(secret: String) {
        tokenRepository.saveClientSecret(secret)
    }

    fun getTmdbApiKey(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TMDB_API_KEY] ?: ""
    }

    fun setModelReady(ready: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.MODEL_READY] = ready
            }
        }
    }

    /** Absolute path where downloaded model files are stored. */
    fun modelDir(): File = File(context.filesDir, "llm_models").also { it.mkdirs() }
}
