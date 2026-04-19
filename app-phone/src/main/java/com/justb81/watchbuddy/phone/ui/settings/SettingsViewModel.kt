package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
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
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.service.CompanionService
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.llm.ModelDownloadWorker
import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.AvatarImageStore
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

enum class AuthMode { MANAGED, SELF_HOSTED, DIRECT }

data class SettingsUiState(
    val traktUsername: String?     = null,
    val displayNameOverride: String = "",
    val avatarSource: AvatarSource = AvatarSource.TRAKT,
    val hasCustomAvatar: Boolean   = false,
    /** Bumped every time a new custom photo lands on disk so Coil-backed previews invalidate. */
    val customAvatarVersion: Long  = 0L,
    val customAvatarImportError: Boolean = false,
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
    private val avatarImageStore: AvatarImageStore,
    @Named("managedBackendAvailable") private val managedBackendAvailable: Boolean
) : AndroidViewModel(application) {

    private inline fun <T> initSafely(label: String, default: T, block: () -> T): T =
        runCatching(block).onFailure { DiagnosticLog.error(TAG, "$label threw", it) }.getOrDefault(default)

    private val hasBundledTmdb = initSafely("hasDefaultTmdbApiKey", false) {
        settingsRepository.hasDefaultTmdbApiKey()
    }

    private val initialModelReady = initSafely("modelReady.value", false) {
        settingsRepository.modelReady.value
    }

    private val _uiState = MutableStateFlow(SettingsUiState(
        llmBackend = application.getString(R.string.settings_llm_detecting),
        llmReady = initialModelReady,
        managedTraktAvailable = managedBackendAvailable,
        buildHasBundledTmdbKey = hasBundledTmdb,
        useBundledTmdbKey = hasBundledTmdb,  // start with bundled if available, corrected after load
        forceShowAdvanced = !managedBackendAvailable || !hasBundledTmdb
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Safety-net exception handler for every coroutine launched from this ViewModel.
     *
     * Three previous force-close bugs (#168, #177, #196) were all caused by a single
     * unguarded [viewModelScope.launch] in the Settings flow.  Each fix added a try/catch
     * to the specific offender, which works until the next coroutine is added and someone
     * forgets the pattern.  This handler ensures that *any* uncaught exception in a
     * coroutine launched via [launchSafe] is logged and swallowed instead of propagating
     * to the JVM's default uncaught exception handler (which force-closes the app).
     *
     * Exceptions are *also* written to [DiagnosticLog] so they show up in shared crash
     * reports even though they don't force-close the process.  The silent swallow in
     * earlier versions made it impossible to tell the difference between "nothing
     * happened" and "something broke invisibly" — now every swallowed failure is
     * visible in the exported diagnostic report.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in SettingsViewModel coroutine", throwable)
        DiagnosticLog.error(TAG, "swallowed coroutine exception", throwable)
    }

    /** Launch a coroutine under [viewModelScope] guarded by [coroutineExceptionHandler]. */
    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(coroutineExceptionHandler, block = block)

    init {
        loadPersistedSettings()
        loadTraktUsername()
        detectLlm()
        observeModelReadyState()
        observeDownloadProgress()
    }

    private fun loadTraktUsername() {
        launchSafe {
            try {
                val accessToken = tokenRepository.getAccessToken() ?: run {
                    Log.d(TAG, "loadTraktUsername: no token, skipping")
                    return@launchSafe
                }
                val profile = traktApi.getProfile("Bearer $accessToken")
                _uiState.value = _uiState.value.copy(traktUsername = profile.username)
                DiagnosticLog.event(TAG, "loadTraktUsername:ok user=${profile.username}")
            } catch (e: Exception) {
                // Keystore unavailable, token expired, or network error — keep "Not connected"
                DiagnosticLog.warn(TAG, "loadTraktUsername:failed", e)
            }
        }
    }

    private fun loadPersistedSettings() {
        launchSafe {
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
                    forceShowAdvanced = !managedBackendAvailable || !buildHasBundled,
                    displayNameOverride = saved.displayNameOverride,
                    avatarSource = saved.avatarSource,
                    hasCustomAvatar = avatarImageStore.exists(),
                    customAvatarVersion = saved.customAvatarVersion
                )
                Log.d(TAG, "loadPersistedSettings: authMode=$resolvedAuthMode tmdbConnected=${saved.tmdbApiKey.isNotBlank()}")
            } catch (e: Exception) {
                // Settings failed to load (e.g. Keystore unavailable) — keep defaults.
                // App remains usable; user can still configure settings manually.
                DiagnosticLog.error(TAG, "loadPersistedSettings:failed", e)
            }
        }
    }

    private fun detectLlm() {
        launchSafe {
            try {
                val config = llmOrchestrator.selectConfig()
                _uiState.value = _uiState.value.copy(
                    llmBackend  = config.backend.name,
                    llmModelName = config.modelVariant?.fileName,
                    llmReady    = settingsRepository.modelReady.value
                )
                Log.d(TAG, "detectLlm: backend=${config.backend.name} model=${config.modelVariant?.fileName}")
            } catch (e: Exception) {
                // LLM detection failed (e.g. system service unavailable) — keep default state.
                DiagnosticLog.error(TAG, "detectLlm:failed", e)
            }
        }
    }

    private fun observeModelReadyState() {
        launchSafe {
            settingsRepository.modelReady
                .catch { e ->
                    DiagnosticLog.error(TAG, "observeModelReadyState:flow-error", e)
                }
                .collect { ready ->
                    _uiState.value = _uiState.value.copy(llmReady = ready)
                }
        }
    }

    private fun observeDownloadProgress() {
        launchSafe {
            // WorkManager's flow can throw at subscription (WM not yet initialized,
            // SQLite IO error) or mid-stream.  .catch {} contains the failure so the
            // Settings screen still opens even when the WorkManager backend is broken.
            workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
                .catch { e ->
                    DiagnosticLog.error(TAG, "observeDownloadProgress:flow-error", e)
                }
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
        launchSafe {
            try {
                val current = settingsRepository.settings.first()
                val key = _uiState.value.tmdbApiKey
                settingsRepository.saveSettings(current.copy(tmdbApiKey = key))
                _uiState.value = _uiState.value.copy(
                    tmdbConnected = key.isNotBlank(),
                    defaultTmdbApiKeyAvailable = key.isBlank() && current.defaultTmdbApiKeyAvailable,
                    saveSuccess = true
                )
            } catch (e: Exception) {
                // Persistence failed (DataStore IO, Keystore) — user can retry; no crash.
                DiagnosticLog.error(TAG, "saveTmdbApiKey:failed", e)
            }
        }
    }

    fun disconnectTmdb() {
        launchSafe {
            try {
                val current = settingsRepository.settings.first()
                settingsRepository.saveSettings(current.copy(tmdbApiKey = ""))
                _uiState.value = _uiState.value.copy(
                    tmdbApiKey = "",
                    tmdbConnected = false,
                    defaultTmdbApiKeyAvailable = current.defaultTmdbApiKeyAvailable
                )
            } catch (e: Exception) {
                // Persistence failed — user can retry; no crash.
                DiagnosticLog.error(TAG, "disconnectTmdb:failed", e)
            }
        }
    }

    fun saveAdvancedSettings() {
        launchSafe {
            try {
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
            } catch (e: Exception) {
                // Persistence or Keystore failure — leave state unchanged; user can retry.
                DiagnosticLog.error(TAG, "saveAdvancedSettings:failed", e)
            }
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
        val previousState = _uiState.value.companionRunning
        val newState = !previousState
        _uiState.value = _uiState.value.copy(companionRunning = newState)
        launchSafe {
            try {
                val current = settingsRepository.settings.first()
                settingsRepository.saveSettings(current.copy(companionEnabled = newState))
                val context = getApplication<Application>()
                if (newState) {
                    CompanionService.start(context)
                } else {
                    CompanionService.stop(context)
                }
            } catch (e: Exception) {
                // Persistence or service start failed — revert optimistic toggle so
                // the UI reflects reality and the user can retry.
                DiagnosticLog.error(TAG, "toggleCompanionService:failed (reverting)", e)
                _uiState.value = _uiState.value.copy(companionRunning = previousState)
            }
        }
    }

    fun disconnectTrakt() {
        try {
            tokenRepository.clearTokens()
        } catch (e: Exception) {
            // Keystore unavailable — tokens are effectively gone anyway
            DiagnosticLog.warn(TAG, "disconnectTrakt: clearTokens failed", e)
        }
        try {
            deviceCapabilityProvider.invalidateCache()
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "disconnectTrakt: invalidateCache failed", e)
        }
        _uiState.value = _uiState.value.copy(traktUsername = null)
        DiagnosticLog.event(TAG, "disconnectTrakt:done")
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

    // ── Identity (display name + avatar source) ───────────────────────────

    fun setDisplayNameOverride(name: String) {
        _uiState.value = _uiState.value.copy(displayNameOverride = name)
        launchSafe {
            runCatching {
                settingsRepository.setIdentity(name, _uiState.value.avatarSource)
                deviceCapabilityProvider.invalidateCache()
            }.onFailure { DiagnosticLog.error(TAG, "setDisplayNameOverride:failed", it) }
        }
    }

    fun setAvatarSource(source: AvatarSource) {
        val previous = _uiState.value.avatarSource
        _uiState.value = _uiState.value.copy(avatarSource = source, customAvatarImportError = false)
        launchSafe {
            runCatching {
                // Leaving CUSTOM? Drop the stored bytes so we don't keep a stale photo around.
                if (previous == AvatarSource.CUSTOM && source != AvatarSource.CUSTOM) {
                    avatarImageStore.clear()
                    _uiState.value = _uiState.value.copy(hasCustomAvatar = false)
                }
                settingsRepository.setIdentity(_uiState.value.displayNameOverride, source)
                deviceCapabilityProvider.invalidateCache()
            }.onFailure { DiagnosticLog.error(TAG, "setAvatarSource:failed", it) }
        }
    }

    /**
     * Handles the Photo Picker callback. [uri] can be null when the user
     * dismisses the picker. On success the avatar file is written, the
     * version is bumped (which invalidates Coil's HTTP cache on the TV),
     * and the avatar source snaps to [AvatarSource.CUSTOM].
     */
    fun onCustomAvatarPicked(uri: Uri?) {
        if (uri == null) return
        launchSafe {
            when (val result = avatarImageStore.writeFromUri(uri)) {
                AvatarImageStore.Result.Ok -> {
                    val version = settingsRepository.bumpCustomAvatarVersion()
                    settingsRepository.setIdentity(
                        _uiState.value.displayNameOverride,
                        AvatarSource.CUSTOM
                    )
                    deviceCapabilityProvider.invalidateCache()
                    _uiState.value = _uiState.value.copy(
                        avatarSource = AvatarSource.CUSTOM,
                        hasCustomAvatar = true,
                        customAvatarVersion = version,
                        customAvatarImportError = false
                    )
                }
                is AvatarImageStore.Result.Failed -> {
                    DiagnosticLog.warn(TAG, "custom avatar import failed: ${result.reason}")
                    _uiState.value = _uiState.value.copy(customAvatarImportError = true)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
