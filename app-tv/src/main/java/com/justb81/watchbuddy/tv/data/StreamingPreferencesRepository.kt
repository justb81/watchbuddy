package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preferences"
)

@Singleton
class StreamingPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val phoneDiscoveryKey = booleanPreferencesKey("phone_discovery_enabled")
    private val autostartKey = booleanPreferencesKey("autostart_enabled")
    private val showNonInstalledKey = booleanPreferencesKey("show_non_installed_providers")

    /** Whether phone discovery (NSD + BLE) should be active. Defaults to true. */
    val isPhoneDiscoveryEnabled: Flow<Boolean> = context.streamingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[phoneDiscoveryKey] ?: true }

    /** Whether the TV should start discovery on boot via [BootReceiver]. Defaults to false. */
    val isAutostartEnabled: Flow<Boolean> = context.streamingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[autostartKey] ?: false }

    /**
     * Whether the "Available on" provider row should include services whose app is
     * not installed on this TV. Defaults to false (only installed apps shown).
     */
    val showNonInstalledProviders: Flow<Boolean> = context.streamingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[showNonInstalledKey] ?: false }

    suspend fun getShowNonInstalledProviders(): Boolean =
        showNonInstalledProviders.first()

    suspend fun setPhoneDiscoveryEnabled(enabled: Boolean) {
        context.streamingDataStore.edit { prefs -> prefs[phoneDiscoveryKey] = enabled }
    }

    suspend fun setAutostartEnabled(enabled: Boolean) {
        context.streamingDataStore.edit { prefs -> prefs[autostartKey] = enabled }
    }

    suspend fun setShowNonInstalledProviders(show: Boolean) {
        context.streamingDataStore.edit { prefs -> prefs[showNonInstalledKey] = show }
    }
}
