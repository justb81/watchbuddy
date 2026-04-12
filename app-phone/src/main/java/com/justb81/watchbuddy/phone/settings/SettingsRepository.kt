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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenRepository: TokenRepository
) {
    private object Keys {
        val AUTH_MODE = stringPreferencesKey("auth_mode")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val DIRECT_CLIENT_ID = stringPreferencesKey("direct_client_id")
        val COMPANION_ENABLED = booleanPreferencesKey("companion_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            authMode = prefs[Keys.AUTH_MODE]
                ?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
                ?: AuthMode.MANAGED,
            backendUrl = prefs[Keys.BACKEND_URL] ?: "",
            directClientId = prefs[Keys.DIRECT_CLIENT_ID] ?: "",
            companionEnabled = prefs[Keys.COMPANION_ENABLED] ?: false
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_MODE] = settings.authMode.name
            prefs[Keys.BACKEND_URL] = settings.backendUrl
            prefs[Keys.DIRECT_CLIENT_ID] = settings.directClientId
            prefs[Keys.COMPANION_ENABLED] = settings.companionEnabled
        }
    }

    fun getClientSecret(): String = tokenRepository.getClientSecret()

    fun saveClientSecret(secret: String) {
        tokenRepository.saveClientSecret(secret)
    }
}
