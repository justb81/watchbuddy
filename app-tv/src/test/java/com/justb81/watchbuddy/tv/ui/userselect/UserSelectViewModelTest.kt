package com.justb81.watchbuddy.tv.ui.userselect

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("UserSelectViewModel")
class UserSelectViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val userSessionRepository: UserSessionRepository = mockk()
    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private lateinit var viewModel: UserSelectViewModel

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        every { userSessionRepository.selectedUserIds } returns flowOf(emptySet())
        coEvery { userSessionRepository.setSelectedUsers(any()) } just runs
        viewModel = UserSelectViewModel(phoneDiscovery, userSessionRepository)
    }

    @Test
    fun `initial selectedIds is empty`() {
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun `toggleUser adds device ID`() {
        viewModel.toggleUser("device-1")
        assertTrue(viewModel.selectedIds.value.contains("device-1"))
    }

    @Test
    fun `toggleUser removes already-selected device ID`() {
        viewModel.toggleUser("device-1")
        viewModel.toggleUser("device-1")
        assertFalse(viewModel.selectedIds.value.contains("device-1"))
    }

    @Test
    fun `selectAll selects all available users`() = runTest {
        val cap1 = DeviceCapability("d1", "u1", null, "P1", LlmBackend.NONE, 0, 1000, true)
        val cap2 = DeviceCapability("d2", "u2", null, "P2", LlmBackend.AICORE, 150, 8000, true)
        val phone1 = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        val phone2 = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone1.capability } returns cap1
        every { phone2.capability } returns cap2

        phonesFlow.value = listOf(phone1, phone2)

        // Subscribe to uiState to trigger the WhileSubscribed stateIn
        val job = backgroundScope.launch {
            viewModel.uiState.collect { }
        }
        testScheduler.advanceUntilIdle()

        viewModel.selectAll()
        assertEquals(setOf("d1", "d2"), viewModel.selectedIds.value)
        job.cancel()
    }

    @Test
    fun `persistSelection saves to repository`() = runTest {
        viewModel.toggleUser("device-1")
        viewModel.toggleUser("device-2")
        viewModel.persistSelection()
        coVerify { userSessionRepository.setSelectedUsers(setOf("device-1", "device-2")) }
    }
}
