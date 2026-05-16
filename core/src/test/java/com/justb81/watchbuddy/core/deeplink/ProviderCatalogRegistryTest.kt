package com.justb81.watchbuddy.core.deeplink

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.CatalogAndroidPackages
import com.justb81.watchbuddy.core.model.CatalogProviderEntry
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderCatalogRegistry")
class ProviderCatalogRegistryTest {

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
        val field = ProviderCatalogRegistry::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        field.set(ProviderCatalogRegistry, null)
    }

    private fun makeSnapshot(vararg providers: CatalogProviderEntry) = ProviderCatalogSnapshot(
        version = 8,
        lastUpdated = "2026-05-10T00:00:00Z",
        providers = providers.toList(),
    )

    private fun makeEntry(
        ids: List<Int>,
        tvPkg: String,
        phonePkg: String = tvPkg,
        justWatch: List<String> = emptyList(),
    ) = CatalogProviderEntry(
        tmdbProviderIds = ids,
        name = "Test Provider",
        regions = listOf("*"),
        androidPackages = CatalogAndroidPackages(tv = listOf(tvPkg), phone = listOf(phonePkg)),
        justWatchTechnicalNames = justWatch,
    )

    @Test
    fun `bundled snapshot resolves Netflix package name and provider id`() {
        val entry = ProviderCatalogRegistry.entryById(8)
        assertNotNull(entry)
        assertEquals("com.netflix.ninja", entry!!.packageName)
        assertEquals(8, entry.providerId)
    }

    @Test
    fun `bundled snapshot resolves Prime Video for both tmdb ids 9 and 119`() {
        val entry9 = ProviderCatalogRegistry.entryById(9)
        val entry119 = ProviderCatalogRegistry.entryById(119)
        assertNotNull(entry9)
        assertNotNull(entry119)
        assertEquals("com.amazon.amazonvideo.livingroom", entry9!!.packageName)
        assertEquals("com.amazon.amazonvideo.livingroom", entry119!!.packageName)
    }

    @Test
    fun `updateFromSnapshot replaces bundled entries for all lookup methods`() {
        val snapshot = makeSnapshot(
            makeEntry(ids = listOf(8), tvPkg = "com.netflix.replaced", justWatch = listOf("netflix")),
        )
        ProviderCatalogRegistry.updateFromSnapshot(snapshot)

        assertEquals("com.netflix.replaced", ProviderCatalogRegistry.entryById(8)?.packageName)
        assertTrue("com.netflix.replaced" in ProviderCatalogRegistry.knownPackageNames)
        assertEquals(8, ProviderCatalogRegistry.providerIdByJustWatchName("netflix"))
    }

    @Test
    fun `providerIdByJustWatchName is case-insensitive`() {
        assertEquals(8, ProviderCatalogRegistry.providerIdByJustWatchName("NETFLIX"))
        assertEquals(8, ProviderCatalogRegistry.providerIdByJustWatchName("Netflix"))
        assertEquals(8, ProviderCatalogRegistry.providerIdByJustWatchName("netflix"))
    }

    @Test
    fun `providerIdByJustWatchName logs a DiagnosticLog warning for unmapped names`() {
        ProviderCatalogRegistry.providerIdByJustWatchName("unknown_streaming_service")

        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN &&
                it.message.contains("unknown_streaming_service")
        }
        assertTrue(warnings.isNotEmpty(), "Expected a WARN entry for unmapped technicalName")
    }

    @Test
    fun `knownPackageNames is identical to the union of all entries packages`() {
        val fromEntries = ProviderCatalogRegistry.entries.mapTo(mutableSetOf()) { it.packageName }
        assertEquals(fromEntries, ProviderCatalogRegistry.knownPackageNames)
    }

    @Test
    fun `bundled snapshot maps amazonhbomax to TMDB provider 1825`() {
        val providerId = ProviderCatalogRegistry.providerIdByJustWatchName("amazonhbomax")
        assertEquals(1825, providerId, "amazonhbomax must resolve to TMDB provider_id 1825 (Max Amazon Channel)")
        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN && it.message.contains("amazonhbomax")
        }
        assertTrue(warnings.isEmpty(), "No warning should be logged for a mapped technicalName")
    }

    @Test
    fun `entries returns one ProviderEntry per providerId-packageName pair`() {
        val snapshot = makeSnapshot(
            makeEntry(ids = listOf(119, 9), tvPkg = "com.amazon.amazonvideo.livingroom"),
            makeEntry(ids = listOf(8), tvPkg = "com.netflix.ninja"),
        )
        ProviderCatalogRegistry.updateFromSnapshot(snapshot)

        val entries = ProviderCatalogRegistry.entries
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.providerId == 119 && it.packageName == "com.amazon.amazonvideo.livingroom" })
        assertTrue(entries.any { it.providerId == 9 && it.packageName == "com.amazon.amazonvideo.livingroom" })
        assertTrue(entries.any { it.providerId == 8 && it.packageName == "com.netflix.ninja" })
    }
}
