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
import com.justb81.watchbuddy.phone.auth.TokenRepository
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

enum class AuthMode { MANAGED, SELF_HOSTED, DIRECT }

data class SettingsUiState(
    val traktUsername: String?     = null,
    val tmdbConnected: Boolean     = false,
    val companionRunning: Boolean  = false,
    val authMode: AuthMode         = AuthMode.MANAGED,
    val customBackendUrl: String   = "",
    val directClientId: String     = "",
    val directClientSecret: String = "",
    val llmBackend: String         = "",
    val llmModelName: String?      = null,
    val llmDownloadProgress: Int?  = null,   // null = not downloading, 0-100 = progress
    val llmReady: Boolean          = false,
    val freeRamMb: Int             = 0,
    val saveSuccess: Boolean       = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val llmOrchestrator: LlmOrchestrator,
    private val tokenRepository: TokenRepository,
    private val deviceCapabilityProvider: DeviceCapabilityProvider,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(SettingsUiState(
        llmBackend = application.getString(R.string.settings_llm_detecting),
        llmReady = settingsRepository.modelReady.value
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPersistedSettings()
        detectLlm()
        observeModelReadyState()
    }

    private fun loadPersistedSettings() {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            val clientSecret = settingsRepository.getClientSecret()
            _uiState.value = _uiState.value.copy(
                authMode = saved.authMode,
                customBackendUrl = saved.backendUrl,
                directClientId = saved.directClientId,
                directClientSecret = clientSecret,
                companionRunning = saved.companionEnabled
            )
        }
    }

    private fun detectLlm() {
        viewModelScope.launch {
            val config = llmOrchestrator.selectConfig()
            _uiState.value = _uiState.value.copy(
                llmBackend  = config.backend.name,
                llmModelName = config.modelVariant?.fileName,
                llmReady    = settingsRepository.modelReady.value
            )
        }
    }

    private fun observeModelReadyState() {
        viewModelScope.launch {
            settingsRepository.modelReady.collect { ready ->
                _uiState.value = _uiState.value.copy(llmReady = ready)
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

    fun saveAdvancedSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveSettings(
                AppSettings(
                    authMode = state.authMode,
                    backendUrl = state.customBackendUrl,
                    directClientId = state.directClientId,
                    companionEnabled = state.companionRunning
                )
            )
            settingsRepository.saveClientSecret(state.directClientSecret)
            _uiState.value = _uiState.value.copy(saveSuccess = true)
        }
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
        // TODO: start/stop CompanionService
    }

    fun disconnectTrakt() {
        tokenRepository.clearTokens()
        deviceCapabilityProvider.invalidateCache()
        _uiState.value = _uiState.value.copy(traktUsername = null)
    }

    fun downloadModel() {
        val config = llmOrchestrator.selectConfig()
        val modelUrl = config.modelVariant?.let { variant ->
            "$MODEL_BASE_URL${variant.fileName}"
        } ?: return // no variant selected → nothing to download

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_URL to modelUrl))
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        // Observe work progress
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo == null) return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(
                            ModelDownloadWorker.KEY_PROGRESS, 0
                        )
                        _uiState.value = _uiState.value.copy(
                            llmDownloadProgress = progress
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.value = _uiState.value.copy(
                            llmDownloadProgress = null,
                            llmReady = true
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        _uiState.value = _uiState.value.copy(
                            llmDownloadProgress = null,
                            llmReady = false
                        )
                    }
                    WorkInfo.State.ENQUEUED -> {
                        _uiState.value = _uiState.value.copy(
                            llmDownloadProgress = 0
                        )
                    }
                    else -> { /* BLOCKED, CANCELLED — no UI update needed */ }
                }
            }
        }
    }

    companion object {
        /**
         * Base URL for MediaPipe/Gemma model downloads.
         * In production this would come from a remote config or the LlmOrchestrator.
         */
        private const val MODEL_BASE_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma4/"
    }
}
