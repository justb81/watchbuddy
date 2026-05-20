package com.justb81.watchbuddy.tv.di

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.justb81.watchbuddy.tv.data.ProviderCatalogCacheDao
import com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Token proxy backend URL — the TV app uses no token proxy, so this is blank.
     * Satisfies NetworkModule's constructor requirement; no token requests are made from TV.
     */
    @Suppress("FunctionOnlyReturningConstant")
    @Provides
    @Singleton
    fun provideTokenBackendUrl(): String = ""

    /**
     * Trakt Client ID — the TV app never calls the Trakt API directly
     * (all Trakt operations go through the phone proxy), so this is blank.
     * Satisfies NetworkModule's constructor requirement; no `trakt-api-key` header is attached.
     */
    @Suppress("FunctionOnlyReturningConstant")
    @Provides
    @Singleton
    @Named("traktClientId")
    fun provideTraktClientId(): String = ""

    /**
     * Application-level [CoroutineScope] backed by a [SupervisorJob] so that
     * individual child failures do not cancel the whole scope. Used by
     * [com.justb81.watchbuddy.tv.boot.BootReceiver] to outlive the broadcast
     * dispatch window via [android.content.BroadcastReceiver.goAsync].
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideProcessLifecycleOwner(): LifecycleOwner = ProcessLifecycleOwner.get()

    /**
     * TV provider catalog repository — fetches catalog from the best-connected phone,
     * persists in Room, and injects into [com.justb81.watchbuddy.core.deeplink.ProviderCatalogRegistry].
     * Falls back to [com.justb81.watchbuddy.core.deeplink.ProviderCatalogRegistry.BUNDLED_SNAPSHOT]
     * when no phone is reachable.
     */
    @Provides
    @Singleton
    fun provideTvProviderCatalogRepository(
        dao: ProviderCatalogCacheDao,
        discoveryManager: PhoneDiscoveryManager,
    ): TvProviderCatalogRepository = TvProviderCatalogRepository(dao, discoveryManager)
}
