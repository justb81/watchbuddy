package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("PhoneDiscoveryManager")
class PhoneDiscoveryManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val httpClient: OkHttpClient = mockk(relaxed = true)
    private val bleScanner: PhoneBleScanner = mockk(relaxed = true)
    private lateinit var manager: PhoneDiscoveryManager
    private var fakeNow: Long = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        fakeNow = 1_700_000_000_000L
        manager = PhoneDiscoveryManager(context, httpClient, bleScanner) { fakeNow }
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
        fun `POLL_BASE_INTERVAL_MS is 60 seconds`() {
            assertEquals(60_000L, DiscoveryConstants.POLL_BASE_INTERVAL_MS)
        }

        @Test
        fun `HEARTBEAT_TICK_MS is 10 seconds`() {
            assertEquals(10_000L, DiscoveryConstants.HEARTBEAT_TICK_MS)
        }

        @Test
        fun `PRESENCE_STALENESS_MS is 2x the steady-state poll cadence`() {
            assertEquals(
                2 * DiscoveryConstants.POLL_BASE_INTERVAL_MS,
                DiscoveryConstants.PRESENCE_STALENESS_MS,
            )
        }

        @Test
        fun `POLL_BASE_INTERVAL_MS is strictly less than PRESENCE_STALENESS_MS`() {
            assertTrue(
                DiscoveryConstants.POLL_BASE_INTERVAL_MS < DiscoveryConstants.PRESENCE_STALENESS_MS,
                "A steady-state poll must keep a healthy phone fresh for scrobble dispatch",
            )
        }

        @Test
        fun `POLL_BACKOFF_INITIAL_MS is strictly less than POLL_BACKOFF_MAX_MS`() {
            assertTrue(
                DiscoveryConstants.POLL_BACKOFF_INITIAL_MS < DiscoveryConstants.POLL_BACKOFF_MAX_MS,
                "Backoff must have room to grow before being capped",
            )
        }

        @Test
        fun `HEARTBEAT_TICK_MS is at most POLL_BACKOFF_INITIAL_MS`() {
            assertTrue(
                DiscoveryConstants.HEARTBEAT_TICK_MS <= DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                "Driver tick must wake up at least once per backoff interval",
            )
        }

        @Test
        fun `MAX_CONSECUTIVE_FAILURES is 5`() {
            assertEquals(5, DiscoveryConstants.MAX_CONSECUTIVE_FAILURES)
        }
    }

    @Nested
    @DisplayName("Heartbeat backoff & Wi-Fi gate")
    @OptIn(ExperimentalCoroutinesApi::class)
    inner class HeartbeatBackoffTest {

        private val baseUrl = "http://192.168.1.10:8765/"

        private fun seedPhone(failCount: Int = 0): PhoneDiscoveryManager.DiscoveredPhone {
            val phone = PhoneDiscoveryManager.DiscoveredPhone(
                serviceInfo = mockk<NsdServiceInfo>().also {
                    every { it.serviceName } returns "phone"
                },
                txtRecord = makeTxtRecord(),
                capability = DeviceCapability("d1", "u1", null, "P1", LlmBackend.NONE, 70, 4000, true),
                score = 76,
                baseUrl = baseUrl,
                failCount = failCount,
                lastSuccessfulCheck = fakeNow,
            )
            manager.setDiscoveredPhonesForTest(listOf(phone))
            return phone
        }

        private fun stubCapabilityResponse(code: Int, body: String) {
            val responseBody = mockk<ResponseBody>(relaxed = true)
            every { responseBody.string() } returns body
            val response = mockk<Response>(relaxed = true)
            every { response.code } returns code
            every { response.isSuccessful } returns (code in 200..299)
            every { response.body } returns responseBody
            val call = mockk<Call>()
            every { call.execute() } returns response
            every { httpClient.newCall(any<Request>()) } returns call
        }

        private fun stubCapabilitySuccess(
            cap: DeviceCapability = DeviceCapability(
                "d1", "u1", null, "P1", LlmBackend.NONE, 70, 4000, true
            )
        ) {
            stubCapabilityResponse(code = 200, body = Json.encodeToString(cap))
        }

        private fun stubCapabilityRawSuccess(rawJson: String) {
            stubCapabilityResponse(code = 200, body = rawJson)
        }

        private fun stubCapabilityFailure() {
            val call = mockk<Call>()
            every { call.execute() } throws IOException("boom")
            every { httpClient.newCall(any<Request>()) } returns call
        }

        private fun advanceTimeTo(epochMs: Long) {
            fakeNow = epochMs
        }

        @Test
        fun `single failure does not evict and schedules next poll after initial backoff`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityFailure()

                manager.runTickForTest()

                val phones = manager.discoveredPhones.value
                assertEquals(1, phones.size, "A single failure must not evict")
                assertEquals(1, phones.single().failCount)
                assertEquals(
                    fakeNow + DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                    manager.nextPollAtForTest(baseUrl),
                )
            }

        @Test
        fun `Wi-Fi unavailable suppresses polling and does not advance failCount`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityFailure()
                manager.setWifiAvailableForTest(false)

                // Simulate ticks during a 2-minute Wi-Fi blip — 12 ticks at 10 s each.
                repeat(12) {
                    advanceTimeTo(fakeNow + DiscoveryConstants.HEARTBEAT_TICK_MS)
                    manager.runTickForTest()
                }

                val phones = manager.discoveredPhones.value
                assertEquals(1, phones.size, "Phone must not be evicted during Wi-Fi outage")
                assertEquals(0, phones.single().failCount, "failCount must not advance while Wi-Fi is down")
            }

        @Test
        fun `resetAllSchedulesNow brings every phone back to due-now and initial backoff`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityFailure()

                // One failure pushes the schedule into the future.
                manager.runTickForTest()
                assertEquals(1, manager.discoveredPhones.value.single().failCount)
                val backoffAfterFailure = manager.backoffForTest(baseUrl)
                assertNotNull(backoffAfterFailure)

                // Simulate Wi-Fi return.
                advanceTimeTo(fakeNow + 30_000L)
                manager.resetAllSchedulesNow()

                assertEquals(
                    fakeNow,
                    manager.nextPollAtForTest(baseUrl),
                    "Reset must bring nextPollAt back to now so the next tick polls immediately",
                )
                assertEquals(
                    DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                    manager.backoffForTest(baseUrl),
                    "Reset must restore initial backoff",
                )
                assertEquals(
                    1,
                    manager.discoveredPhones.value.single().failCount,
                    "Reset must NOT zero failCount — a phone that was already failing keeps its tally",
                )
            }

        @Test
        fun `5 consecutive failures evict the phone`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityFailure()

                // Failure 1 — schedules next poll at +10 s.
                manager.runTickForTest()
                assertEquals(1, manager.discoveredPhones.value.single().failCount)

                // Failure 2 — at +10 s, schedules +20 s.
                advanceTimeTo(fakeNow + DiscoveryConstants.POLL_BACKOFF_INITIAL_MS)
                manager.runTickForTest()
                assertEquals(2, manager.discoveredPhones.value.single().failCount)
                assertEquals(20_000L, manager.backoffForTest(baseUrl))

                // Failure 3 — at +20 s, schedules +40 s.
                advanceTimeTo(fakeNow + 20_000L)
                manager.runTickForTest()
                assertEquals(3, manager.discoveredPhones.value.single().failCount)
                assertEquals(40_000L, manager.backoffForTest(baseUrl))

                // Failure 4 — at +40 s, schedules +80 s.
                advanceTimeTo(fakeNow + 40_000L)
                manager.runTickForTest()
                assertEquals(4, manager.discoveredPhones.value.single().failCount)
                assertEquals(80_000L, manager.backoffForTest(baseUrl))

                // Failure 5 — eviction.
                advanceTimeTo(fakeNow + 80_000L)
                manager.runTickForTest()
                assertTrue(
                    manager.discoveredPhones.value.isEmpty(),
                    "Phone must be evicted after MAX_CONSECUTIVE_FAILURES failures",
                )
                assertNull(manager.nextPollAtForTest(baseUrl), "Schedule entry must be cleaned up on eviction")
            }

        @Test
        fun `successful poll after failures resets failCount and backoff`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()

                // Two consecutive failures.
                stubCapabilityFailure()
                manager.runTickForTest()
                advanceTimeTo(fakeNow + DiscoveryConstants.POLL_BACKOFF_INITIAL_MS)
                manager.runTickForTest()
                assertEquals(2, manager.discoveredPhones.value.single().failCount)

                // Success on the next poll.
                stubCapabilitySuccess()
                advanceTimeTo(fakeNow + 20_000L)
                manager.runTickForTest()

                val phone = manager.discoveredPhones.value.single()
                assertEquals(0, phone.failCount, "failCount must reset on success")
                assertEquals(fakeNow, phone.lastSuccessfulCheck)
                assertEquals(
                    DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                    manager.backoffForTest(baseUrl),
                    "Backoff must reset to initial after success",
                )
                assertEquals(
                    fakeNow + DiscoveryConstants.POLL_BASE_INTERVAL_MS,
                    manager.nextPollAtForTest(baseUrl),
                    "Next poll scheduled at steady-state cadence after success",
                )
            }

        @Test
        fun `phones whose nextPollAt is in the future are not polled this tick`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                // Override the default due-now schedule to push the phone 60 s
                // into the future, then advance only a partial tick.
                manager.setNextPollAtForTest(baseUrl, fakeNow + 60_000L)
                stubCapabilitySuccess()

                advanceTimeTo(fakeNow + DiscoveryConstants.HEARTBEAT_TICK_MS)
                manager.runTickForTest()

                val phone = manager.discoveredPhones.value.single()
                // lastSuccessfulCheck unchanged → no /capability call happened.
                assertEquals(
                    1_700_000_000_000L,
                    phone.lastSuccessfulCheck,
                    "Polling must skip phones whose nextPollAt is in the future",
                )
                io.mockk.verify(exactly = 0) { httpClient.newCall(any<Request>()) }
                assertFalse(phone.failCount > 0, "No failure should be recorded for a not-due phone")
            }

        @Test
        fun `tick without phones is a no-op and does not crash`() =
            runTest(UnconfinedTestDispatcher()) {
                manager.runTickForTest()
                assertTrue(manager.discoveredPhones.value.isEmpty())
            }

        // ── /capability validation & HTTP guarding (issues #538, #541) ────────

        @Test
        fun `HTTP 500 is a transport failure - increments failCount and applies backoff`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityResponse(code = 500, body = "")

                manager.runTickForTest()

                val phones = manager.discoveredPhones.value
                assertEquals(1, phones.size, "5xx is a transport failure, not invalid — phone stays")
                assertEquals(1, phones.single().failCount)
                assertEquals(
                    fakeNow + DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                    manager.nextPollAtForTest(baseUrl),
                )
            }

        @Test
        fun `HTTP 200 with empty body is a transport failure - bumps failCount`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityResponse(code = 200, body = "")

                manager.runTickForTest()

                val phones = manager.discoveredPhones.value
                assertEquals(1, phones.size)
                assertEquals(1, phones.single().failCount)
            }

        @Test
        fun `HTTP 200 with blank deviceId is invalid - phone evicted immediately`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                val malicious = DeviceCapability("", "u", null, "P", LlmBackend.NONE, 70, 4000, true)
                stubCapabilitySuccess(malicious)

                manager.runTickForTest()

                assertTrue(
                    manager.discoveredPhones.value.isEmpty(),
                    "Invalid capability must evict immediately, not after MAX_CONSECUTIVE_FAILURES",
                )
                assertNull(
                    manager.nextPollAtForTest(baseUrl),
                    "Schedule entry must be cleaned up when an invalid peer is evicted",
                )
            }

        @Test
        fun `HTTP 200 with negative modelQuality is invalid - phone evicted`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                val malicious = DeviceCapability("d1", "u1", null, "P1", LlmBackend.NONE, -1, 4000, true)
                stubCapabilitySuccess(malicious)

                manager.runTickForTest()

                assertTrue(manager.discoveredPhones.value.isEmpty())
            }

        @Test
        fun `HTTP 200 with malformed JSON is invalid - phone evicted`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                stubCapabilityRawSuccess("{not json")

                manager.runTickForTest()

                assertTrue(manager.discoveredPhones.value.isEmpty())
            }

        @Test
        fun `HTTP 200 with unknown JSON field is invalid - locks in strict decoder`() =
            runTest(UnconfinedTestDispatcher()) {
                seedPhone()
                // futureField is not on DeviceCapability — strict decoder must reject.
                stubCapabilityRawSuccess(
                    """{"deviceId":"d1","userName":"u1","deviceName":"P1","llmBackend":"NONE",""" +
                        """"modelQuality":70,"freeRamMb":4000,"isAvailable":true,"futureField":1}"""
                )

                manager.runTickForTest()

                assertTrue(
                    manager.discoveredPhones.value.isEmpty(),
                    "Unknown keys must be rejected — capability decoding must not silently default",
                )
            }
    }
}
