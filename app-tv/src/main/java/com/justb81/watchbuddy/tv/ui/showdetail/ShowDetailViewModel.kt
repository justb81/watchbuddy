package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import com.justb81.watchbuddy.core.model.KNOWN_STREAMING_SERVICES
import com.justb81.watchbuddy.core.model.StreamingService
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val streamingPrefs: StreamingPreferencesRepository
) : ViewModel() {

    /**
     * Returns the streaming services to display, filtered and ordered by user preferences.
     * Falls back to all known services if no preference is set.
     */
    val availableServices: Flow<List<StreamingService>> = streamingPrefs.subscribedServiceIds.map { ids ->
        if (ids.isEmpty()) {
            KNOWN_STREAMING_SERVICES
        } else {
            ids.mapNotNull { id -> KNOWN_STREAMING_SERVICES.find { it.id == id } }
        }
    }

    /**
     * Resolves the best deep link for a show based on user's preferred streaming services.
     * Returns the first available service's deep link, respecting priority order.
     */
    fun resolveDeepLink(
        entry: TraktWatchedEntry,
        subscribedServices: List<StreamingService>
    ): String? {
        val tmdbId = entry.show.ids.tmdb ?: return null
        val slug = entry.show.ids.slug ?: entry.show.title.lowercase().replace(" ", "-")

        val service = subscribedServices.firstOrNull()
            ?: KNOWN_STREAMING_SERVICES.firstOrNull()
            ?: return null

        return service.deepLinkTemplate
            .replace("{tmdb_id}", tmdbId.toString())
            .replace("{slug}", slug)
            .replace("{id}", tmdbId.toString())
    }
}
