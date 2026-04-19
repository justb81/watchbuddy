package com.justb81.watchbuddy.phone.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AvatarSource
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenRepository: TokenRepository,
    @Named("defaultTmdbApiKey") private val defaultTmdbApiKey: String
) {
    private object Keys {
        val AUTH_MODE = stringPreferencesKey("auth_mode")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val DIRECT_CLIENT_ID = stringPreferencesKey("direct_client_id")
        val COMPANION_ENABLED = booleanPreferencesKey("companion_enabled")
        val MODEL_DOWNLOAD_URL = stringPreferencesKey("model_download_url")
        val MODEL_READY = booleanPreferencesKey("model_ready")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val DISPLAY_NAME_OVERRIDE = stringPreferencesKey("display_name_override")
        val AVATAR_SOURCE = stringPreferencesKey("avatar_source")
        val CUSTOM_AVATAR_VERSION = longPreferencesKey("custom_avatar_version")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    init {
        scope.launch {
            context.dataStore.data
                .catch { e ->
                    DiagnosticLog.error(TAG, "modelReady flow errored at subscription", e)
                }
                .map { it[Keys.MODEL_READY] ?: false }
                .collect {
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
            modelDownloadUrl = prefs[Keys.MODEL_DOWNLOAD_URL] ?: "",
            tmdbApiKey = prefs[Keys.TMDB_API_KEY] ?: "",
            defaultTmdbApiKeyAvailable = defaultTmdbApiKey.isNotBlank(),
            displayNameOverride = prefs[Keys.DISPLAY_NAME_OVERRIDE] ?: "",
            avatarSource = prefs[Keys.AVATAR_SOURCE]
                ?.let { runCatching { AvatarSource.valueOf(it) }.getOrNull() }
                ?: AvatarSource.TRAKT,
            customAvatarVersion = prefs[Keys.CUSTOM_AVATAR_VERSION] ?: 0L
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_MODE] = settings.authMode.name
            prefs[Keys.BACKEND_URL] = settings.backendUrl
            prefs[Keys.DIRECT_CLIENT_ID] = settings.directClientId
            prefs[Keys.COMPANION_ENABLED] = settings.companionEnabled
            prefs[Keys.MODEL_DOWNLOAD_URL] = settings.modelDownloadUrl
            prefs[Keys.TMDB_API_KEY] = settings.tmdbApiKey
            prefs[Keys.DISPLAY_NAME_OVERRIDE] = settings.displayNameOverride
            prefs[Keys.AVATAR_SOURCE] = settings.avatarSource.name
            prefs[Keys.CUSTOM_AVATAR_VERSION] = settings.customAvatarVersion
        }
    }

    /** Updates the identity override fields without touching unrelated settings. */
    suspend fun setIdentity(
        displayNameOverride: String,
        avatarSource: AvatarSource
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME_OVERRIDE] = displayNameOverride
            prefs[Keys.AVATAR_SOURCE] = avatarSource.name
        }
    }

    /**
     * Atomically bumps [Keys.CUSTOM_AVATAR_VERSION] by one after a new custom
     * photo has been written to disk and returns the new value. The version
     * is what `/avatar` exposes as its ETag and what appears in the URL the
     * phone advertises via `/capability`, so Coil on the TV revalidates.
     */
    suspend fun bumpCustomAvatarVersion(): Long {
        var next = 0L
        context.dataStore.edit { prefs ->
            next = (prefs[Keys.CUSTOM_AVATAR_VERSION] ?: 0L) + 1L
            prefs[Keys.CUSTOM_AVATAR_VERSION] = next
        }
        return next
    }

    fun getClientSecret(): String = try {
        tokenRepository.getClientSecret()
    } catch (e: Exception) {
        DiagnosticLog.error(TAG, "getClientSecret failed (Keystore?)", e)
        ""
    }

    fun saveClientSecret(secret: String) {
        try {
            tokenRepository.saveClientSecret(secret)
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "saveClientSecret failed (Keystore?)", e)
        }
    }

    /**
     * Returns the effective TMDB API key: the user's custom key when set, otherwise the
     * default key baked in at build time.  Returns an empty string when neither is available.
     */
    fun getTmdbApiKey(): Flow<String> = context.dataStore.data.map { prefs ->
        val userKey = prefs[Keys.TMDB_API_KEY] ?: ""
        userKey.ifBlank { defaultTmdbApiKey }
    }

    /** True when a default TMDB API key was embedded at build time. */
    fun hasDefaultTmdbApiKey(): Boolean = defaultTmdbApiKey.isNotBlank()

    fun setModelReady(ready: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[Keys.MODEL_READY] = ready
            }
        }
    }

    /** Updates only the companion-enabled flag without touching other settings. */
    suspend fun setCompanionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.COMPANION_ENABLED] = enabled
        }
    }

    /** Absolute path where downloaded model files are stored. */
    fun modelDir(): File = File(context.filesDir, "llm_models").also { it.mkdirs() }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
