package com.justb81.watchbuddy.core.justwatch

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot

/**
 * Thin façade mapping JustWatch `technicalName` values to TMDB `provider_id` integers.
 *
 * The canonical data comes from the backend-served provider catalog (fetched and
 * cached by [com.justb81.watchbuddy.phone.data.ProviderCatalogRepository] on phone
 * and [com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository] on TV).
 * When no live catalog is injected the object falls back to the bundled snapshot.
 *
 * Entries not present here are silently dropped — they will not produce deep links but
 * TMDB's watch-provider list still renders them with their logo and name.
 */
object JustWatchPackageMap {

    private const val TAG = "JustWatchPackageMap"

    @Volatile private var snapshot: ProviderCatalogSnapshot? = null

    fun updateFromSnapshot(catalog: ProviderCatalogSnapshot) {
        snapshot = catalog
    }

    val technicalNameToProviderId: Map<String, Int>
        get() = snapshot?.let { s ->
            buildMap {
                for (provider in s.providers) {
                    for (name in provider.justWatchTechnicalNames) {
                        put(name.lowercase(), provider.tmdbProviderId)
                    }
                }
            }
        } ?: BUNDLED_MAP

    fun resolveProviderId(technicalName: String): Int? {
        val key = technicalName.lowercase()
        val id = technicalNameToProviderId[key]
        if (id == null) {
            DiagnosticLog.warn(TAG, "unmapped technicalName: $key")
        }
        return id
    }

    private val BUNDLED_MAP: Map<String, Int> = mapOf(
        "netflix" to 8,
        "netflixbasicwithads" to 8,
        "amazonprime" to 119,
        "amazonprimevideowithads" to 119,
        "disneyplus" to 337,
        "appletvplus" to 350,
        "paramountplus" to 531,
        "max" to 1899,
        "joynde" to 2184,
        "daserstemediathek" to 195,
        "ardplus" to 195,
        "zdf" to 231,
        "youtubered" to 192,
        "yti" to 192,
        "yot" to 192,
        "ytv" to 192,
    )
}
