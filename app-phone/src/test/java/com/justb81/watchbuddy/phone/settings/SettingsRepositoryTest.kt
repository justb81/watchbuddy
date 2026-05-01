package com.justb81.watchbuddy.phone.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmEventLog
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsRepository")
class SettingsRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repository: SettingsRepository

    @BeforeEach
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tempDir, "test_settings.preferences_pb") }
        )
        repository = SettingsRepository(
            context = mockk<Context>(relaxed = true),
            dataStore = dataStore,
            tokenRepository = mockk<TokenRepository>(relaxed = true),
            llmEventLog = mockk<LlmEventLog>(relaxed = true),
            defaultTmdbApiKey = ""
        )
    }

    @Test
    fun `countryOverridePersists — default is empty`() = runTest {
        val settings = repository.settings.first()
        assertEquals("", settings.countryOverride)
    }

    @Test
    fun `countryOverridePersists — set override is reflected in settings flow`() = runTest {
        repository.setCountryOverride("DE")
        val settings = repository.settings.first()
        assertEquals("DE", settings.countryOverride)
    }

    @Test
    fun `countryOverridePersists — override is uppercased`() = runTest {
        repository.setCountryOverride("de")
        val settings = repository.settings.first()
        assertEquals("DE", settings.countryOverride)
    }

    @Test
    fun `countryOverridePersists — override can be cleared by setting empty string`() = runTest {
        repository.setCountryOverride("US")
        repository.setCountryOverride("")
        val settings = repository.settings.first()
        assertEquals("", settings.countryOverride)
    }

    @Test
    fun `countryOverridePersists — saveSettings round-trips countryOverride`() = runTest {
        val original = repository.settings.first()
        repository.saveSettings(original.copy(countryOverride = "FR"))
        val updated = repository.settings.first()
        assertEquals("FR", updated.countryOverride)
    }
}
