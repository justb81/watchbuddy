package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.justb81.watchbuddy.tv.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Tests for the preference keys added in issue #344.
 *
 * Uses an in-memory [DataStore] backed by [MutableStateFlow] so there are no
 * file I/O or Android-runtime dependencies in the JVM unit-test environment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("StreamingPreferencesRepository — discovery and autostart prefs")
class StreamingPreferencesRepositoryTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private lateinit var repository: StreamingPreferencesRepository

    @BeforeEach
    fun setUp() {
        repository = StreamingPreferencesRepository(
            context = mockk<Context>(relaxed = true),
            dataStore = InMemoryPreferencesDataStore(),
        )
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

/**
 * Pure-JVM in-memory [DataStore] for unit tests.
 *
 * Stores [Preferences] in a [MutableStateFlow] so reads and writes are
 * observable without any file I/O or Android runtime.
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {

    private val _prefs = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = _prefs

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(_prefs.value)
        _prefs.value = updated
        return updated
    }
}
