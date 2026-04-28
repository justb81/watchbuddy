package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Covers the multi-field [MediaMetadataSnapshot] match cascade added alongside
 * the LLM fallback. Streaming apps distribute signal across MediaMetadata
 * fields in incompatible ways — these tests exercise each shape.
 */
@DisplayName("MediaSessionScrobbler — snapshot cascade")
class MediaSessionScrobblerSnapshotTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private val titleExtractor: TitleExtractor = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val breakingBadShow = TraktShow(
        title = "Breaking Bad",
        year = 2008,
        ids = TraktIds(trakt = 1, tmdb = 1396),
    )
    private val breakingBadEntry = TraktWatchedEntry(show = breakingBadShow)

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, titleExtractor,
        )
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
        coEvery { titleExtractor.extract(any()) } returns null
    }

    @Test
    fun `Plex-shape snapshot resolves show via ALBUM_ARTIST when TITLE is just the episode title`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val snapshot = snapshotOf(
            "com.plexapp.android",
            "albumArtist" to "Breaking Bad",
            "displaySubtitle" to "S01E01",
            "title" to "Pilot",
        )
        val result = scrobbler.matchSnapshot(snapshot)

        assertNotNull(result)
        assertEquals(breakingBadShow, result!!.matchedShow)
        assertEquals(1, result.matchedEpisode?.season)
        assertEquals(1, result.matchedEpisode?.number)
        coVerify(exactly = 0) { titleExtractor.extract(any()) }
    }

    @Test
    fun `Jellyfin-shape snapshot resolves show via ALBUM when ARTIST has the episode`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val snapshot = snapshotOf(
            "org.jellyfin.mobile",
            "album" to "Breaking Bad",
            "artist" to "Cat's in the Bag",
            "displaySubtitle" to "S01E02",
            "title" to "Cat's in the Bag",
        )
        val result = scrobbler.matchSnapshot(snapshot)

        assertNotNull(result)
        assertEquals(breakingBadShow, result!!.matchedShow)
        assertEquals(1, result.matchedEpisode?.season)
        assertEquals(2, result.matchedEpisode?.number)
    }

    @Test
    fun `Netflix-shape snapshot (episode title only) falls through to LLM fallback`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        coEvery { titleExtractor.extract(any()) } returns TitleExtractionResponse(
            showTitle = "Breaking Bad",
            season = 2,
            episode = 5,
            confidence = 0.9f,
        )

        val snapshot = snapshotOf("com.netflix.ninja", "title" to "Chapter Seven")
        val result = scrobbler.matchSnapshot(snapshot)

        assertNotNull(result)
        assertEquals(breakingBadShow, result!!.matchedShow)
        assertEquals(2, result.matchedEpisode?.season)
        assertEquals(5, result.matchedEpisode?.number)
        coVerify(exactly = 1) { titleExtractor.extract(any()) }
    }

    @Test
    fun `LLM fallback result that does not match the library falls through to TMDB`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        coEvery { watchedShowSource.getTmdbApiKey() } returns "tmdb-key"
        coEvery { tmdbApiService.searchTv("The Bear", "tmdb-key") } returns
            com.justb81.watchbuddy.core.model.TmdbTvSearchResponse(
                listOf(
                    com.justb81.watchbuddy.core.model.TmdbShow(
                        id = 136315,
                        name = "The Bear",
                        first_air_date = "2022-06-23",
                    ),
                ),
            )
        coEvery { titleExtractor.extract(any()) } returns TitleExtractionResponse(
            showTitle = "The Bear",
            season = 1,
            episode = 1,
            confidence = 0.8f,
        )

        val snapshot = snapshotOf("com.disney.disneyplus", "title" to "System")
        val result = scrobbler.matchSnapshot(snapshot)

        assertNotNull(result)
        assertEquals("The Bear", result!!.matchedShow?.title)
        assertEquals(136315, result.matchedShow?.ids?.tmdb)
        assertEquals(1, result.matchedEpisode?.season)
    }

    @Test
    fun `no MediaSession field yields an exploitable candidate returns null`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val result = scrobbler.matchSnapshot(
            MediaSnapshotBuilder("com.netflix.ninja").build()
        )

        assertNull(result)
        coVerify(exactly = 0) { titleExtractor.extract(any()) }
        coVerify(exactly = 0) { watchedShowSource.getCachedShows() }
    }

    @Test
    fun `SxxExx harvested from DISPLAY_SUBTITLE is honored even when show match comes from ALBUM_ARTIST`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val snapshot = snapshotOf(
            "com.plexapp.android",
            "albumArtist" to "Breaking Bad",
            "displaySubtitle" to "S05E14",
            "title" to "Ozymandias",
        )
        val result = scrobbler.matchSnapshot(snapshot)

        assertNotNull(result)
        assertEquals(5, result!!.matchedEpisode?.season)
        assertEquals(14, result.matchedEpisode?.number)
        coVerify(exactly = 0) { watchedShowSource.getShowHint(any()) }
    }

    @Test
    fun `LLM fallback null result falls through to TMDB path cleanly`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns emptyList()
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { titleExtractor.extract(any()) } returns null

        val snapshot = snapshotOf("com.example.unknown", "title" to "Mystery Episode")
        val result = scrobbler.matchSnapshot(snapshot)

        assertNull(result)
        assertTrue(true)
    }
}
