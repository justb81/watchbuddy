package com.justb81.watchbuddy.phone.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktUserProfile
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository
) {
    private var cachedProfile: TraktUserProfile? = null
    private var profileFetchedAt: Long = 0L

    suspend fun getCapability(): DeviceCapability {
        val config = llmOrchestrator.selectConfig()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val freeRamMb = (memInfo.availMem / 1_048_576).toInt()

        val profile = getCachedProfile()

        return DeviceCapability(
            deviceId = Build.ID,
            userName = profile?.username ?: "Unknown",
            deviceName = Build.MODEL,
            llmBackend = config.backend,
            modelQuality = config.qualityScore,
            freeRamMb = freeRamMb,
            isAvailable = true
        )
    }

    private suspend fun getCachedProfile(): TraktUserProfile? {
        val now = System.currentTimeMillis()
        if (cachedProfile != null && now - profileFetchedAt < CACHE_TTL) {
            return cachedProfile
        }
        val token = tokenRepository.getAccessToken() ?: return cachedProfile
        return try {
            traktApi.getProfile("Bearer $token").also {
                cachedProfile = it
                profileFetchedAt = now
            }
        } catch (_: Exception) {
            cachedProfile
        }
    }

    fun invalidateCache() {
        cachedProfile = null
        profileFetchedAt = 0L
    }

    companion object {
        private const val CACHE_TTL = 60 * 60 * 1000L // 1 hour
    }
}
