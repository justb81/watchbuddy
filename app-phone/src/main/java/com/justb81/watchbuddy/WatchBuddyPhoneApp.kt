package com.justb81.watchbuddy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.justb81.watchbuddy.phone.service.CompanionService
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WatchBuddyPhoneApp : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        autoStartCompanionIfEnabled()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CompanionService.CHANNEL_ID,
                getString(R.string.companion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.companion_channel_description)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun autoStartCompanionIfEnabled() {
        appScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.companionEnabled) {
                CompanionService.start(this@WatchBuddyPhoneApp)
            }
        }
    }
}
