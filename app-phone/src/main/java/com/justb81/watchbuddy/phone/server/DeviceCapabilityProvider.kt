package com.justb81.watchbuddy.phone.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator
) {
    fun getCapability(): DeviceCapability {
        val config = llmOrchestrator.selectConfig()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val freeRamMb = (memInfo.availMem / 1_048_576).toInt()

        return DeviceCapability(
            deviceId = Build.ID,
            userName = "user",          // TODO: pull from user preferences / Trakt profile
            deviceName = Build.MODEL,
            llmBackend = config.backend,
            modelQuality = config.qualityScore,
            freeRamMb = freeRamMb,
            isAvailable = true
        )
    }
}
