package com.justb81.watchbuddy.tv.ui.settings

import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.*
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
@DisplayName("StreamingSettingsViewModel")
class StreamingSettingsViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val repository: StreamingPreferencesRepository = mockk()
    private lateinit var viewModel: StreamingSettingsViewModel

    @BeforeEach
    fun setUp() {
        every { repository.subscribedServiceIds } returns flowOf(emptyList())
        coEvery { repository.setSubscribedServices(any()) } just runs
        viewModel = StreamingSettingsViewModel(repository)
    }

    @Nested
    @DisplayName("toggleService")
    inner class ToggleServiceTest {

        @Test
        fun `adds service to subscribed list`() {
            viewModel.toggleService("netflix")
            assertTrue(viewModel.uiState.value.subscribedIds.contains("netflix"))
            assertTrue(viewModel.uiState.value.orderedIds.contains("netflix"))
        }

        @Test
        fun `removes already-subscribed service`() {
            viewModel.toggleService("netflix")
            viewModel.toggleService("netflix")
            assertFalse(viewModel.uiState.value.subscribedIds.contains("netflix"))
        }

        @Test
        fun `persists changes to repository`() = runTest {
            viewModel.toggleService("disney")
            coVerify { repository.setSubscribedServices(listOf("disney")) }
        }
    }

    @Nested
    @DisplayName("moveServiceUp")
    inner class MoveServiceUpTest {

        @Test
        fun `swaps service with one above`() {
            viewModel.toggleService("netflix")
            viewModel.toggleService("disney")
            // Order is now [netflix, disney]
            viewModel.moveServiceUp("disney")
            assertEquals("disney", viewModel.uiState.value.orderedIds[0])
            assertEquals("netflix", viewModel.uiState.value.orderedIds[1])
        }

        @Test
        fun `does nothing when service is first`() {
            viewModel.toggleService("netflix")
            viewModel.toggleService("disney")
            viewModel.moveServiceUp("netflix")
            assertEquals("netflix", viewModel.uiState.value.orderedIds[0])
        }

        @Test
        fun `persists to repository`() = runTest {
            viewModel.toggleService("netflix")
            viewModel.toggleService("disney")
            clearMocks(repository, answers = false)
            coEvery { repository.setSubscribedServices(any()) } just runs
            viewModel.moveServiceUp("disney")
            coVerify { repository.setSubscribedServices(any()) }
        }
    }

    @Nested
    @DisplayName("moveServiceDown")
    inner class MoveServiceDownTest {

        @Test
        fun `swaps service with one below`() {
            viewModel.toggleService("netflix")
            viewModel.toggleService("disney")
            viewModel.moveServiceDown("netflix")
            assertEquals("disney", viewModel.uiState.value.orderedIds[0])
            assertEquals("netflix", viewModel.uiState.value.orderedIds[1])
        }

        @Test
        fun `does nothing when service is last`() {
            viewModel.toggleService("netflix")
            viewModel.toggleService("disney")
            viewModel.moveServiceDown("disney")
            assertEquals("disney", viewModel.uiState.value.orderedIds[1])
        }
    }

    @Test
    fun `initial uiState has default values`() {
        val state = viewModel.uiState.value
        assertEquals(7, state.services.size) // KNOWN_STREAMING_SERVICES
        assertTrue(state.subscribedIds.isEmpty())
        assertTrue(state.orderedIds.isEmpty())
    }
}
