package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [AppProfiles]:
 *  - Validates registry completeness / shape.
 *  - Per-profile fixture tests: given the metadata shape each app actually
 *    produces, [MediaSessionScrobbler.matchSnapshot] resolves the expected
 *    (showTitle, season, episode) via Phase 1 alone — no LLM call.
 *
 * Snapshot text uses the tagged-line format emitted by [MediaSnapshotBuilder]:
 * each line is `"<tag>: <value>"`.
 */
@DisplayName("AppProfiles registry")
class AppProfilesTest {

    // ── Registry shape ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Registry")
    inner class RegistryTest {

        @Test
        fun `ALL map is non-empty`() {
            assertTrue(AppProfiles.ALL.isNotEmpty())
        }

        @Test
        fun `forPackage returns null for unknown package`() {
            assertNull(AppProfiles.forPackage("com.unknown.app"))
        }

        @Test
        fun `forPackage returns profile for netflix ninja`() {
            val profile = AppProfiles.forPackage("com.netflix.ninja")
            assertNotNull(profile)
            assertEquals("com.netflix.ninja", profile!!.packageName)
        }

        @Test
        fun `forPackage returns profile for disney plus`() {
            val profile = AppProfiles.forPackage("com.disney.disneyplus")
            assertNotNull(profile)
            assertEquals("com.disney.disneyplus", profile!!.packageName)
        }

        @Test
        fun `all profiles have non-blank packageName`() {
            AppProfiles.ALL.values.forEach { p ->
                assertTrue(p.packageName.isNotBlank(), "Empty packageName in ${p.packageName}")
            }
        }

        @Test
        fun `all profile keys match packageName field`() {
            AppProfiles.ALL.entries.forEach { (key, profile) ->
                assertEquals(key, profile.packageName)
            }
        }

        @Test
        fun `markerRegexes each have at least two capture groups`() {
            AppProfiles.ALL.values.forEach { profile ->
                profile.markerRegexes.forEach { regex ->
                    // Verify the regex has 2 capture groups by matching a known pattern
                    val testInputs = listOf("S01E01", "T1 E1", "S1:E1", "T01E01")
                    val match = testInputs.firstNotNullOfOrNull { regex.find(it) }
                    if (match != null) {
                        // groupValues[0] is the full match; groups start at index 1
                        assertTrue(
                            match.groupValues.size >= 3,
                            "Regex in ${profile.packageName} must have ≥2 capture groups",
                        )
                    }
                }
            }
        }

        @Test
        fun `only YouTube TV profile has skipPhase1 = true`() {
            val skipPhase1Profiles = AppProfiles.ALL.values.filter { it.skipPhase1 }.map { it.packageName }
            assertEquals(
                listOf("com.google.android.youtube.tv"),
                skipPhase1Profiles,
                "Only the YouTube TV profile should have skipPhase1=true",
            )
        }

        @Test
        fun `all marker regexes complete within 200ms on adversarial input`() {
            // Adversarial inputs that trigger catastrophic backtracking in poorly-written patterns.
            val adversarialInputs = listOf(
                "S".repeat(1000) + ":", // triggers backtracking on S/digit anchors
                "Staffel ".repeat(200), // triggers backtracking on German marker
                "T" + "0".repeat(500) + " E", // triggers backtracking on T##E## patterns
                "a".repeat(1000), // long non-matching string
            )
            AppProfiles.ALL.values.forEach { profile ->
                profile.markerRegexes.forEach { regex ->
                    adversarialInputs.forEach { input ->
                        val start = System.currentTimeMillis()
                        regex.find(input)
                        val elapsed = System.currentTimeMillis() - start
                        assertTrue(
                            elapsed < 200,
                            "Regex in ${profile.packageName} took ${elapsed}ms on adversarial input",
                        )
                    }
                }
            }
        }
    }

