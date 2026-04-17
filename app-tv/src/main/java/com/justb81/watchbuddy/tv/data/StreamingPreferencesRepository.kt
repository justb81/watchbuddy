package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

@Singleton
class StreamingPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val subscribedKey = stringSetPreferencesKey("subscribed_service_ids")
    private val orderKey = stringPreferencesKey("service_order")

    /**
     * Emits the ordered list of subscribed service IDs.
     * Empty list means no preference has been set (show all as fallback).
     */
    val subscribedServiceIds: Flow<List<String>> = context.streamingDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            val ids = prefs[subscribedKey] ?: emptySet()
            val order = prefs[orderKey]?.split(",") ?: emptyList()
            // Return IDs sorted by the stored priority order
            if (order.isNotEmpty()) {
                ids.sortedBy { id -> order.indexOf(id).let { if (it == -1) Int.MAX_VALUE else it } }
            } else {
                ids.toList()
            }
        }

    suspend fun setSubscribedServices(orderedIds: List<String>) {
        context.streamingDataStore.edit { prefs ->
            prefs[subscribedKey] = orderedIds.toSet()
            prefs[orderKey] = orderedIds.joinToString(",")
        }
    }
}
