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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "watchbuddy_settings")

data class AppSettings(
    val authMode: AuthMode = AuthMode.MANAGED,
    val backendUrl: String = "",
    val directClientId: String = "",
    val directClientSecret: String = "",
    val companionEnabled: Boolean = true,
    val modelReady: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenRepository: TokenRepository
) {
    private val KEY_AUTH_MODE = stringPreferencesKey("auth_mode")
    private val KEY_BACKEND_URL = stringPreferencesKey("backend_url")
    private val KEY_DIRECT_CLIENT_ID = stringPreferencesKey("direct_client_id")
    private val KEY_COMPANION_ENABLED = booleanPreferencesKey("companion_enabled")
    private val KEY_MODEL_READY = booleanPreferencesKey("model_ready")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            authMode = try {
                AuthMode.valueOf(prefs[KEY_AUTH_MODE] ?: AuthMode.MANAGED.name)
            } catch (_: Exception) { AuthMode.MANAGED },
            backendUrl = prefs[KEY_BACKEND_URL] ?: "",
            directClientId = prefs[KEY_DIRECT_CLIENT_ID] ?: "",
            directClientSecret = tokenRepository.getClientSecret() ?: "",
            companionEnabled = prefs[KEY_COMPANION_ENABLED] ?: true,
            modelReady = prefs[KEY_MODEL_READY] ?: false
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_MODE] = settings.authMode.name
            prefs[KEY_BACKEND_URL] = settings.backendUrl
            prefs[KEY_DIRECT_CLIENT_ID] = settings.directClientId
            prefs[KEY_COMPANION_ENABLED] = settings.companionEnabled
        }
        if (settings.authMode == AuthMode.DIRECT && settings.directClientSecret.isNotBlank()) {
            tokenRepository.saveClientSecret(settings.directClientSecret)
        }
    }

    suspend fun setCompanionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPANION_ENABLED] = enabled
        }
    }

    suspend fun setModelReady(ready: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MODEL_READY] = ready
        }
    }
}
