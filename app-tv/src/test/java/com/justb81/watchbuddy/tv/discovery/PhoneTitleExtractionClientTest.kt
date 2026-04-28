package com.justb81.watchbuddy.tv.discovery

import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PhoneTitleExtractionClient — best-phone selection + in-flight dedup")
class PhoneTitleExtractionClientTest {

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private lateinit var server: MockWebServer
    private lateinit var client: PhoneTitleExtractionClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PhoneTitleExtractionClient(
            phoneDiscovery = phoneDiscovery,
            watchedShowSource = watchedShowSource,
            sharedHttpClient = OkHttpClient(),
        )
        coEvery { watchedShowSource.getCachedShows() } returns emptyList<TraktWatchedEntry>()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns null when no phones have an on-device LLM`() = runBlocking {
        every { phoneDiscovery.discoveredPhones } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                makeDiscoveredPhone(
                    baseUrl = "http://10.0.0.1:8765/",
                    backend = LlmBackend.NONE,
                    modelQuality = 0,
                )
            )
        )

        val result = client.extract(
            snapshotOf("com.netflix.ninja", "title" to "Pilot")
        )

        assertNull(result)
    }

    @Test
    fun `picks highest-scoring phone that actually has an LLM`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"showTitle":"Breaking Bad","season":1,"episode":1,"confidence":0.9}"""
            )
        )

        every { phoneDiscovery.discoveredPhones } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                makeDiscoveredPhone(
                    baseUrl = "http://dead-link/",
                    backend = LlmBackend.NONE,
                    modelQuality = 0,
                    score = 200,
                ),
                makeDiscoveredPhone(
                    baseUrl = server.url("/").toString(),
                    backend = LlmBackend.LITERT,
                    modelQuality = 90,
                    score = 95,
                ),
            )
        )

        val result = client.extract(
            snapshotOf("com.netflix.ninja", "title" to "Pilot")
        )

        assertNotNull(result)
        assertEquals("Breaking Bad", result!!.showTitle)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
        assertEquals(0.9f, result.confidence)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/scrobble/extract", recorded.path)
    }

    @Test
    fun `concurrent extract calls for the same title share one inference`() = runBlocking {
        // Only one MockResponse enqueued — if the client makes two HTTP calls
        // it will hang on the second and this test will time out.
        server.enqueue(
            MockResponse()
                .setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody("""{"showTitle":"Breaking Bad","season":1,"episode":1,"confidence":0.9}""")
        )

        every { phoneDiscovery.discoveredPhones } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                makeDiscoveredPhone(
                    baseUrl = server.url("/").toString(),
                    backend = LlmBackend.LITERT,
                    modelQuality = 90,
                )
            )
        )

        val snapshot = snapshotOf("com.netflix.ninja", "title" to "Pilot")
        val results = (1..3)
            .map { async(Dispatchers.IO) { client.extract(snapshot) } }
            .awaitAll()

        results.forEach { assertEquals("Breaking Bad", it?.showTitle) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `different titles DO trigger separate inferences even when concurrent`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"showTitle":"Breaking Bad","confidence":0.9}"""))
        server.enqueue(MockResponse().setBody("""{"showTitle":"The Bear","confidence":0.9}"""))

        every { phoneDiscovery.discoveredPhones } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                makeDiscoveredPhone(
                    baseUrl = server.url("/").toString(),
                    backend = LlmBackend.LITERT,
                    modelQuality = 90,
                )
            )
        )

        val s1 = snapshotOf("com.netflix.ninja", "title" to "Pilot")
        val s2 = snapshotOf("com.netflix.ninja", "title" to "System")
        val r1 = async(Dispatchers.IO) { client.extract(s1) }
        val r2 = async(Dispatchers.IO) { client.extract(s2) }
        val (a, b) = listOf(r1.await(), r2.await())

        assertTrue(setOf(a?.showTitle, b?.showTitle) == setOf("Breaking Bad", "The Bear"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HTTP failure returns null without throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        every { phoneDiscovery.discoveredPhones } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                makeDiscoveredPhone(
                    baseUrl = server.url("/").toString(),
                    backend = LlmBackend.LITERT,
                    modelQuality = 90,
                )
            )
        )

        val result = client.extract(
            snapshotOf("com.netflix.ninja", "title" to "Pilot")
        )

        assertNull(result)
    }

    private fun snapshotOf(packageName: String, vararg fields: Pair<String, String>) =
        MediaSnapshotBuilder(packageName).also { b ->
            listOf("albumArtist", "album", "artist", "displayTitle", "displaySubtitle", "title", "displayDescription")
                .forEach { key -> fields.toMap()[key]?.let { b.add("mediaSession.$key", it) } }
        }.build()

    private fun makeDiscoveredPhone(
        baseUrl: String,
        backend: LlmBackend,
        modelQuality: Int,
        score: Int = modelQuality,
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val serviceInfo = mockk<NsdServiceInfo>(relaxed = true)
        every { serviceInfo.serviceName } returns "test-$baseUrl"
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = serviceInfo,
            txtRecord = PhoneDiscoveryManager.PhoneTxtRecord(
                version = "1",
                modelQuality = modelQuality,
                llmBackend = backend,
            ),
            capability = DeviceCapability(
                deviceId = "dev-$baseUrl",
                userName = "alice",
                deviceName = "Pixel",
                llmBackend = backend,
                modelQuality = modelQuality,
                freeRamMb = 4096,
                isAvailable = true,
            ),
            score = score,
            baseUrl = baseUrl,
        )
    }
}
