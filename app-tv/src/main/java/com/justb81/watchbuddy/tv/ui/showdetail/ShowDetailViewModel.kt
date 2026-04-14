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
     * Iterates through subscribed services in priority order and returns the first link that
     * can be generated with the available show IDs.  Services whose templates require a TMDB
     * numeric ID are skipped when [TraktIds.tmdb] is null, allowing slug-only services
     * (Joyn, Prime Video, ZDF) and no-variable services (WaipuTV) to work regardless.
     * Returns null only when no subscribed service can produce a valid link.
     */
    fun resolveDeepLink(
        entry: TraktWatchedEntry,
        subscribedServices: List<StreamingService>
    ): String? {
        val tmdbId = entry.show.ids.tmdb
        val slug   = entry.show.ids.slug ?: entry.show.title.lowercase().replace(" ", "-")

        val servicesToTry = subscribedServices.ifEmpty { KNOWN_STREAMING_SERVICES }

        for (service in servicesToTry) {
            val template = service.deepLinkTemplate
            val needsId  = template.contains("{tmdb_id}") || template.contains("{id}")
            if (needsId && tmdbId == null) continue

            return template
                .replace("{tmdb_id}", tmdbId?.toString() ?: "")
                .replace("{slug}", slug)
                .replace("{id}",     tmdbId?.toString() ?: "")
        }
        return null
    }
}
