package com.justb81.watchbuddy.core.model

import com.justb81.watchbuddy.core.network.WatchBuddyJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderCatalogModels")
class ProviderCatalogModelsTest {

    @Test
    fun `decodes v8 catalog with tmdbProviderIds list`() {
        val json = """
            {
              "version": 8,
              "lastUpdated": "2026-05-10T00:00:00Z",
              "providers": [
                {
                  "tmdbProviderIds": [119, 9],
                  "name": "Amazon Prime Video",
                  "regions": ["*"],
                  "androidPackages": {
                    "tv": ["com.amazon.amazonvideo.livingroom"],
                    "phone": ["com.amazon.avod.thirdpartyclient"]
                  },
                  "justWatchTechnicalNames": ["amazonprime", "amazonprimevideowithads"]
                }
              ]
            }
        """.trimIndent()

        val snapshot = WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), json)

        assertEquals(8, snapshot.version)
        assertEquals(1, snapshot.providers.size)
        val amazon = snapshot.providers.first()
        assertEquals(listOf(119, 9), amazon.tmdbProviderIds)
        assertEquals("Amazon Prime Video", amazon.name)
        assertEquals(listOf("amazonprime", "amazonprimevideowithads"), amazon.justWatchTechnicalNames)
        assertEquals(listOf("com.amazon.amazonvideo.livingroom"), amazon.androidPackages.tv)
        assertEquals(listOf("com.amazon.avod.thirdpartyclient"), amazon.androidPackages.phone)
    }

    @Test
    fun `decodes catalog with single-element tmdbProviderIds`() {
        val json = """
            {
              "version": 8,
              "lastUpdated": "2026-05-10T00:00:00Z",
              "providers": [
                {
                  "tmdbProviderIds": [8],
                  "name": "Netflix",
                  "regions": ["*"],
                  "androidPackages": {
                    "tv": ["com.netflix.ninja"],
                    "phone": ["com.netflix.mediaclient"]
                  },
                  "justWatchTechnicalNames": ["netflix"]
                }
              ]
            }
        """.trimIndent()

        val snapshot = WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), json)

        assertEquals(1, snapshot.providers.size)
        assertEquals(listOf(8), snapshot.providers.first().tmdbProviderIds)
    }

    @Test
    fun `round-trips a full v8 snapshot`() {
        val original = ProviderCatalogSnapshot(
            version = 8,
            lastUpdated = "2026-05-10T00:00:00Z",
            providers = listOf(
                CatalogProviderEntry(
                    tmdbProviderIds = listOf(119, 9),
                    name = "Amazon Prime Video",
                    regions = listOf("*"),
                    androidPackages = CatalogAndroidPackages(
                        tv = listOf("com.amazon.amazonvideo.livingroom"),
                        phone = listOf("com.amazon.avod.thirdpartyclient"),
                    ),
                    justWatchTechnicalNames = listOf("amazonprime", "amazonprimevideowithads"),
                ),
            ),
        )

        val encoded = WatchBuddyJson.encodeToString(ProviderCatalogSnapshot.serializer(), original)
        val decoded = WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `regions can contain wildcard and specific locales`() {
        val json = """
            {
              "version": 8,
              "lastUpdated": "2026-05-10T00:00:00Z",
              "providers": [
                {
                  "tmdbProviderIds": [2184],
                  "name": "Joyn",
                  "regions": ["DE", "AT", "CH"],
                  "androidPackages": {
                    "tv": ["de.prosiebensat1.joyn.tv"],
                    "phone": ["de.prosiebensat1.joyn"]
                  },
                  "justWatchTechnicalNames": ["joynde"]
                }
              ]
            }
        """.trimIndent()

        val snapshot = WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), json)
        val joyn = snapshot.providers.first()

        assertEquals(listOf(2184), joyn.tmdbProviderIds)
        assertTrue(joyn.regions.containsAll(listOf("DE", "AT", "CH")))
        assertEquals("de.prosiebensat1.joyn.tv", joyn.androidPackages.tv.first())
    }
}
