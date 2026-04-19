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
    private val bleScanner: PhoneBleScanner = mockk(relaxed = true)
    private lateinit var manager: PhoneDiscoveryManager

    @BeforeEach
    fun setUp() {
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsdManager
        manager = PhoneDiscoveryManager(context, httpClient, bleScanner)
    }

    // ── DiscoveredPhone construction helpers ───────────────────────────────────

    private fun makePhone(
        capability: DeviceCapability?,
        txtRecord: PhoneDiscoveryManager.PhoneTxtRecord? = null,
        score: Int = 0,
        name: String = "test"
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val serviceInfo = mockk<NsdServiceInfo>()
        every { serviceInfo.serviceName } returns name
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = serviceInfo,
            txtRecord = txtRecord,
            capability = capability,
            score = score,
            baseUrl = "http://test/"
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

    // ── getBestPhone ───────────────────────────────────────────────────────────

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

            val phone1 = makePhone(cap1, score = 0, name = "phone1")
            val phone2 = makePhone(cap2, score = 160, name = "phone2")

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
                name = "cap-phone"
            )
            val txtPhone = makePhone(
                capability = null,
                txtRecord = makeTxtRecord(modelQuality = 90),
                score = 90,
                name = "txt-phone"
            )
            setPhones(txtPhone, capPhone)

            val best = manager.getBestPhone()
            assertEquals("cap-phone", best?.serviceInfo?.serviceName)
        }
    }

    // ── calculateScore ─────────────────────────────────────────────────────────

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
        fun `returns 0 when txt is null and capability is null`() {
            assertEquals(0, calculateScore(null, null))
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
            assertEquals(60, calculateScore(null, cap)) // 50 + 10
        }

        @Test
        fun `adds ramBonus 6 for at least 4000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 4500, true)
            assertEquals(56, calculateScore(null, cap)) // 50 + 6
        }

        @Test
        fun `adds ramBonus 3 for at least 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 3500, true)
            assertEquals(53, calculateScore(null, cap)) // 50 + 3
        }

        @Test
        fun `adds ramBonus 0 for less than 3000 MB`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 2000, true)
            assertEquals(50, calculateScore(null, cap)) // 50 + 0
        }

        @Test
        fun `adds modelQuality to ramBonus for AICore with large RAM`() {
            val cap = DeviceCapability("d", "u", null, "P", LlmBackend.AICORE, 150, 8000, true)
            assertEquals(160, calculateScore(null, cap)) // 150 + 10
        }
    }

    // ── parseTxtRecord ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseTxtRecord")
    inner class ParseTxtRecordTest {

        private fun parseTxtRecord(serviceInfo: NsdServiceInfo): PhoneDiscoveryManager.PhoneTxtRecord? =
            manager.parseTxtRecord(serviceInfo)

        private fun mockServiceInfo(attrs: Map<String, ByteArray>): NsdServiceInfo {
            val info = mockk<NsdServiceInfo>()
            every { info.attributes } returns attrs
            return info
        }

        @Test
        fun `parses valid TXT records`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "1".toByteArray(),
                    "modelQuality" to "90".toByteArray(),
                    "llmBackend" to "LITERT".toByteArray()
                )
            )
            val result = parseTxtRecord(info)
            assertNotNull(result)
            assertEquals("1", result!!.version)
            assertEquals(90, result.modelQuality)
            assertEquals(LlmBackend.LITERT, result.llmBackend)
        }

        @Test
        fun `parses AICORE backend`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "1".toByteArray(),
                    "modelQuality" to "150".toByteArray(),
                    "llmBackend" to "AICORE".toByteArray()
                )
            )
            val result = parseTxtRecord(info)
            assertNotNull(result)
            assertEquals(LlmBackend.AICORE, result!!.llmBackend)
            assertEquals(150, result.modelQuality)
        }

        @Test
        fun `parses NONE backend`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "1".toByteArray(),
                    "modelQuality" to "0".toByteArray(),
                    "llmBackend" to "NONE".toByteArray()
                )
            )
            val result = parseTxtRecord(info)
            assertNotNull(result)
            assertEquals(LlmBackend.NONE, result!!.llmBackend)
        }

        @Test
        fun `returns null when version attribute is missing`() {
            val info = mockServiceInfo(
                mapOf(
                    "modelQuality" to "70".toByteArray(),
                    "llmBackend" to "LITERT".toByteArray()
                )
            )
            assertNull(
                parseTxtRecord(info),
                "Missing 'version' must hard-fail parsing; do not mask as NONE fallback"
            )
        }

        @Test
        fun `returns null when modelQuality attribute is missing`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "0.15.1".toByteArray(),
                    "llmBackend" to "LITERT".toByteArray()
                )
            )
            assertNull(
                parseTxtRecord(info),
                "Missing 'modelQuality' must hard-fail parsing; do not fall back to 0"
            )
        }

        @Test
        fun `returns null when llmBackend attribute is missing`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "0.15.1".toByteArray(),
                    "modelQuality" to "70".toByteArray()
                )
            )
            assertNull(
                parseTxtRecord(info),
                "Missing 'llmBackend' (distinct from unknown value) must hard-fail parsing"
            )
        }

        @Test
        fun `returns null when modelQuality is not a valid integer`() {
            val info = mockServiceInfo(
                mapOf(
                    "version" to "0.15.1".toByteArray(),
                    "modelQuality" to "not-a-number".toByteArray(),
                    "llmBackend" to "LITERT".toByteArray()
                )
            )
            assertNull(
                parseTxtRecord(info),
                "Unparseable 'modelQuality' must hard-fail parsing; do not fall back to 0"
            )
        }

        @Test
        fun `falls back to NONE when llmBackend is an unknown value`() {
            // A phone running a newer build may advertise an enum value this TV
            // build does not know about yet. We must not make the phone silently
            // invisible — fall back to LlmBackend.NONE instead.
            val info = mockServiceInfo(
                mapOf(
                    "version" to "0.15.1".toByteArray(),
                    "modelQuality" to "70".toByteArray(),
                    "llmBackend" to "UNKNOWN_BACKEND".toByteArray()
                )
            )
            val result = parseTxtRecord(info)
            assertNotNull(result)
            assertEquals(LlmBackend.NONE, result!!.llmBackend)
            // Other fields must be preserved so the phone still ranks correctly.
            assertEquals("0.15.1", result.version)
            assertEquals(70, result.modelQuality)
        }

        @Test
        fun `preserves semver version string verbatim`() {
            // The phone now advertises its real versionName (e.g. "0.15.1") in
            // the TXT `version` field, not a hardcoded protocol placeholder.
            val info = mockServiceInfo(
                mapOf(
                    "version" to "0.15.1".toByteArray(),
                    "modelQuality" to "90".toByteArray(),
                    "llmBackend" to "LITERT".toByteArray()
                )
            )
            val result = parseTxtRecord(info)
            assertNotNull(result)
            assertEquals("0.15.1", result!!.version)
        }

        @Test
        fun `returns null when all attributes map is empty`() {
            val info = mockServiceInfo(emptyMap())
            assertNull(parseTxtRecord(info))
        }
    }

    // ── constants ──────────────────────────────────────────────────────────────

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
        manager.stopDiscovery()
    }

    @Nested
    @DisplayName("setEnabled")
    inner class SetEnabledTest {

        @Test
        fun `setEnabled(false) clears discovered phones and stops BLE scanner`() {
            val phone = makePhone(
                capability = DeviceCapability("d", "u", null, "P", LlmBackend.NONE, 50, 4000, true),
                score = 50,
                name = "preload"
            )
            manager.setDiscoveredPhonesForTest(listOf(phone))
            assertEquals(1, manager.discoveredPhones.value.size)

            manager.setEnabled(false)

            assertTrue(manager.discoveredPhones.value.isEmpty())
            verify { bleScanner.stop() }
        }

        @Test
        fun `setEnabled(true) then setEnabled(false) are idempotent for repeated calls`() {
            manager.setEnabled(false)
            manager.setEnabled(false)
            manager.setEnabled(true)
            manager.setEnabled(true)
            // No exceptions, and the discovered list is still empty (no real NSD in test).
            assertTrue(manager.discoveredPhones.value.isEmpty())
        }
    }

    // ── DiscoveryConstants ─────────────────────────────────────────────────────

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
