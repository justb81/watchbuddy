package com.justb81.watchbuddy.core.justwatch

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.CatalogAndroidPackages
import com.justb81.watchbuddy.core.model.CatalogProviderEntry
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("JustWatchPackageMap")
class JustWatchPackageMapTest {

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        resetSnapshot()
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
        resetSnapshot()
    }

    private fun resetSnapshot() {
        val field = JustWatchPackageMap::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        field.set(JustWatchPackageMap, null)
    }

    @ParameterizedTest(name = "{0} → TMDB {1}")
    @CsvSource(
        "netflix, 8",
        "netflixbasicwithads, 8",
        "amazonprime, 119",
        "amazonprimevideowithads, 119",
        "disneyplus, 337",
        "appletvplus, 350",
        "paramountplus, 531",
        "max, 1899",
        "joynde, 2184",
        "daserstemediathek, 195",
        "ardplus, 195",
        "zdf, 231",
        "youtubered, 192",
    )
    fun `resolves known technical names to TMDB provider ids`(technicalName: String, expectedId: Int) {
        assertEquals(expectedId, JustWatchPackageMap.resolveProviderId(technicalName))
    }

    @Test
    fun `youtubeRedResolvesToProvider192`() {
        assertEquals(192, JustWatchPackageMap.resolveProviderId("youtubered"))
    }

    @Test
    fun `resolveProviderId is case-insensitive`() {
        assertEquals(8, JustWatchPackageMap.resolveProviderId("NETFLIX"))
        assertEquals(8, JustWatchPackageMap.resolveProviderId("Netflix"))
    }

    @Test
    fun `resolveProviderId returns null for unknown technical name`() {
        assertNull(JustWatchPackageMap.resolveProviderId("unknown_service"))
        assertNull(JustWatchPackageMap.resolveProviderId(""))
        assertNull(JustWatchPackageMap.resolveProviderId("abc"))
    }

    @Test
    fun `resolveProviderId emits a WARN diagnostic for unknown technical names`() {
        JustWatchPackageMap.resolveProviderId("new_streaming_service")

        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN && it.message.contains("new_streaming_service")
        }
        assertTrue(warnings.isNotEmpty(), "Expected a WARN entry for unmapped technicalName")
    }

    @Test
    fun `resolveProviderId does not log a warning for known technical names`() {
        JustWatchPackageMap.resolveProviderId("netflix")

        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN
        }
        assertTrue(warnings.isEmpty(), "Expected no WARN entries when technicalName resolves successfully")
    }

    @Test
    fun `resolveProviderId warning includes the lowercased technical name`() {
        JustWatchPackageMap.resolveProviderId("UNKNOWN_PROVIDER")

        val entry = DiagnosticLog.snapshot().firstOrNull {
            it.level == DiagnosticLog.Level.WARN
        }
        assertTrue(
            entry?.message?.contains("unknown_provider") == true,
            "Warning message should contain the lowercased technicalName"
        )
    }

    @Test
    fun `map does not contain duplicates for known entries`() {
        // Multiple keys sharing a TMDB id (e.g. netflix/netflixbasicwithads → 8) is intentional
        val uniqueEntries = JustWatchPackageMap.technicalNameToProviderId.keys
        assertEquals(uniqueEntries.size, uniqueEntries.toSet().size)
    }

    @Test
    fun `snapshot with merged Amazon entry resolves amazonprime to canonical ID 119`() {
        val snapshot = ProviderCatalogSnapshot(
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

        JustWatchPackageMap.updateFromSnapshot(snapshot)

        assertEquals(119, JustWatchPackageMap.resolveProviderId("amazonprime"))
        assertEquals(119, JustWatchPackageMap.resolveProviderId("amazonprimevideowithads"))
    }

    @Test
    fun `snapshot resolveProviderId uses first tmdbProviderId as canonical`() {
        val snapshot = ProviderCatalogSnapshot(
            version = 8,
            lastUpdated = "2026-05-10T00:00:00Z",
            providers = listOf(
                CatalogProviderEntry(
                    tmdbProviderIds = listOf(8),
                    name = "Netflix",
                    regions = listOf("*"),
                    androidPackages = CatalogAndroidPackages(
                        tv = listOf("com.netflix.ninja"),
                        phone = listOf("com.netflix.mediaclient"),
                    ),
                    justWatchTechnicalNames = listOf("netflix", "netflixbasicwithads"),
                ),
            ),
        )

        JustWatchPackageMap.updateFromSnapshot(snapshot)

        assertEquals(8, JustWatchPackageMap.resolveProviderId("netflix"))
        assertEquals(8, JustWatchPackageMap.resolveProviderId("netflixbasicwithads"))
    }
}
