package com.justb81.watchbuddy

import android.app.Application
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WatchBuddyTvApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Install the crash reporter as early as possible so failures inside
        // Application.super.onCreate() (e.g. Hilt component build) and the
        // first Activity's super.onCreate() are captured. Installing only in
        // Application.onCreate() is too late — the reporter is still null when
        // the Hilt singleton graph is assembled.
        runCatching { CrashReporter.install(base) }
        DiagnosticLog.event("App", "TV attachBaseContext")
    }

    override fun onCreate() {
        DiagnosticLog.event("App", "TV onCreate entered")
        try {
            super.onCreate()
            DiagnosticLog.event(
                "App",
                "TV onCreate ${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
            )
        } catch (t: Throwable) {
            DiagnosticLog.error("App", "TV onCreate failed", t)
            throw t
        }
    }
}
