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

        // ── Services that require a TMDB ID ──────────────────────────────────

        @Test
        fun `substitutes tmdb_id placeholder for Netflix`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 42, slug = "test")))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}")
            )
            assertEquals("https://netflix.com/title/42", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `substitutes id placeholder for ARD`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 77, slug = "test")))
            val services = listOf(
                StreamingService("ard", "ARD", "pkg", "https://ard.de/video/{id}")
            )
            assertEquals("https://ard.de/video/77", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `substitutes both tmdb_id and slug for Disney+`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 5, slug = "test-show")))
            val services = listOf(
                StreamingService("disney", "Disney+", "pkg", "https://disney.com/series/{slug}/{tmdb_id}")
            )
            assertEquals("https://disney.com/series/test-show/5", viewModel.resolveDeepLink(entry, services))
        }

        // ── Services that only need a slug ────────────────────────────────────

        @Test
        fun `Joyn generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Test Show", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/test-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `Prime Video generates search URL without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = "breaking-bad")))
            val services = listOf(
                StreamingService("prime", "Prime Video", "pkg", "https://www.primevideo.com/search?phrase={slug}")
            )
            assertEquals(
                "https://www.primevideo.com/search?phrase=breaking-bad",
                viewModel.resolveDeepLink(entry, services)
            )
        }

        @Test
        fun `ZDF generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Tatort", 2024, TraktIds(tmdb = null, slug = "tatort")))
            val services = listOf(
                StreamingService("zdf", "ZDF", "pkg", "https://www.zdf.de/serien/{slug}")
            )
            assertEquals("https://www.zdf.de/serien/tatort", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `slug-only service still works when tmdb_id is present`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 99, slug = "test")))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/test", viewModel.resolveDeepLink(entry, services))
        }

        // ── Services with no template variables ───────────────────────────────

        @Test
        fun `WaipuTV generates deep link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = null)))
            val services = listOf(
                StreamingService("waipu", "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `WaipuTV generates deep link even with tmdb_id present`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = 1)))
            val services = listOf(
                StreamingService("waipu", "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        // ── Slug derivation from title ─────────────────────────────────────────

        @Test
        fun `derives slug from show title when slug field is null`() {
            val entry = TraktWatchedEntry(TraktShow("My Show", 2024, TraktIds(tmdb = 1, slug = null)))
            val services = listOf(
                StreamingService("test", "Test", "pkg", "https://test.com/{slug}")
            )
            assertEquals("https://test.com/my-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `derives slug from title for slug-only service when both slug and tmdb_id are null`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = null)))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/breaking-bad", viewModel.resolveDeepLink(entry, services))
        }

        // ── Service priority and fallback ─────────────────────────────────────

        @Test
        fun `returns first subscribed service link when all ids available`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val services = listOf(
                StreamingService("disney", "Disney+", "pkg", "https://disney.com/{tmdb_id}"),
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/{tmdb_id}")
            )
            assertEquals("https://disney.com/1", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `skips Netflix and falls back to WaipuTV when tmdb_id is null`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null, slug = "test")))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("waipu",   "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `skips all id-requiring services and uses first slug-only service`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val services = listOf(
                StreamingService("netflix", "Netflix",  "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("disney",  "Disney+",  "pkg", "https://disney.com/series/{slug}/{tmdb_id}"),
                StreamingService("prime",   "Prime",    "pkg", "https://www.primevideo.com/search?phrase={slug}")
            )
            assertEquals("https://www.primevideo.com/search?phrase=test-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `returns null only when all subscribed services need an unavailable tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("ard",     "ARD",     "pkg", "https://ard.de/video/{id}")
            )
            assertNull(viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `falls back to KNOWN_STREAMING_SERVICES when subscribed list is empty and tmdb_id present`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val result = viewModel.resolveDeepLink(entry, emptyList())
            // Netflix is first in KNOWN_STREAMING_SERVICES and tmdb_id is available
            assertNotNull(result)
            assertTrue(result!!.contains("1"))
        }

        @Test
        fun `falls back to slug-only service in KNOWN_STREAMING_SERVICES when tmdb_id is null`() {
            val entry = TraktWatchedEntry(TraktShow("Test Show", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val result = viewModel.resolveDeepLink(entry, emptyList())
            // Netflix and Disney+ fail (need tmdb_id); Prime Video succeeds with slug
            assertNotNull(result)
            assertTrue(result!!.contains("test-show"))
        }
    }
}
