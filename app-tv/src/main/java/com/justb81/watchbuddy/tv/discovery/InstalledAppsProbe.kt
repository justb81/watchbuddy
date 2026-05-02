package com.justb81.watchbuddy.tv.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.justb81.watchbuddy.core.deeplink.ProviderCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries [PackageManager] for installed streaming app packages and caches the result
 * for the lifetime of the session. The cache is invalidated automatically when any app
 * is installed or removed (via [Intent.ACTION_PACKAGE_ADDED] / [ACTION_PACKAGE_REMOVED]).
 *
 * On Android 11+ the set of visible packages is restricted. Each known streaming app's
 * package name must appear in a `<queries>` block in the TV app's AndroidManifest.xml
 * for PackageManager to report it.
 */
@Singleton
class InstalledAppsProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    lifecycleOwner: LifecycleOwner,
) : DefaultLifecycleObserver {

    private val cachedPackages = AtomicReference<Set<String>?>()

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            cachedPackages.set(null)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.unregisterReceiver(packageChangeReceiver)
        owner.lifecycle.removeObserver(this)
    }

    fun isInstalled(packageName: String): Boolean = getInstalledPackages().contains(packageName)

    fun getInstalledPackages(): Set<String> = cachedPackages.get() ?: loadAndCache()

    private fun loadAndCache(): Set<String> {
        val pm = context.packageManager
        val packages = ProviderCatalog.knownPackageNames.filterTo(mutableSetOf()) { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        cachedPackages.compareAndSet(null, packages)
        return cachedPackages.get() ?: packages
    }
}
