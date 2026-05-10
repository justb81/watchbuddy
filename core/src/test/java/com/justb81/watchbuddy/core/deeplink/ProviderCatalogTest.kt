package com.justb81.watchbuddy.core.deeplink

import com.justb81.watchbuddy.core.model.CatalogAndroidPackages
import com.justb81.watchbuddy.core.model.CatalogProviderEntry
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ProviderCatalog")
class ProviderCatalogTest {

    private val amazonPackage = "com.amazon.amazonvideo.livingroom"
    private val joynTvPackage = "de.prosiebensat1.joyn.tv"

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

    @BeforeEach
    fun resetSnapshot() {
        // Reset to bundled state by calling updateFromSnapshot with a null-like state.
        // ProviderCatalog is a singleton — clear snapshot between tests via reflection.
        val field = ProviderCatalog::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        field.set(ProviderCatalog, null)
    }

    @AfterEach
    fun cleanup() {
        val field = ProviderCatalog::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        field.set(ProviderCatalog, null)
    }

    @Nested
    @DisplayName("BUNDLED_ENTRIES (no snapshot)")
    inner class BundledEntries {

        @Test
        fun `contains both Amazon TMDB IDs mapped to the same package`() {
            val entry9 = ProviderCatalog.byId[9]
            val entry119 = ProviderCatalog.byId[119]
            assertNotNull(entry9)
            assertNotNull(entry119)
            assertEquals(amazonPackage, entry9!!.packageName)
            assertEquals(amazonPackage, entry119!!.packageName)
        }

        @Test
        fun `Joyn entry uses new TV package not the old Seven TV package`() {
            val joyn = ProviderCatalog.byId[2184]
            assertNotNull(joyn)
            assertEquals(joynTvPackage, joyn!!.packageName)
            assertTrue("de.prosiebensat1digital.seventv" !in ProviderCatalog.knownPackageNames)
        }

        @Test
        fun `knownPackageNames does not contain old Seven TV package`() {
            assertTrue("de.prosiebensat1digital.seventv" !in ProviderCatalog.knownPackageNames)
        }

        @Test
        fun `Netflix is in bundled entries`() {
            assertEquals("com.netflix.ninja", ProviderCatalog.byId[8]?.packageName)
        }
    }

    @Nested
    @DisplayName("updateFromSnapshot")
    inner class SnapshotBehaviour {

        @Test
        fun `merged Amazon entry produces byId entries for both TMDB IDs`() {
            val snapshot = makeSnapshot(
                makeEntry(ids = listOf(119, 9), tvPkg = amazonPackage, justWatch = listOf("amazonprime")),
            )
            ProviderCatalog.updateFromSnapshot(snapshot)

            assertEquals(amazonPackage, ProviderCatalog.byId[119]?.packageName)
            assertEquals(amazonPackage, ProviderCatalog.byId[9]?.packageName)
        }

        @Test
        fun `single-ID entry still works`() {
            val snapshot = makeSnapshot(
                makeEntry(ids = listOf(8), tvPkg = "com.netflix.ninja"),
            )
            ProviderCatalog.updateFromSnapshot(snapshot)

            assertEquals("com.netflix.ninja", ProviderCatalog.byId[8]?.packageName)
            assertNull(ProviderCatalog.byId[9])
        }

        @Test
        fun `knownPackageNames reflects snapshot TV packages`() {
            val snapshot = makeSnapshot(
                makeEntry(ids = listOf(8), tvPkg = "com.netflix.ninja"),
                makeEntry(ids = listOf(119, 9), tvPkg = amazonPackage),
            )
            ProviderCatalog.updateFromSnapshot(snapshot)

            assertTrue("com.netflix.ninja" in ProviderCatalog.knownPackageNames)
            assertTrue(amazonPackage in ProviderCatalog.knownPackageNames)
        }

        @Test
        fun `Joyn entry in snapshot with correct TV package`() {
            val snapshot = makeSnapshot(
                makeEntry(ids = listOf(2184), tvPkg = joynTvPackage),
            )
            ProviderCatalog.updateFromSnapshot(snapshot)

            assertEquals(joynTvPackage, ProviderCatalog.byId[2184]?.packageName)
        }
    }
}
