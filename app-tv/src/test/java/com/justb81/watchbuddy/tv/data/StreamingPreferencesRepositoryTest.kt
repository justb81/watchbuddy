package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.justb81.watchbuddy.tv.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for the new preference keys added in issue #344.
 * Uses an isolated file-backed DataStore per test method to avoid shared state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("StreamingPreferencesRepository — discovery and autostart prefs")
class StreamingPreferencesRepositoryTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: StreamingPreferencesRepository

    @BeforeEach
    fun setUp() {
        val prefsFile = File(tempDir, "streaming_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(
                mainDispatcherRule.testDispatcher + SupervisorJob()
            ),
            produceFile = { prefsFile }
        )
        repository = StreamingPreferencesRepository(mockk<Context>(relaxed = true), dataStore)
    }

    @Nested
    @DisplayName("isPhoneDiscoveryEnabled")
    inner class PhoneDiscoveryEnabledTest {

        @Test
        fun `defaults to true when not set`() = runTest {
            assertTrue(repository.isPhoneDiscoveryEnabled.first())
        }

        @Test
        fun `persists false value`() = runTest {
            repository.setPhoneDiscoveryEnabled(false)
            assertFalse(repository.isPhoneDiscoveryEnabled.first())
        }

        @Test
        fun `persists true value after setting false`() = runTest {
            repository.setPhoneDiscoveryEnabled(false)
            repository.setPhoneDiscoveryEnabled(true)
            assertTrue(repository.isPhoneDiscoveryEnabled.first())
        }
    }

    @Nested
    @DisplayName("isAutostartEnabled")
    inner class AutostartEnabledTest {

        @Test
        fun `defaults to false when not set`() = runTest {
            assertFalse(repository.isAutostartEnabled.first())
        }

        @Test
        fun `persists true value`() = runTest {
            repository.setAutostartEnabled(true)
            assertTrue(repository.isAutostartEnabled.first())
        }

        @Test
        fun `persists false value after setting true`() = runTest {
            repository.setAutostartEnabled(true)
            repository.setAutostartEnabled(false)
            assertFalse(repository.isAutostartEnabled.first())
        }
    }
}
