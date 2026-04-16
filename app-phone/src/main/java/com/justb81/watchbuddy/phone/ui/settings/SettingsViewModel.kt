package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.service.CompanionService
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.llm.ModelDownloadWorker
import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

enum class AuthMode { MANAGED, SELF_HOSTED, DIRECT }

data class SettingsUiState(
    val traktUsername: String?     = null,
    val tmdbConnected: Boolean     = false,
    val tmdbApiKey: String         = "",
    /** True when the build ships a default TMDB API key and the user has not set a custom one. */
    val defaultTmdbApiKeyAvailable: Boolean = false,
    /** True when the build includes a bundled TMDB key (regardless of whether it is currently active). */
    val buildHasBundledTmdbKey: Boolean = false,
    /** True when the user has selected the "bundled key" radio in advanced settings. */
    val useBundledTmdbKey: Boolean = true,
    val companionRunning: Boolean  = false,
    val authMode: AuthMode         = AuthMode.MANAGED,
    val customBackendUrl: String   = "",
    val directClientId: String     = "",
    val directClientSecret: String = "",
    val llmBackend: String         = "",
    val llmModelName: String?      = null,
    val llmDownloadProgress: Int?  = null,   // null = not downloading, 0-100 = progress
    val llmReady: Boolean          = false,
    val llmValidationFailed: Boolean = false,
    val modelDownloadUrl: String   = "",
    val modelDownloadUrlError: Boolean = false,
    val freeRamMb: Int             = 0,
    val saveSuccess: Boolean       = false,
    /** True when the managed Trakt backend is configured in this build. */
    val managedTraktAvailable: Boolean = true,
    /**
     * True when advanced settings must always be visible (i.e. at least one bundled option
     * is not configured in this build so the user must configure it manually).
     */
    val forceShowAdvanced: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val workManager: WorkManager,
    private val llmOrchestrator: LlmOrchestrator,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val deviceCapabilityProvider: DeviceCapabilityProvider,
    private val settingsRepository: SettingsRepository,
    @Named("managedBackendAvailable") private val managedBackendAvailable: Boolean
) : AndroidViewModel(application) {

    private val hasBundledTmdb = settingsRepository.hasDefaultTmdbApiKey()

    private val _uiState = MutableStateFlow(SettingsUiState(
        llmBackend = application.getString(R.string.settings_llm_detecting),
        llmReady = settingsRepository.modelReady.value,
        managedTraktAvailable = managedBackendAvailable,
        buildHasBundledTmdbKey = hasBundledTmdb,
        useBundledTmdbKey = hasBundledTmdb,  // start with bundled if available, corrected after load
        forceShowAdvanced = !managedBackendAvailable || !hasBundledTmdb
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPersistedSettings()
        loadTraktUsername()
        detectLlm()
        observeModelReadyState()
        observeDownloadProgress()
    }

    private fun loadTraktUsername() {
        viewModelScope.launch {
            try {
                val accessToken = tokenRepository.getAccessToken() ?: return@launch
                val profile = traktApi.getProfile("Bearer $accessToken")
                _uiState.value = _uiState.value.copy(traktUsername = profile.username)
            } catch (_: Exception) {
                // Keystore unavailable, token expired, or network error — keep "Not connected"
            }
        }
    }

    private fun loadPersistedSettings() {
        viewModelScope.launch {
            try {
                val saved = settingsRepository.settings.first()
                val clientSecret = settingsRepository.getClientSecret()
                // If managed backend is unavailable in this build but was previously stored,
                // fall back to DIRECT so the user is not stuck on a non-functional mode.
                val resolvedAuthMode = if (!managedBackendAvailable && saved.authMode == AuthMode.MANAGED) {
                    AuthMode.DIRECT
                } else {
                    saved.authMode
                }
                val buildHasBundled = saved.defaultTmdbApiKeyAvailable  // true = build has a key
                _uiState.value = _uiState.value.copy(
                    authMode = resolvedAuthMode,
                    customBackendUrl = saved.backendUrl,
                    directClientId = saved.directClientId,
                    directClientSecret = clientSecret,
                    companionRunning = saved.companionEnabled,
                    modelDownloadUrl = saved.modelDownloadUrl,
                    tmdbApiKey = saved.tmdbApiKey,
                    tmdbConnected = saved.tmdbApiKey.isNotBlank(),
                    defaultTmdbApiKeyAvailable = buildHasBundled && saved.tmdbApiKey.isBlank(),
                    buildHasBundledTmdbKey = buildHasBundled,
                    useBundledTmdbKey = saved.tmdbApiKey.isBlank() && buildHasBundled,
                    forceShowAdvanced = !managedBackendAvailable || !buildHasBundled
                )
            } catch (_: Exception) {
                // Settings failed to load (e.g. Keystore unavailable) — keep defaults.
                // App remains usable; user can still configure settings manually.
            }
        }
    }

    private fun detectLlm() {
        viewModelScope.launch {
            try {
                val config = llmOrchestrator.selectConfig()
                _uiState.value = _uiState.value.copy(
                    llmBackend  = config.backend.name,
                    llmModelName = config.modelVariant?.fileName,
                    llmReady    = settingsRepository.modelReady.value
                )
            } catch (_: Exception) {
                // LLM detection failed (e.g. system service unavailable) — keep default state.
            }
        }
    }

    private fun observeModelReadyState() {
        viewModelScope.launch {
            settingsRepository.modelReady.collect { ready ->
                _uiState.value = _uiState.value.copy(llmReady = ready)
            }
        }
    }

    private fun observeDownloadProgress() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
                .collect { workInfoList ->
                    val workInfo = workInfoList.firstOrNull() ?: return@collect
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt(
                                ModelDownloadWorker.KEY_PROGRESS, 0
                            )
                            _uiState.value = _uiState.value.copy(llmDownloadProgress = progress)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _uiState.value = _uiState.value.copy(
                                llmDownloadProgress = null,
                                llmReady = true
                            )
                        }
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: ""
                            _uiState.value = _uiState.value.copy(
                                llmDownloadProgress = null,
                                llmValidationFailed = error.startsWith("Validation:")
                            )
                        }
                        WorkInfo.State.CANCELLED -> {
                            _uiState.value = _uiState.value.copy(llmDownloadProgress = null)
                        }
                        WorkInfo.State.ENQUEUED -> {
                            _uiState.value = _uiState.value.copy(llmDownloadProgress = 0)
                        }
                        else -> { /* BLOCKED — no UI update needed */ }
                    }
                }
        }
    }

    fun setAuthMode(mode: AuthMode) {
        _uiState.value = _uiState.value.copy(authMode = mode)
    }

    fun setCustomBackendUrl(url: String) {
        _uiState.value = _uiState.value.copy(customBackendUrl = url)
    }

    fun setDirectClientId(id: String) {
        _uiState.value = _uiState.value.copy(directClientId = id)
    }

    fun setDirectClientSecret(secret: String) {
        _uiState.value = _uiState.value.copy(directClientSecret = secret)
    }

    fun setModelDownloadUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            modelDownloadUrl = url,
            llmValidationFailed = false,
            modelDownloadUrlError = false
        )
    }

    fun setTmdbApiKey(key: String) {
        _uiState.value = _uiState.value.copy(tmdbApiKey = key)
    }

    fun saveTmdbApiKey() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            val key = _uiState.value.tmdbApiKey
            settingsRepository.saveSettings(current.copy(tmdbApiKey = key))
            _uiState.value = _uiState.value.copy(
                tmdbConnected = key.isNotBlank(),
                defaultTmdbApiKeyAvailable = key.isBlank() && current.defaultTmdbApiKeyAvailable,
                saveSuccess = true
            )
        }
    }

    fun disconnectTmdb() {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.saveSettings(current.copy(tmdbApiKey = ""))
            _uiState.value = _uiState.value.copy(
                tmdbApiKey = "",
                tmdbConnected = false,
                defaultTmdbApiKeyAvailable = current.defaultTmdbApiKeyAvailable
            )
        }
    }

    fun saveAdvancedSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            val current = settingsRepository.settings.first()
            // When "bundled" is selected the key is stored as empty (repository falls back to
            // the build-time default); when "own" is selected we persist whatever the user typed.
            val tmdbKeyToSave = if (state.useBundledTmdbKey) "" else state.tmdbApiKey
            settingsRepository.saveSettings(
                current.copy(
                    authMode = state.authMode,
                    backendUrl = state.customBackendUrl,
                    directClientId = state.directClientId,
                    companionEnabled = state.companionRunning,
                    modelDownloadUrl = state.modelDownloadUrl,
                    tmdbApiKey = tmdbKeyToSave
                )
            )
            settingsRepository.saveClientSecret(state.directClientSecret)
            _uiState.value = _uiState.value.copy(
                tmdbConnected = tmdbKeyToSave.isNotBlank(),
                defaultTmdbApiKeyAvailable = tmdbKeyToSave.isBlank() && current.defaultTmdbApiKeyAvailable,
                saveSuccess = true
            )
        }
    }

    /** Toggles whether to use the build-in bundled TMDB key or a user-supplied key. */
    fun setUseBundledTmdbKey(useBundled: Boolean) {
        _uiState.value = _uiState.value.copy(
            useBundledTmdbKey = useBundled,
            // Clear the text-field value when switching to bundled so the save picks up "".
            tmdbApiKey = if (useBundled) "" else _uiState.value.tmdbApiKey
        )
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    fun toggleCompanionService() {
        val newState = !_uiState.value.companionRunning
        _uiState.value = _uiState.value.copy(companionRunning = newState)
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.saveSettings(current.copy(companionEnabled = newState))
        }
        val context = getApplication<Application>()
        if (newState) {
            CompanionService.start(context)
        } else {
            CompanionService.stop(context)
        }
    }

    fun disconnectTrakt() {
        try {
            tokenRepository.clearTokens()
        } catch (_: Exception) {
            // Keystore unavailable — tokens are effectively gone anyway
        }
        deviceCapabilityProvider.invalidateCache()
        _uiState.value = _uiState.value.copy(traktUsername = null)
    }

    fun downloadModel() {
        val config = llmOrchestrator.selectConfig()
        val variant = config.modelVariant ?: return // no variant selected → nothing to download
        val customUrl = _uiState.value.modelDownloadUrl.trim()
        if (customUrl.isNotBlank() && !customUrl.endsWith(".litertlm", ignoreCase = true)) {
            _uiState.value = _uiState.value.copy(modelDownloadUrlError = true)
            return
        }
        val modelUrl = customUrl.takeIf { it.isNotBlank() } ?: variant.downloadUrl
        _uiState.value = _uiState.value.copy(llmValidationFailed = false, modelDownloadUrlError = false)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_URL to modelUrl,
                ModelDownloadWorker.KEY_MODEL_FILENAME to variant.fileName
            ))
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        // Progress is tracked by observeDownloadProgress() running since init
    }

}
