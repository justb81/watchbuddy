package com.justb81.watchbuddy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned provider catalog — source of truth for TMDB provider_id ↔ Android
 * package name ↔ JustWatch technicalName mappings.
 *
 * Served by the backend at GET /provider-catalog, cached by the phone via
 * DataStore (24 h WorkManager refresh), relayed to TV via CompanionHttpServer,
 * and persisted on TV in Room. [com.justb81.watchbuddy.core.deeplink.ProviderCatalogRegistry]
 * reads from this model to serve all provider lookups.
 */
@Serializable
data class ProviderCatalogSnapshot(
    val version: Int,
    val lastUpdated: String,
    val providers: List<CatalogProviderEntry>,
)

@Serializable
data class CatalogProviderEntry(
    @SerialName("tmdbProviderIds") val tmdbProviderIds: List<Int>,
    val name: String,
    val regions: List<String>,
    @SerialName("androidPackages") val androidPackages: CatalogAndroidPackages,
    @SerialName("justWatchTechnicalNames") val justWatchTechnicalNames: List<String>,
)

@Serializable
data class CatalogAndroidPackages(
    val tv: List<String>,
    val phone: List<String>,
)