    // ── Netflix fixture ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Netflix (com.netflix.ninja)")
    inner class NetflixFixture {

        private val extractor: TitleExtractor = mockk()
        private val strangerThings = TraktShow(
            title = "Stranger Things",
            year = 2016,
            ids = TraktIds(trakt = 104439, tmdb = 66732),
        )

        @Test
        fun `resolves show from notification text with Netflix S colon E marker`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(strangerThings), extractor)
            // Netflix ships: notification.text = "Stranger Things S4:E1"
            val snapshot = buildTaggedSnapshot(
                packageName = "com.netflix.ninja",
                lines = listOf(
                    "notification.text: Stranger Things S4:E1",
                    "notification.title: Stranger Things",
                    "mediaSession.title: Chapter One: The Hellfire Club",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Stranger Things", result!!.matchedShow?.title)
            assertEquals(4, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
            coVerify(exactly = 0) { extractor.extract(any()) }
        }

        @Test
        fun `marker regex handles uppercase S hash hash colon E hash hash format`() {
            val profile = AppProfiles.forPackage("com.netflix.ninja")!!
            val regex = profile.markerRegexes.first()
            val match = regex.find("Stranger Things S04:E07")
            assertNotNull(match)
            assertEquals("04", match!!.groupValues[1])
            assertEquals("07", match.groupValues[2])
        }

        @Test
        fun `marker regex handles lowercase s hash hash colon e hash hash format`() {
            val profile = AppProfiles.forPackage("com.netflix.ninja")!!
            val regex = profile.markerRegexes.first()
            val match = regex.find("breaking bad s03:e07")
            assertNotNull(match)
            assertEquals("03", match!!.groupValues[1])
            assertEquals("07", match.groupValues[2])
        }

        @Test
        fun `marker regex does not match standard S##E## without colon`() {
            val profile = AppProfiles.forPackage("com.netflix.ninja")!!
            val netflixRegex = profile.markerRegexes.first()
            // S03E07 (no colon) should NOT match the Netflix-specific regex
            val match = netflixRegex.find("Stranger Things S03E07")
            assertNull(match)
        }
    }

    // ── Disney+ fixture ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Disney+ (com.disney.disneyplus)")
    inner class DisneyPlusFixture {

        private val extractor: TitleExtractor = mockk()
        private val wandavision = TraktShow(
            title = "WandaVision",
            year = 2021,
            ids = TraktIds(trakt = 152374, tmdb = 85271),
        )

        @Test
        fun `resolves show from albumArtist with T## E## marker`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(wandavision), extractor)
            // Disney+ ships albumArtist with show title and T##E## marker
            val snapshot = buildTaggedSnapshot(
                packageName = "com.disney.disneyplus",
                lines = listOf(
                    "mediaSession.albumArtist: WandaVision T1 E1",
                    "watchNext.title: WandaVision",
                    "mediaSession.title: Filmed Before a Live Studio Audience",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("WandaVision", result!!.matchedShow?.title)
            assertEquals(1, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
            coVerify(exactly = 0) { extractor.extract(any()) }
        }

        @Test
        fun `T## E## marker regex matches various formats`() {
            val profile = AppProfiles.forPackage("com.disney.disneyplus")!!
            val regex = profile.markerRegexes.first()

            listOf("T1 E1", "T01 E01", "T1E1", "T10E05", "t2 e3").forEach { format ->
                assertNotNull(regex.find(format), "Regex should match '$format'")
            }
        }

        @Test
        fun `T## E## marker extracts correct season and episode`() {
            val profile = AppProfiles.forPackage("com.disney.disneyplus")!!
            val regex = profile.markerRegexes.first()
            val match = regex.find("WandaVision T2 E3")
            assertNotNull(match)
            assertEquals("2", match!!.groupValues[1])
            assertEquals("3", match.groupValues[2])
        }

        @Test
        fun `albumArtist is the first preferred source tag`() {
            val profile = AppProfiles.forPackage("com.disney.disneyplus")!!
            assertEquals("mediaSession.albumArtist", profile.preferredSourceTags.first())
        }
    }

    // ── Amazon Prime Video fixture ────────────────────────────────────────────

    @Nested
    @DisplayName("Prime Video (com.amazon.amazonvideo.livingroom)")
    inner class PrimeVideoFixture {

        private val extractor: TitleExtractor = mockk()
        private val theBoysShow = TraktShow(
            title = "The Boys",
            year = 2019,
            ids = TraktIds(trakt = 155932, tmdb = 76479),
        )

        @Test
        fun `resolves show from albumArtist field`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(theBoysShow), extractor)
            val snapshot = buildTaggedSnapshot(
                packageName = "com.amazon.amazonvideo.livingroom",
                lines = listOf(
                    "mediaSession.albumArtist: The Boys",
                    "mediaSession.album: Season 3",
                    "mediaSession.title: The Big Ride",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("The Boys", result!!.matchedShow?.title)
            coVerify(exactly = 0) { extractor.extract(any()) }
        }

        @Test
        fun `preferredSourceTags hoists albumArtist before title`() {
            val profile = AppProfiles.forPackage("com.amazon.amazonvideo.livingroom")!!
            assertEquals("mediaSession.albumArtist", profile.preferredSourceTags.first())
        }
    }

    // ── Plex fixture ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plex (com.plexapp.android)")
    inner class PlexFixture {

        private val extractor: TitleExtractor = mockk()
        private val breakingBad = TraktShow(
            title = "Breaking Bad",
            year = 2008,
            ids = TraktIds(trakt = 1, tmdb = 1396),
        )

        @Test
        fun `resolves show with standard S##E## marker from title`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(breakingBad), extractor)
            val snapshot = buildTaggedSnapshot(
                packageName = "com.plexapp.android",
                lines = listOf(
                    "mediaSession.title: Breaking Bad S03E07",
                    "notification.title: Breaking Bad",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
            assertEquals(3, result.matchedEpisode?.season)
            assertEquals(7, result.matchedEpisode?.number)
            coVerify(exactly = 0) { extractor.extract(any()) }
        }
    }

    // ── Jellyfin fixture ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Jellyfin (org.jellyfin.androidtv)")
    inner class JellyfinFixture {

        private val extractor: TitleExtractor = mockk()
        private val theOffice = TraktShow(
            title = "The Office",
            year = 2005,
            ids = TraktIds(trakt = 725, tmdb = 2316),
        )

        @Test
        fun `resolves show from mediaSession title`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(theOffice), extractor)
            val snapshot = buildTaggedSnapshot(
                packageName = "org.jellyfin.androidtv",
                lines = listOf(
                    "mediaSession.title: The Office S02E01",
                    "notification.title: The Office",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("The Office", result!!.matchedShow?.title)
            assertEquals(2, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
        }
    }

    // ── Joyn fixture ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Joyn (de.prosiebensat1digital.seventv)")
    inner class JoynFixture {

        @Test
        fun `forPackage returns profile for joyn`() {
            val profile = AppProfiles.forPackage("de.prosiebensat1digital.seventv")
            assertNotNull(profile)
            assertEquals("de.prosiebensat1digital.seventv", profile!!.packageName)
        }

        @Test
        fun `Staffel Y Folge X marker regex extracts season and episode`() {
            val profile = AppProfiles.forPackage("de.prosiebensat1digital.seventv")!!
            val regex = profile.markerRegexes.first()

            val match1 = regex.find("Staffel 2, Folge 3")
            assertNotNull(match1)
            assertEquals("2", match1!!.groupValues[1])
            assertEquals("3", match1.groupValues[2])

            val match2 = regex.find("Staffel 1 Folge 12")
            assertNotNull(match2)
            assertEquals("1", match2!!.groupValues[1])
            assertEquals("12", match2.groupValues[2])
        }

        @Test
        fun `Staffel Folge marker regex is case-insensitive`() {
            val profile = AppProfiles.forPackage("de.prosiebensat1digital.seventv")!!
            val regex = profile.markerRegexes.first()
            val match = regex.find("staffel 3, folge 5")
            assertNotNull(match)
            assertEquals("3", match!!.groupValues[1])
            assertEquals("5", match.groupValues[2])
        }

        @Test
        fun `joyn profile has llmHint`() {
            val profile = AppProfiles.forPackage("de.prosiebensat1digital.seventv")!!
            assertNotNull(profile.llmHint)
        }
    }

    // ── Apple TV+ fixture ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Apple TV+ (com.apple.atve.androidtv.appletv)")
    inner class AppleTvFixture {

        @Test
        fun `forPackage returns profile for apple tv`() {
            val profile = AppProfiles.forPackage("com.apple.atve.androidtv.appletv")
            assertNotNull(profile)
            assertEquals("com.apple.atve.androidtv.appletv", profile!!.packageName)
        }

        @Test
        fun `apple tv profile has no markerRegexes`() {
            val profile = AppProfiles.forPackage("com.apple.atve.androidtv.appletv")!!
            assertTrue(profile.markerRegexes.isEmpty())
        }

        @Test
        fun `apple tv profile prefers watchNext title and notification title`() {
            val profile = AppProfiles.forPackage("com.apple.atve.androidtv.appletv")!!
            assertEquals("watchNext.title", profile.preferredSourceTags[0])
            assertEquals("notification.title", profile.preferredSourceTags[1])
        }

        @Test
        fun `apple tv profile has llmHint about Episode N format`() {
            val profile = AppProfiles.forPackage("com.apple.atve.androidtv.appletv")!!
            assertNotNull(profile.llmHint)
            assertTrue(profile.llmHint!!.contains("Episode", ignoreCase = true))
        }
    }

    // ── Kodi fixture ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Kodi (org.xbmc.kodi)")
    inner class KodiFixture {

        private val extractor: TitleExtractor = mockk()
        private val gameOfThrones = TraktShow(
            title = "Game of Thrones",
            year = 2011,
            ids = TraktIds(trakt = 1390, tmdb = 1399),
        )

        @Test
        fun `forPackage returns profile for kodi`() {
            val profile = AppProfiles.forPackage("org.xbmc.kodi")
            assertNotNull(profile)
            assertEquals("org.xbmc.kodi", profile!!.packageName)
        }

        @Test
        fun `kodi profile has no markerRegexes`() {
            val profile = AppProfiles.forPackage("org.xbmc.kodi")!!
            assertTrue(profile.markerRegexes.isEmpty())
        }

        @Test
        fun `kodi profile prefers mediaSession title only`() {
            val profile = AppProfiles.forPackage("org.xbmc.kodi")!!
            assertEquals(listOf("mediaSession.title"), profile.preferredSourceTags)
        }

        @Test
        fun `resolves show from mediaSession title with standard S##E## marker`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(gameOfThrones), extractor)
            val snapshot = buildTaggedSnapshot(
                packageName = "org.xbmc.kodi",
                lines = listOf("mediaSession.title: Game of Thrones S01E09"),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Game of Thrones", result!!.matchedShow?.title)
            assertEquals(1, result.matchedEpisode?.season)
            assertEquals(9, result.matchedEpisode?.number)
            coVerify(exactly = 0) { extractor.extract(any()) }
        }
    }

    // ── YouTube TV fixture ────────────────────────────────────────────────────

    @Nested
    @DisplayName("YouTube TV (com.google.android.youtube.tv)")
    inner class YouTubeTvFixture {

        @Test
        fun `forPackage returns profile for youtube tv`() {
            val profile = AppProfiles.forPackage("com.google.android.youtube.tv")
            assertNotNull(profile)
            assertEquals("com.google.android.youtube.tv", profile!!.packageName)
        }

        @Test
        fun `youtube tv profile has skipPhase1 = true`() {
            val profile = AppProfiles.forPackage("com.google.android.youtube.tv")!!
            assertTrue(profile.skipPhase1)
        }

        @Test
        fun `youtube tv profile has llmHint`() {
            val profile = AppProfiles.forPackage("com.google.android.youtube.tv")!!
            assertNotNull(profile.llmHint)
        }

        @Test
        fun `youtube tv profile has no markerRegexes`() {
            val profile = AppProfiles.forPackage("com.google.android.youtube.tv")!!
            assertTrue(profile.markerRegexes.isEmpty())
        }
    }

    // ── Unknown app — negative case ───────────────────────────────────────────

    @Nested
    @DisplayName("Unknown package — no profile regression")
    inner class UnknownPackageTest {

        private val extractor: TitleExtractor = mockk()
        private val seinfeld = TraktShow(
            title = "Seinfeld",
            year = 1989,
            ids = TraktIds(trakt = 1401, tmdb = 1400),
        )

        @Test
        fun `unknown package falls through to default field order`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(seinfeld), extractor)
            // No profile — uses default order; title field has the show name
            val snapshot = buildTaggedSnapshot(
                packageName = "com.crunchyroll.crunchyroid",
                lines = listOf(
                    "mediaSession.title: Seinfeld S01E01",
                ),
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals("Seinfeld", result!!.matchedShow?.title)
        }

        @Test
        fun `unknown package does not call LLM when Phase 1 succeeds`() = runTest {
            coEvery { extractor.extract(any()) } returns null
            val scrobbler = makeScrobbler(listOf(seinfeld), extractor)
            val snapshot = buildTaggedSnapshot(
                packageName = "com.crunchyroll.crunchyroid",
                lines = listOf("mediaSession.title: Seinfeld S01E01"),
            )

            scrobbler.matchSnapshot(snapshot)

            coVerify(exactly = 0) { extractor.extract(any()) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeScrobbler(
        shows: List<TraktShow>,
        extractor: TitleExtractor,
    ): MediaSessionScrobbler {
        val context = mockk<Context>(relaxed = true)
        val tmdb = mockk<TmdbApiService>()
        val watchedShowSource = mockk<WatchedShowSource>()
        val dispatcher = mockk<ScrobbleDispatcher>()

        coEvery { watchedShowSource.getCachedShows() } returns
            shows.map { TraktWatchedEntry(show = it) }
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null

        return MediaSessionScrobbler(context, tmdb, watchedShowSource, dispatcher, extractor)
    }

    private fun buildTaggedSnapshot(
        packageName: String,
        lines: List<String>,
    ): MediaMetadataSnapshot =
        MediaMetadataSnapshot(packageName = packageName, text = lines.joinToString("\n"))
}
