package com.justb81.watchbuddy.core.justwatch

import com.justb81.watchbuddy.core.deeplink.ProviderCatalogRegistry
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Integration test that validates the [ProviderCatalogRegistry] against TMDB's live
 * `/watch/providers/tv` catalogue.
 *
 * The test is CI-skippable: it requires a TMDB API key available via the
 * `TMDB_API_KEY` environment variable or the `tmdb.api.key` JVM system property.
 * If neither is present the test is skipped via [assumeTrue] so that CI
 * (which doesn't have the key) is never blocked.
 *
 * To run locally:
 *   TMDB_API_KEY=<your-key> ./gradlew :core:test --tests "*JustWatchProviderMapIntegrationTest*"
 * or via Gradle property:
 *   ./gradlew :core:test -Dtmdb.api.key=<your-key> --tests "*JustWatchProviderMapIntegrationTest*"
 */
@Tag("integration")
@DisplayName("ProviderCatalogRegistry — TMDB provider ID validation (integration)")
class JustWatchProviderMapIntegrationTest {

    private fun resolveApiKey(): String? =
        System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("tmdb.api.key")?.takeIf { it.isNotBlank() }

    private fun buildTmdbService(): TmdbApiService =
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(OkHttpClient())
            .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApiService::class.java)

    @Test
    @DisplayName("all mapped TMDB provider IDs exist in the live TMDB provider catalogue")
    fun `all ProviderCatalogRegistry provider IDs are present in TMDB catalogue`() {
        val apiKey = resolveApiKey()
        assumeTrue(apiKey != null, "TMDB API key not available — skipping integration test")

        val service = buildTmdbService()
        val response = runBlocking { service.getAllTvWatchProviders(apiKey = apiKey!!) }

        val tmdbProviderIds = response.results.map { it.providerId }.toSet()
        val mappedIds = ProviderCatalogRegistry.technicalNameToProviderId.values.toSet()

        val missing = mappedIds - tmdbProviderIds
        assertTrue(
            missing.isEmpty(),
            "The following TMDB provider IDs are in ProviderCatalogRegistry but not in " +
                "TMDB's /watch/providers/tv catalogue — they may have been renumbered: $missing"
        )
    }
}
