package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PhoneDiscoveryManager")
class PhoneDiscoveryManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val httpClient: OkHttpClient = mockk(relaxed = true)
    private val bleScanner: PhoneBleScanner = mockk(relaxed = true)
    private lateinit var manager: PhoneDiscoveryManager

    @BeforeEach
    fun setUp() {
        manager = PhoneDiscoveryManager(context, httpClient, bleScanner)
    }

    private fun makePhone(
        capability: DeviceCapability?,
        txtRecord: PhoneDiscoveryManager.PhoneTxtRecord? = null,
        score: Int = 0,
        name: String = "test",
        baseUrl: String = "http://test/",
        rssi: Int? = null,
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val serviceInfo = mockk<NsdServiceInfo>()
        every { serviceInfo.serviceName } returns name
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = serviceInfo,
            txtRecord = txtRecord,
            capability = capability,
            score = score,
            baseUrl = baseUrl,
            rssi = rssi,
        )
    }

    private fun makeTxtRecord(
        modelQuality: Int = 70,
        llmBackend: LlmBackend = LlmBackend.LITERT,
        version: String = "1"
    ) = PhoneDiscoveryManager.PhoneTxtRecord(
        version = version,
        modelQuality = modelQuality,
        llmBackend = llmBackend
    )

    @Nested
    @DisplayName("getBestPhone")
    inner class GetBestPhoneTest {

        private fun setPhones(vararg phones: PhoneDiscoveryManager.DiscoveredPhone) {
            manager.setDiscoveredPhonesForTest(phones.toList())
        }

        @Test
        fun `returns null when no phones discovered`() {
            assertNull(manager.getBestPhone())
        }

        @Test
        fun `returns highest scoring available phone`() {
            val cap1 = DeviceCapability("d1", "u1", null, "P1", LlmBackend.NONE, 0, 1000, true)
            val cap2 = DeviceCapability("d2", "u2", null, "P2", LlmBackend.AICORE, 150, 8000, true)

            val phone1 = makePhone(cap1, score = 0, name = "phone1", baseUrl = "http://phone1/")
            val phone2 = makePhone(cap2, score = 160, name = "phone2", baseUrl = "http://phone2/")

            setPhones(phone1, phone2)

            val best = manager.getBestPhone()
            assertNotNull(best)
            assertEquals("d2", best!!.capability?.deviceId)
        }

        @Test
        fun `excludes phones where capability marks them unavailable`() {
            val cap = DeviceCapability("d1", "u1", null, "P1", LlmBackend.AICORE, 150, 8000, false)
            val phone = makePhone(cap, score = 160, name = "phone1")
            setPhones(phone)

            assertNull(manager.getBestPhone())
        }

        @Test
        fun `includes TXT-only phones (no capability) in ranking`() {
            val txt = makeTxtRecord(modelQuality = 90, llmBackend = LlmBackend.LITERT)
            val phone = makePhone(capability = null, txtRecord = txt, score = 90, name = "txt-only")
            setPhones(phone)

            assertNotNull(manager.getBestPhone())
        }

        @Test
        fun `prefers phone with capability over TXT-only phone when score is higher`() {
            val capPhone = makePhone(
                capability = DeviceCapability("d1", "u1", null, "P1", LlmBackend.AICORE, 150, 8000, true),
                score = 160,
                name = "cap-phone",
                baseUrl = "http://cap/",
            )
            val txtPhone = makePhone(
                capability = null,
                txtRecord = makeTxtRecord(modelQuality = 90),
                score = 90,
                name = "txt-phone",
                baseUrl = "http://txt/",
            )
            setPhones(txtPhone, capPhone)

            val best = manager.getBestPhone()
            assertEquals("cap-phone", best?.serviceInfo?.serviceName)
        }
    }

    @Nested
    @DisplayName("calculateScore")
    inner class CalculateScoreTest {

        private fun calculateScore(
            txt: PhoneDiscoveryManager.PhoneTxtRecord?,
            cap: DeviceCapability?
        ): Int = manager.calculateScore(txt, cap)

        @Test
        fun `returns 0 when both txt and capability are null`() {
            assertEquals(0, calculateScore(null, null))
        }

        @Test
        fun `returns modelQuality from TXT when capability is null`() {
            val txt = makeTxtRecord(modelQuality = 70)
            assertEquals(70, calculateScore(txt, null))
        }

        @Test
        fun `uses capability score when capability is present (ignores txt)`() {
            val txt = makeTxtRecord(modelQuality = 70)
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.AICORE, 150, 8000, true)
            // Capability wins: 150 (modelQuality) + 10 (ramBonus for >=6000 MB) = 160
            assertEquals(160, calculateScore(txt, cap))
        }

        @Test
        fun `adds ramBonus 10 for at least 6000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 6000, true)
            assertEquals(60, calculateScore(null, cap))
        }

        @Test
        fun `adds ramBonus 6 for at least 4000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 4500, true)
            assertEquals(56, calculateScore(null, cap))
        }

        @Test
        fun `adds ramBonus 3 for at least 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 3500, true)
            assertEquals(53, calculateScore(null, cap))
        }

        @Test
        fun `adds ramBonus 0 for less than 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 2000, true)
            assertEquals(50, calculateScore(null, cap))
        }
    }

    @Nested
    @DisplayName("BLE advertisements")
    inner class BleAdvertisementTest {

        private fun ipv4(host: String): java.net.Inet4Address =
            java.net.InetAddress.getByName(host) as java.net.Inet4Address

        @Test
        fun `RSSI is refreshed on repeat advertisements for the same phone`() {
            val baseUrl = "http://192.168.1.42:8765/"
            val phone = makePhone(
                capability = DeviceCapability(
                    "d", "u", null, "P", LlmBackend.NONE, 70, 4000, true
                ),
                txtRecord = makeTxtRecord(),
                score = 76,
                name = "phone-42",
                baseUrl = baseUrl,
                rssi = -80,
            )
            manager.setDiscoveredPhonesForTest(listOf(phone))

            manager.onBleAdvertisement(
                ipv4 = ipv4("192.168.1.42"),
                port = 8765,
                modelQuality = 70,
                llmBackendOrdinal = LlmBackend.NONE.ordinal,
                rssi = -55,
            )

            val updated = manager.discoveredPhones.value.single { it.baseUrl == baseUrl }
            assertEquals(-55, updated.rssi, "RSSI must update in place on repeat advert")
            // Score unchanged — RSSI does not re-rank.
            assertEquals(76, updated.score)
        }
    }

    @Nested
    @DisplayName("setEnabled")
    inner class SetEnabledTest {

        @Test
        fun `setEnabled(false) clears the discovered-phone list`() {
            val phone = makePhone(
                capability = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 4000, true),
                score = 50,
                name = "preload"
            )
            manager.setDiscoveredPhonesForTest(listOf(phone))
            assertEquals(1, manager.discoveredPhones.value.size)

            manager.setEnabled(false)

            assertTrue(
                manager.discoveredPhones.value.isEmpty(),
                "Disabling discovery must clear the UI list immediately"
            )
        }

        @Test
        fun `setEnabled(false) is idempotent when called twice`() {
            manager.setEnabled(false)
            manager.setEnabled(false)
            assertTrue(manager.discoveredPhones.value.isEmpty())
        }
    }

    @Test
    fun `CAPABILITY_PATH constant is correct`() {
        assertEquals("/capability", PhoneDiscoveryManager.CAPABILITY_PATH)
    }

    @Test
    fun `stopDiscovery does not throw`() {
        manager.stopDiscovery()
    }

    @Nested
    @DisplayName("DiscoveryConstants")
    inner class DiscoveryConstantsTest {

        @Test
        fun `PRESENCE_STALENESS_MS is strictly greater than HEARTBEAT_INTERVAL_MS`() {
            assertTrue(
                DiscoveryConstants.PRESENCE_STALENESS_MS > DiscoveryConstants.HEARTBEAT_INTERVAL_MS,
                "A single missed heartbeat must not immediately evict a healthy phone"
            )
        }

        @Test
        fun `HEARTBEAT_INTERVAL_MS is 60 seconds`() {
            assertEquals(60_000L, DiscoveryConstants.HEARTBEAT_INTERVAL_MS)
        }

        @Test
        fun `PRESENCE_STALENESS_MS is 2x the heartbeat interval`() {
            assertEquals(2 * DiscoveryConstants.HEARTBEAT_INTERVAL_MS, DiscoveryConstants.PRESENCE_STALENESS_MS)
        }

        @Test
        fun `MAX_CONSECUTIVE_FAILURES is 3`() {
            assertEquals(3, DiscoveryConstants.MAX_CONSECUTIVE_FAILURES)
        }
    }
}
