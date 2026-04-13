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
    @param:ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator,
    private val traktApiService: TraktApiService,
    private val tokenRepository: TokenRepository
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

        return DeviceCapability(
            deviceId = Build.ID,
            userName = profile?.username ?: "user",
            userAvatarUrl = profile?.images?.avatar?.full,
            deviceName = Build.MODEL,
            llmBackend = config.backend,
            modelQuality = config.qualityScore,
            freeRamMb = freeRamMb,
            isAvailable = true
        )
    }

    fun invalidateCache() {
        cachedProfile = null
    }
}
