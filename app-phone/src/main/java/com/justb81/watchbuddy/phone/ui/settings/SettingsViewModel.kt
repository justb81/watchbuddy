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
import com.justb81.watchbuddy.phone.service.CompanionService
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
    val llmDownloadProgress: Int?  = null,
    val llmReady: Boolean          = false,
    val freeRamMb: Int             = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val llmOrchestrator: LlmOrchestrator,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState(
        llmBackend = application.getString(R.string.settings_llm_detecting)
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        detectLlm()
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.value = _uiState.value.copy(
                authMode = settings.authMode,
                customBackendUrl = settings.backendUrl,
                directClientId = settings.directClientId,
                directClientSecret = settings.directClientSecret,
                companionRunning = settings.companionEnabled,
                llmReady = settings.modelReady
            )
        }
    }

    private fun detectLlm() {
        viewModelScope.launch {
            val config = llmOrchestrator.selectConfig()
            _uiState.value = _uiState.value.copy(
                llmBackend  = config.backend.name,
                llmModelName = config.modelVariant?.fileName,
                llmReady    = false
            )
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
            settingsRepository.saveSettings(
                AppSettings(
                    authMode = _uiState.value.authMode,
                    backendUrl = _uiState.value.customBackendUrl,
                    directClientId = _uiState.value.directClientId,
                    directClientSecret = _uiState.value.directClientSecret,
                    companionEnabled = _uiState.value.companionRunning
                )
            )
        }
    }

    fun toggleCompanionService() {
        val newState = !_uiState.value.companionRunning
        _uiState.value = _uiState.value.copy(companionRunning = newState)
        val context = getApplication<Application>()
        if (newState) {
            CompanionService.start(context)
        } else {
            CompanionService.stop(context)
        }
        viewModelScope.launch {
            settingsRepository.setCompanionEnabled(newState)
        }
    }

    fun disconnectTrakt() {
        tokenRepository.clearTokens()
        _uiState.value = _uiState.value.copy(traktUsername = null)
    }

    fun downloadModel() {
        val config = llmOrchestrator.selectConfig()
        val variant = config.modelVariant ?: return
        val modelUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/${variant.fileName}"

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_URL to modelUrl,
                ModelDownloadWorker.KEY_FILE_NAME to variant.fileName
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build())
            .addTag(ModelDownloadWorker.WORK_TAG)
            .build()

        val workManager = WorkManager.getInstance(getApplication())
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.WORK_TAG,
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(downloadRequest.id).collect { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        _uiState.value = _uiState.value.copy(llmDownloadProgress = progress)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.value = _uiState.value.copy(
                            llmDownloadProgress = null,
                            llmReady = true
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        _uiState.value = _uiState.value.copy(llmDownloadProgress = null)
                    }
                    else -> {}
                }
            }
        }
    }
}
