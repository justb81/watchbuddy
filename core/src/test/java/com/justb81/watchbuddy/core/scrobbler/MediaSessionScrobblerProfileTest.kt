package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the [AppProfile]-integration points in [MediaSessionScrobbler]:
 *  - [AppProfile.preferredSourceTags] reorders candidate lines before Phase 1.
 *  - [AppProfile.markerRegexes] are tried before the default `S##E##` pattern.
 *  - [AppProfile.skipPhase1] bypasses Phase 1 and goes straight to the LLM.
 *  - Unknown packages behave identically to current main (no regression).
 */
@DisplayName("MediaSessionScrobbler — AppProfile integration")
class MediaSessionScrobblerProfileTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private val titleExtractor: TitleExtractor = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, titleExtractor,
        )
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
        coEvery { titleExtractor.extract(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        // Ensure any object mocks are cleaned up between tests
        runCatching { unmockkObject(AppProfiles) }
    }

    // ── preferredSourceTags ───────────────────────────────────────────────────

    @Nested
    @DisplayName("preferredSourceTags — field priority")
    inner class PreferredSourceTagsTest {

        @Test
        fun `preferred tag is tried before default order — Disney+ albumArtist wins`() = runTest {
            val disneyShow = TraktShow("The Mandalorian", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = disneyShow))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.disney.disneyplus",
                lines = listOf(
                    "notification.title: This Is The Way (Episode 1)",
                    "mediaSession.albumArtist: The Mandalorian T2 E1",
                    "mediaSession.title: This Is The Way",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("The Mandalorian", result!!.matchedShow?.title)
            // albumArtist hoisted first by Disney+ profile; T##E## marker parsed
            assertEquals(2, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
        }

        @Test
        fun `non-preferred lines are still tried after preferred group`() = runTest {
            val show = TraktShow("Dark", ids = TraktIds(trakt = 99))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            // Plex prefers mediaSession.title first; displayTitle has the S##E## marker
            val snapshot = buildTaggedSnapshot(
                packageName = "com.plexapp.android",
                lines = listOf(
                    "mediaSession.title: Sic Mundus Creatus Est",
                    "mediaSession.displayTitle: Dark S01E04",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            // "Dark S01E04" in displayTitle should still be tried and matched
            assertNotNull(result)
            assertEquals("Dark", result!!.matchedShow?.title)
        }

        @Test
        fun `unknown package uses default line order`() = runTest {
            val show = TraktShow("Breaking Bad", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.unknown.app",
                lines = listOf(
                    "mediaSession.albumArtist: Breaking Bad",
                    "mediaSession.title: Pilot",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
        }
    }

    // ── markerRegexes ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markerRegexes — custom episode markers")
    inner class MarkerRegexesTest {

        @Test
        fun `Disney+ T## E## marker extracts season=1, episode=1`() = runTest {
            val show = TraktShow("Loki", ids = TraktIds(trakt = 164718))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.disney.disneyplus",
                lines = listOf("mediaSession.albumArtist: Loki T1 E1"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(1, result!!.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
        }

        @Test
        fun `Netflix S hash hash colon E hash hash marker extracts season and episode`() = runTest {
            val show = TraktShow("Squid Game", ids = TraktIds(trakt = 194942))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.netflix.ninja",
                lines = listOf(
                    "notification.text: Squid Game S2:E3",
                    "mediaSession.title: The Test",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Squid Game", result!!.matchedShow?.title)
            assertEquals(2, result.matchedEpisode?.season)
            assertEquals(3, result.matchedEpisode?.number)
        }

        @Test
        fun `default S##E## is tried as fallback when profile marker does not match`() = runTest {
            // Disney+ profile has T##E## marker — if content ships S##E##
            // the default pattern must still work.
            val show = TraktShow("Hawkeye", ids = TraktIds(trakt = 177549))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.disney.disneyplus",
                lines = listOf("mediaSession.albumArtist: Hawkeye S01E03"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(1, result!!.matchedEpisode?.season)
            assertEquals(3, result.matchedEpisode?.number)
        }

        @Test
        fun `unknown package uses only default S##E## — T##E## not matched`() = runTest {
            val show = TraktShow("Some Show", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.unknown.streaming",
                lines = listOf("mediaSession.title: Some Show T2 E5"),
            )

            // No profile → T##E## not extracted → episode is null
            val result = scrobbler.matchSnapshot(snapshot)

            if (result != null) {
                assertNull(result.matchedEpisode)
            }
        }
    }

    // ── skipPhase1 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("skipPhase1 flag")
    inner class SkipPhase1Test {

        @Test
        fun `skipPhase1=true calls LLM even when Phase 1 cache match exists`() = runTest {
            mockkObject(AppProfiles)
            val skipProfile = AppProfile(packageName = "com.test.skip", skipPhase1 = true)
            every { AppProfiles.forPackage("com.test.skip") } returns skipProfile

            val show = TraktShow("Breaking Bad", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))
            // LLM returns null — result will be null because TMDB key is also null
            coEvery { titleExtractor.extract(any()) } returns null

            val snapshot = buildTaggedSnapshot(
                packageName = "com.test.skip",
                lines = listOf("mediaSession.title: Breaking Bad"),
            )

            scrobbler.matchSnapshot(snapshot)

            // LLM must have been called even though "Breaking Bad" is in the cache
            // (skipPhase1 bypasses the Levenshtein scoring loop)
            coVerify(exactly = 1) { titleExtractor.extract(any()) }
        }

        @Test
        fun `skipPhase1=true with successful LLM returns matched show`() = runTest {
            mockkObject(AppProfiles)
            val skipProfile = AppProfile(packageName = "com.test.skip", skipPhase1 = true)
            every { AppProfiles.forPackage("com.test.skip") } returns skipProfile

            val show = TraktShow("Breaking Bad", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))
            coEvery { titleExtractor.extract(any()) } returns TitleExtractionResponse(
                showTitle = "Breaking Bad",
                season = 1,
                episode = 1,
                confidence = 0.95f,
            )

            val snapshot = buildTaggedSnapshot(
                packageName = "com.test.skip",
                lines = listOf("mediaSession.title: Breaking Bad S01E01"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
            coVerify(exactly = 1) { titleExtractor.extract(any()) }
        }

        @Test
        fun `skipPhase1=false never bypasses Phase 1 cache match`() = runTest {
            val show = TraktShow("Breaking Bad", ids = TraktIds(trakt = 1))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.unknown.app", // no profile → skipPhase1=false
                lines = listOf("mediaSession.title: Breaking Bad S01E01"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
            // LLM never called because Phase 1 succeeded
            coVerify(exactly = 0) { titleExtractor.extract(any()) }
        }
    }

    // ── observedPackageStats ──────────────────────────────────────────────────

    @Nested
    @DisplayName("observedPackageStats")
    inner class ObservedPackageStatsTest {

        @Test
        fun `stats start at zero before any sessions are polled`() {
            val stats = scrobbler.observedPackageStats()
            assertEquals(0, stats.totalCount)
            assertEquals(0, stats.profiledCount)
        }
    }

    // ── Regression — unknown package behaves like current main ────────────────

    @Nested
    @DisplayName("Regression — unknown package")
    inner class UnknownPackageRegressionTest {

        @Test
        fun `unknown package with S##E## resolves correctly`() = runTest {
            val show = TraktShow("Breaking Bad", ids = TraktIds(trakt = 1, tmdb = 1396))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = buildTaggedSnapshot(
                packageName = "com.example.newstreamer",
                lines = listOf("mediaSession.title: Breaking Bad S03E07"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
            assertEquals(3, result.matchedEpisode?.season)
            assertEquals(7, result.matchedEpisode?.number)
            coVerify(exactly = 0) { titleExtractor.extract(any()) }
        }

        @Test
        fun `unknown package produces same result as before AppProfiles`() = runTest {
            val show = TraktShow("The Crown", ids = TraktIds(trakt = 90264))
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = show))

            val snapshot = snapshotOf(
                "com.totally.unknown",
                "albumArtist" to "The Crown",
                "title" to "War",
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("The Crown", result!!.matchedShow?.title)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildTaggedSnapshot(
        packageName: String,
        lines: List<String>,
    ): MediaMetadataSnapshot =
        MediaMetadataSnapshot(packageName = packageName, text = lines.joinToString("\n"))

    private fun snapshotOf(packageName: String, vararg pairs: Pair<String, String>): MediaMetadataSnapshot =
        MediaMetadataSnapshot(
            packageName = packageName,
            text = pairs.joinToString("\n") { (tag, value) -> "$tag: $value" },
        )
}
