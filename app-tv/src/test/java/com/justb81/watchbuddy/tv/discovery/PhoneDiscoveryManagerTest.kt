package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import io.mockk.*
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PhoneDiscoveryManager")
class PhoneDiscoveryManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val httpClient: OkHttpClient = mockk(relaxed = true)
    private val nsdManager: NsdManager = mockk(relaxed = true)
    private lateinit var manager: PhoneDiscoveryManager

    @BeforeEach
    fun setUp() {
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsdManager
        manager = PhoneDiscoveryManager(context, httpClient)
    }

    @Nested
    @DisplayName("calculateScore")
    inner class CalculateScoreTest {

        private fun makePhone(
            capability: DeviceCapability?,
            score: Int = 0,
            name: String = "test"
        ): PhoneDiscoveryManager.DiscoveredPhone {
            val serviceInfo = mockk<NsdServiceInfo>()
            every { serviceInfo.serviceName } returns name
            return PhoneDiscoveryManager.DiscoveredPhone(serviceInfo, capability, score, "http://test/")
        }

        @Test
        fun `getBestPhone returns null when no phones discovered`() {
            assertNull(manager.getBestPhone())
        }

        @Test
        fun `getBestPhone returns highest scoring available phone`() {
            val cap1 = DeviceCapability("d1", "u1", null, "P1", LlmBackend.NONE, 0, 1000, true)
            val cap2 = DeviceCapability("d2", "u2", null, "P2", LlmBackend.AICORE, 150, 8000, true)

            // Simulate phones being added via the StateFlow
            // We can't easily trigger NSD discovery, so test getBestPhone with pre-set phones
            // by testing the discoveredPhones StateFlow directly
            val phone1 = makePhone(cap1, score = 0, name = "phone1")
            val phone2 = makePhone(cap2, score = 160, name = "phone2")

            // Access the private _discoveredPhones field to set test data
            val field = PhoneDiscoveryManager::class.java.getDeclaredField("_discoveredPhones")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>
            flow.value = listOf(phone1, phone2)

            val best = manager.getBestPhone()
            assertNotNull(best)
            assertEquals("d2", best!!.capability?.deviceId)
        }

        @Test
        fun `getBestPhone excludes unavailable phones`() {
            val cap = DeviceCapability("d1", "u1", null, "P1", LlmBackend.AICORE, 150, 8000, false)
            val phone = makePhone(cap, score = 160, name = "phone1")

            val field = PhoneDiscoveryManager::class.java.getDeclaredField("_discoveredPhones")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>
            flow.value = listOf(phone)

            assertNull(manager.getBestPhone())
        }
    }

    @Nested
    @DisplayName("scoring formula")
    inner class ScoringFormulaTest {

        // Test calculateScore indirectly via reflection since it's private
        private fun calculateScore(cap: DeviceCapability?): Int {
            val method = PhoneDiscoveryManager::class.java.getDeclaredMethod(
                "calculateScore", DeviceCapability::class.java
            )
            method.isAccessible = true
            return method.invoke(manager, cap) as Int
        }

        @Test
        fun `returns 0 for null capability`() {
            assertEquals(0, calculateScore(null))
        }

        @Test
        fun `adds ramBonus 10 for at least 6000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 6000, true)
            assertEquals(60, calculateScore(cap)) // 50 + 10
        }

        @Test
        fun `adds ramBonus 6 for at least 4000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 4500, true)
            assertEquals(56, calculateScore(cap)) // 50 + 6
        }

        @Test
        fun `adds ramBonus 3 for at least 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 3500, true)
            assertEquals(53, calculateScore(cap)) // 50 + 3
        }

        @Test
        fun `adds ramBonus 0 for less than 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 2000, true)
            assertEquals(50, calculateScore(cap)) // 50 + 0
        }

        @Test
        fun `adds modelQuality to ramBonus`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.AICORE, 150, 8000, true)
            assertEquals(160, calculateScore(cap)) // 150 + 10
        }
    }

    @Test
    fun `SERVICE_TYPE constant is correct`() {
        assertEquals("_watchbuddy._tcp.", PhoneDiscoveryManager.SERVICE_TYPE)
    }

    @Test
    fun `CAPABILITY_PATH constant is correct`() {
        assertEquals("/capability", PhoneDiscoveryManager.CAPABILITY_PATH)
    }

    @Test
    fun `stopDiscovery does not throw`() {
        // Should handle gracefully even if discovery was never started
        manager.stopDiscovery()
    }
}
