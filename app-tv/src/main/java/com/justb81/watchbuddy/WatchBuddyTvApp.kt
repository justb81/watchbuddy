package com.justb81.watchbuddy

import android.app.Application
import android.os.Build
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WatchBuddyTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        DiagnosticLog.event(
            "App",
            "TV onCreate ${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )
    }
}
