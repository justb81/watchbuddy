package com.justb81.watchbuddy.phone.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktUserProfile
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator,
    private val traktApiService: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val stateManager: CompanionStateManager
) {
    private var cachedProfile: TraktUserProfile? = null

    private suspend fun getCachedProfile(): TraktUserProfile? {
        cachedProfile?.let { return it }
        val token = tokenRepository.getAccessToken() ?: return null
        return try {
            traktApiService.getProfile("Bearer $token").also { cachedProfile = it }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCapability(): DeviceCapability {
        val config = llmOrchestrator.selectConfig()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val freeRamMb = (memInfo.availMem / 1_048_576).toInt()

        val profile = getCachedProfile()
        val settings = settingsRepository.settings.first()
        val tmdbKey = settingsRepository.getTmdbApiKey().first()

        val displayName = settings.displayNameOverride
            .ifBlank { profile?.username ?: "user" }

        return DeviceCapability(
            deviceId = Build.ID,
            userName = displayName,
            userAvatarUrl = resolveAvatarUrl(settings, profile),
            deviceName = Build.MODEL,
            llmBackend = config.backend,
            modelQuality = config.qualityScore,
            freeRamMb = freeRamMb,
            isAvailable = true,
            tmdbConfigured = tmdbKey.isNotBlank(),
            tmdbApiKey = tmdbKey.ifBlank { null },
            avatarSource = settings.avatarSource
        )
    }

    private fun resolveAvatarUrl(settings: AppSettings, profile: TraktUserProfile?): String? =
        when (settings.avatarSource) {
            AvatarSource.TRAKT -> profile?.images?.avatar?.full
            AvatarSource.GENERATED -> null
            AvatarSource.CUSTOM -> {
                val ipv4 = stateManager.wifiIpv4.value
                if (ipv4.isNullOrBlank()) {
                    // Off-Wi-Fi the /capability endpoint is unreachable anyway,
                    // but without a bound IP there is no way to advertise the
                    // custom avatar URL. Fall back to a null URL — the TV will
                    // render initials from userName.
                    null
                } else {
                    "http://$ipv4:${CompanionHttpServer.PORT}/avatar?v=${settings.customAvatarVersion}"
                }
            }
        }

    fun invalidateCache() {
        cachedProfile = null
    }
}
