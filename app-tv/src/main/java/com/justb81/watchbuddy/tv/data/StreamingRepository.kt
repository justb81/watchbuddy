package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justb81.watchbuddy.core.model.KNOWN_STREAMING_SERVICES
import com.justb81.watchbuddy.core.model.StreamingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streamingDataStore by preferencesDataStore(name = "streaming_prefs")

@Singleton
class StreamingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_SUBSCRIBED_SERVICES = stringSetPreferencesKey("subscribed_services")

    val subscribedServiceIds: Flow<Set<String>> = context.streamingDataStore.data.map { prefs ->
        prefs[KEY_SUBSCRIBED_SERVICES] ?: emptySet()
    }

    val subscribedServices: Flow<List<StreamingService>> = subscribedServiceIds.map { ids ->
        if (ids.isEmpty()) return@map emptyList()
        KNOWN_STREAMING_SERVICES.filter { it.id in ids }
    }

    suspend fun setSubscribedServices(serviceIds: Set<String>) {
        context.streamingDataStore.edit { prefs ->
            prefs[KEY_SUBSCRIBED_SERVICES] = serviceIds
        }
    }

    fun resolveDeepLink(tmdbId: Int, subscribedIds: Set<String>): String? {
        val available = KNOWN_STREAMING_SERVICES.filter { it.id in subscribedIds }
        val bestService = available.firstOrNull() ?: return null
        return bestService.deepLinkTemplate.replace("{tmdb_id}", tmdbId.toString())
    }
}
