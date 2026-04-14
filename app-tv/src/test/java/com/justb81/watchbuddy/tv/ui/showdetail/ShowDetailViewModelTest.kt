package com.justb81.watchbuddy.tv.ui.showdetail

import app.cash.turbine.test
import com.justb81.watchbuddy.core.model.*
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShowDetailViewModel")
class ShowDetailViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val streamingPrefs: StreamingPreferencesRepository = mockk()
    private lateinit var viewModel: ShowDetailViewModel

    @Nested
    @DisplayName("availableServices")
    inner class AvailableServicesTest {

        @Test
        fun `returns all known services when prefs empty`() = runTest {
            every { streamingPrefs.subscribedServiceIds } returns flowOf(emptyList())
            viewModel = ShowDetailViewModel(streamingPrefs)

            viewModel.availableServices.test {
                val services = awaitItem()
                assertEquals(KNOWN_STREAMING_SERVICES.size, services.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `returns only subscribed services in order`() = runTest {
            every { streamingPrefs.subscribedServiceIds } returns flowOf(listOf("disney", "netflix"))
            viewModel = ShowDetailViewModel(streamingPrefs)

            viewModel.availableServices.test {
                val services = awaitItem()
                assertEquals(2, services.size)
                assertEquals("disney", services[0].id)
                assertEquals("netflix", services[1].id)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    @DisplayName("resolveDeepLink")
    inner class ResolveDeepLinkTest {

        @BeforeEach
        fun setUp() {
            every { streamingPrefs.subscribedServiceIds } returns flowOf(emptyList())
            viewModel = ShowDetailViewModel(streamingPrefs)
        }

        @Test
        fun `substitutes tmdb_id placeholder`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 42, slug = "test")))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://netflix.com/title/42", result)
        }

        @Test
        fun `substitutes slug placeholder`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test-show")))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://joyn.de/serien/test-show", result)
        }

        @Test
        fun `Prime Video uses search URL with slug`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = 1399, slug = "breaking-bad")))
            val services = listOf(
                StreamingService("prime", "Prime Video", "pkg", "https://www.primevideo.com/search?phrase={slug}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://www.primevideo.com/search?phrase=breaking-bad", result)
        }

        @Test
        fun `substitutes id placeholder`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 77, slug = "test")))
            val services = listOf(
                StreamingService("ard", "ARD", "pkg", "https://ard.de/video/{id}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://ard.de/video/77", result)
        }

        @Test
        fun `returns null when tmdb id is null`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val result = viewModel.resolveDeepLink(entry, KNOWN_STREAMING_SERVICES)
            assertNull(result)
        }

        @Test
        fun `falls back to title-based slug when slug is null`() {
            val entry = TraktWatchedEntry(TraktShow("My Show", 2024, TraktIds(tmdb = 1, slug = null)))
            val services = listOf(
                StreamingService("test", "Test", "pkg", "https://test.com/{slug}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://test.com/my-show", result)
        }

        @Test
        fun `uses first subscribed service`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val services = listOf(
                StreamingService("disney", "Disney+", "pkg", "https://disney.com/{tmdb_id}"),
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/{tmdb_id}")
            )
            val result = viewModel.resolveDeepLink(entry, services)
            assertEquals("https://disney.com/1", result)
        }

        @Test
        fun `falls back to first known service when subscribed list empty`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val result = viewModel.resolveDeepLink(entry, emptyList())
            // Should use first KNOWN_STREAMING_SERVICES (netflix)
            assertNotNull(result)
            assertTrue(result!!.contains("1"))
        }
    }
}
