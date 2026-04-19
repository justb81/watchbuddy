package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preferences"
)

/** Hilt-accessible accessor used by [AppModule] to provide the singleton DataStore. */
internal fun Context.getStreamingDataStore(): DataStore<Preferences> = streamingDataStore

@Singleton
class StreamingPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    private val subscribedKey = stringSetPreferencesKey("subscribed_service_ids")
    private val orderKey = stringPreferencesKey("service_order")
    private val phoneDiscoveryEnabledKey = booleanPreferencesKey("phone_discovery_enabled")
    private val autostartEnabledKey = booleanPreferencesKey("autostart_enabled")

    /**
     * Emits the ordered list of subscribed service IDs.
     * Empty list means no preference has been set (show all as fallback).
     */
    val subscribedServiceIds: Flow<List<String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val ids = prefs[subscribedKey] ?: emptySet()
            val order = prefs[orderKey]?.split(",") ?: emptyList()
            if (order.isNotEmpty()) {
                ids.sortedBy { id -> order.indexOf(id).let { if (it == -1) Int.MAX_VALUE else it } }
            } else {
                ids.toList()
            }
        }

    suspend fun setSubscribedServices(orderedIds: List<String>) {
        dataStore.edit { prefs ->
            prefs[subscribedKey] = orderedIds.toSet()
            prefs[orderKey] = orderedIds.joinToString(",")
        }
    }

    /** Emits whether phone discovery is active. Defaults to true. */
    val isPhoneDiscoveryEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[phoneDiscoveryEnabledKey] ?: true }

    suspend fun setPhoneDiscoveryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[phoneDiscoveryEnabledKey] = enabled }
    }

    /** Emits whether discovery should start automatically at TV boot. Defaults to false. */
    val isAutostartEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[autostartEnabledKey] ?: false }

    suspend fun setAutostartEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[autostartEnabledKey] = enabled }
    }
}
