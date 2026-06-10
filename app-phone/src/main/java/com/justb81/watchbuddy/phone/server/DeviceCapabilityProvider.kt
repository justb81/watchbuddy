package com.justb81.watchbuddy.phone.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.tracking.TrackingProfile
import com.justb81.watchbuddy.core.tracking.TrackingProvider
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator,
    private val trackingProvider: TrackingProvider,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val stateManager: CompanionStateManager
) {
    private val profileMutex = Mutex()

    @Volatile private var cachedProfile: TrackingProfile? = null

    private suspend fun getCachedProfile(): TrackingProfile? {
        // Fast path: @Volatile read avoids mutex acquisition when cache is warm.
        cachedProfile?.let { return it }
        return profileMutex.withLock {
            // Re-check inside the lock: a concurrent call may have already populated it.
            cachedProfile?.let { return@withLock it }
            val token = tokenRepository.getAccessToken() ?: return@withLock null
            try {
                trackingProvider.getProfile("Bearer $token").also { cachedProfile = it }
            } catch (_: Exception) {
                null
            }
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
            avatarSource = settings.avatarSource,
            lastResolvedSessionKey = stateManager.lastResolvedSessionKey.value,
            lastResolvedTraktId = stateManager.lastResolvedTraktId.value,
            countryCode = settings.countryOverride.takeIf { it.length == 2 }
                ?: Locale.getDefault().country.takeIf { it.length == 2 },
        )
    }

    private fun resolveAvatarUrl(settings: AppSettings, profile: TrackingProfile?): String? =
        when (settings.avatarSource) {
            AvatarSource.TRAKT -> profile?.avatarUrl
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
