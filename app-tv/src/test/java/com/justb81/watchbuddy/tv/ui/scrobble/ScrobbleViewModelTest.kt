package com.justb81.watchbuddy.tv.ui.scrobble

import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ScrobbleViewModel")
class ScrobbleViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val scrobbler: MediaSessionScrobbler = mockk()
    private val pendingFlow = MutableSharedFlow<ScrobbleCandidate>()
    private lateinit var viewModel: ScrobbleViewModel

    private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
    private val testEpisode = TraktEpisode(season = 1, number = 1)
    private val testCandidate = ScrobbleCandidate(
        "com.netflix", "Breaking Bad S01E01", 0.85f, testShow, testEpisode
    )

    @BeforeEach
    fun setUp() {
        every { scrobbler.pendingConfirmation } returns pendingFlow
        viewModel = ScrobbleViewModel(scrobbler)
    }

    @Test
    fun `initial pendingCandidate is null`() {
        assertNull(viewModel.pendingCandidate.value)
    }

    @Test
    fun `candidate from scrobbler appears in pendingCandidate`() = runTest {
        pendingFlow.emit(testCandidate)
        assertEquals(testCandidate, viewModel.pendingCandidate.value)
    }

    @Test
    fun `confirmScrobble calls autoScrobble and clears pending`() = runTest {
        coEvery { scrobbler.autoScrobble(any()) } just runs

        pendingFlow.emit(testCandidate)
        assertEquals(testCandidate, viewModel.pendingCandidate.value)

        viewModel.confirmScrobble()
        assertNull(viewModel.pendingCandidate.value)
        coVerify { scrobbler.autoScrobble(testCandidate) }
    }

    @Test
    fun `confirmScrobble does nothing when no pending candidate`() {
        viewModel.confirmScrobble()
        coVerify(exactly = 0) { scrobbler.autoScrobble(any()) }
    }

    @Test
    fun `dismissScrobble clears pending`() = runTest {
        pendingFlow.emit(testCandidate)
        viewModel.dismissScrobble()
        assertNull(viewModel.pendingCandidate.value)
    }

    @Test
    fun `dismissed candidate is not shown again`() = runTest {
        pendingFlow.emit(testCandidate)
        viewModel.dismissScrobble()
        assertNull(viewModel.pendingCandidate.value)

        // Emit the same candidate again
        pendingFlow.emit(testCandidate)
        assertNull(viewModel.pendingCandidate.value)
    }

    @Test
    fun `different episodes have different candidateKeys`() = runTest {
        val candidate1 = testCandidate
        val candidate2 = testCandidate.copy(
            matchedEpisode = TraktEpisode(season = 2, number = 3)
        )

        pendingFlow.emit(candidate1)
        viewModel.dismissScrobble()

        // Different episode should appear
        pendingFlow.emit(candidate2)
        assertNotNull(viewModel.pendingCandidate.value)
    }
}
