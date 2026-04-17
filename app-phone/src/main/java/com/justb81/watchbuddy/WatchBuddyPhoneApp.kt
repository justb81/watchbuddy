package com.justb81.watchbuddy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.service.CompanionService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WatchBuddyPhoneApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        DiagnosticLog.event(
            "App",
            "Phone onCreate ${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CompanionService.CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CompanionService.CHANNEL_ID,
                getString(R.string.companion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.companion_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(MODEL_DOWNLOAD_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                MODEL_DOWNLOAD_CHANNEL_ID,
                getString(R.string.model_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.model_download_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val MODEL_DOWNLOAD_CHANNEL_ID = "model_download"
    }
}
