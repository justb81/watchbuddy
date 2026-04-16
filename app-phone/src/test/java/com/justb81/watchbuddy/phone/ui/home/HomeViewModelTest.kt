package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk()
    private val tokenRepository: TokenRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsRepository.settings } returns flowOf(AppSettings())
        coEvery { tokenRepository.getAccessToken() } returns "test-access-token"
        every { application.getString(R.string.home_just_now) } returns "Just now"
        every { application.getString(R.string.home_sync_failed, any()) } returns "Sync failed"
        every { application.getString(R.string.home_sync_failed_auth) } returns "Auth failed"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadShows success updates state with shows`() = runTest {
        coEvery { traktApi.getWatchedShows(any()) } returns emptyList()

        val viewModel = HomeViewModel(application, traktApi, tokenRepository, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertTrue(state.error == null)
        assertEquals("Just now", state.lastSyncTime)
    }

    @Test
    fun `loadShows HTTP 403 shows auth error message`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 403
        coEvery { traktApi.getWatchedShows(any()) } throws httpEx

        val viewModel = HomeViewModel(application, traktApi, tokenRepository, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertEquals("Auth failed", state.error)
    }

    @Test
    fun `loadShows HTTP 401 shows auth error message`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 401
        coEvery { traktApi.getWatchedShows(any()) } throws httpEx

        val viewModel = HomeViewModel(application, traktApi, tokenRepository, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertEquals("Auth failed", state.error)
    }

    @Test
    fun `loadShows network error shows generic sync failed message`() = runTest {
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("ECONNREFUSED")

        val viewModel = HomeViewModel(application, traktApi, tokenRepository, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertEquals("Sync failed", state.error)
    }

    @Test
    fun `loadShows missing token shows generic error`() = runTest {
        coEvery { tokenRepository.getAccessToken() } returns null

        val viewModel = HomeViewModel(application, traktApi, tokenRepository, settingsRepository)

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertTrue(state.error != null)
    }
}
