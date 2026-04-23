package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@DisplayName("LiteRtLlmProvider")
class LiteRtLlmProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var context: Context
    private val modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B

    @BeforeEach
    fun setUp() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        val filesDir = tempDir.toFile()
        File(filesDir, "llm_models").mkdirs()
        File(filesDir, "llm_models/${modelVariant.fileName}").writeText("stub")
        context = mockk {
            every { filesDir } returns filesDir
        }
    }

    @AfterEach
    fun tearDown() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        unmockkAll()
    }

    private fun stubEngine(
        response: String? = null,
        throwOnSend: Throwable? = null,
    ): Engine {
        val message = mockk<Message>()
        every { message.toString() } returns (response ?: "")
        val conversation = mockk<Conversation>(relaxed = true)
        if (throwOnSend != null) {
            every { conversation.sendMessage(any()) } throws throwOnSend
        } else {
            every { conversation.sendMessage(any()) } returns message
        }
        return mockk<Engine>(relaxed = true) {
            every { createConversation(any()) } returns conversation
        }
    }

    private fun recordingFactory(
        engines: List<Engine>,
        throwOnGpuInit: Throwable? = null,
    ): Pair<LiteRtLlmProvider.EngineFactory, MutableList<EngineConfig>> {
        val calls = mutableListOf<EngineConfig>()
        val queue = ArrayDeque(engines)
        val factory = LiteRtLlmProvider.EngineFactory { config ->
            calls += config
            if (throwOnGpuInit != null && config.backend is Backend.GPU) {
                throw throwOnGpuInit
            }
            queue.removeFirst()
        }
        return factory to calls
    }

    @Test
    fun `generate falls back to CPU when GPU sendMessage throws`() = runTest {
        val gpu = stubEngine(
            throwOnSend = RuntimeException("Can not find OpenCL library on this device"),
        )
        val cpu = stubEngine(response = "recap from CPU")
        val (factory, calls) = recordingFactory(listOf(gpu, cpu))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val result = provider.generate("hi")

        assertEquals("recap from CPU", result)
        assertEquals(2, calls.size)
        assertTrue(calls[0].backend is Backend.GPU)
        assertTrue(calls[1].backend is Backend.CPU)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `generate falls back to CPU when GPU engine init throws`() = runTest {
        val cpu = stubEngine(response = "cpu response")
        val (factory, calls) = recordingFactory(
            engines = listOf(cpu),
            throwOnGpuInit = RuntimeException("Can not initialize GPU"),
        )
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val result = provider.generate("hi")

        assertEquals("cpu response", result)
        assertEquals(2, calls.size)
        assertTrue(calls[0].backend is Backend.GPU)
        assertTrue(calls[1].backend is Backend.CPU)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `subsequent instance skips GPU after first failure latches gpuKnownBad`() = runTest {
        val firstGpu = stubEngine(
            throwOnSend = RuntimeException("Can not find OpenCL library on this device"),
        )
        val firstCpu = stubEngine(response = "first")
        val (firstFactory, _) = recordingFactory(listOf(firstGpu, firstCpu))
        LiteRtLlmProvider(context, modelVariant, firstFactory).generate("hi")
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())

        val secondCpu = stubEngine(response = "second")
        val (secondFactory, secondCalls) = recordingFactory(listOf(secondCpu))
        val result = LiteRtLlmProvider(context, modelVariant, secondFactory).generate("hi")

        assertEquals("second", result)
        assertEquals(1, secondCalls.size)
        assertTrue(secondCalls[0].backend is Backend.CPU)
    }

    @Test
    fun `generate propagates exception when CPU fallback also fails`() = runTest {
        val gpu = stubEngine(throwOnSend = RuntimeException("GPU boom"))
        val cpu = stubEngine(throwOnSend = RuntimeException("CPU boom"))
        val (factory, _) = recordingFactory(listOf(gpu, cpu))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val e = assertThrows<RuntimeException> { provider.generate("hi") }
        assertEquals("CPU boom", e.message)
    }

    @Test
    fun `generate throws IllegalStateException when model file missing`() = runTest {
        File(tempDir.toFile(), "llm_models/${modelVariant.fileName}").delete()
        val factory = LiteRtLlmProvider.EngineFactory { _ -> fail("factory should not be called") }
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        assertThrows<IllegalStateException> { provider.generate("hi") }
    }

    @Test
    fun `blank GPU response does not trigger CPU fallback`() = runTest {
        // Empty-response is our own guard, not a JNI failure: the cascade in
        // LlmProviderFactory should fall through to FallbackProvider without
        // latching gpuKnownBad.
        val gpu = stubEngine(response = "")
        val (factory, calls) = recordingFactory(listOf(gpu))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        assertThrows<IllegalStateException> { provider.generate("hi") }
        assertEquals(1, calls.size)
        assertTrue(calls[0].backend is Backend.GPU)
        assertFalse(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }
}
