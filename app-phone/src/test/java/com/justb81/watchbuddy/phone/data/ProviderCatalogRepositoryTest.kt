package com.justb81.watchbuddy.phone.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkManager
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ProviderCatalogRepository")
class ProviderCatalogRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val workManager: WorkManager = mockk(relaxed = true)

    private val catalogJsonKey = stringPreferencesKey("provider_catalog_json")

    private val minimalV8Json = """
        {
          "version": 8,
          "lastUpdated": "2026-05-10T00:00:00Z",
          "providers": [
            {
              "tmdbProviderIds": [8],
              "name": "Netflix",
              "regions": ["*"],
              "androidPackages": {"tv": ["com.netflix.ninja"], "phone": ["com.netflix.mediaclient"]},
              "justWatchTechnicalNames": ["netflix"]
            }
          ]
        }
    """.trimIndent()

    private fun buildDataStore() = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { File(tempDir, "test_catalog.preferences_pb") },
    )

    @Test
    fun `currentJson returns null when no catalog has been fetched`() = runTest {
        val dataStore = buildDataStore()
        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        assertNull(repo.currentJson())
    }

    @Test
    fun `currentJson returns stored JSON after it has been persisted`() = runTest {
        val dataStore = buildDataStore()
        dataStore.edit { it[catalogJsonKey] = minimalV8Json }

        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        val json = repo.currentJson()
        assertNotNull(json)
        assertTrue(json!!.contains("\"version\":8") || json.contains("\"version\": 8"))
    }

    @Test
    fun `isLive returns false when no catalog JSON is stored`() = runTest {
        val dataStore = buildDataStore()
        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        assertFalse(repo.isLive())
    }

    @Test
    fun `isLive returns true when catalog JSON is stored`() = runTest {
        val dataStore = buildDataStore()
        dataStore.edit { it[catalogJsonKey] = minimalV8Json }

        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        assertTrue(repo.isLive())
    }

    @Test
    fun `catalog StateFlow starts null when no JSON is stored`() = runTest {
        val dataStore = buildDataStore()
        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        assertNull(repo.catalog.value)
    }

    @Test
    fun `catalog StateFlow reflects parsed snapshot from stored JSON`() = runTest {
        val dataStore = buildDataStore()
        dataStore.edit { it[catalogJsonKey] = minimalV8Json }

        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        val snapshot = repo.catalog.first { it != null }
        assertNotNull(snapshot)
        assertEquals(8, snapshot!!.version)
    }

    @Test
    fun `currentVersion returns 0 when no catalog is loaded`() = runTest {
        val dataStore = buildDataStore()
        val repo = ProviderCatalogRepository(
            dataStore = dataStore,
            workManager = workManager,
            backendUrl = "",
        )

        assertEquals(0, repo.currentVersion())
    }
}
