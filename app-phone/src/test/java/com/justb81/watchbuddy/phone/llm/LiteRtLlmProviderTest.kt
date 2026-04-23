package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        val dir = tempDir.toFile()
        File(dir, "llm_models").mkdirs()
        File(dir, "llm_models/${modelVariant.fileName}").writeText("stub")
        context = mockk()
        every { context.filesDir } returns dir
    }

    @AfterEach
    fun tearDown() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        unmockkAll()
    }

    private class FakeHandle(
        private val response: String? = null,
        private val throwOnSend: Throwable? = null,
    ) : LiteRtLlmProvider.EngineHandle {
        var closed: Boolean = false
            private set
        var sendCount: Int = 0
            private set

        override fun sendMessage(prompt: String): String {
            sendCount++
            throwOnSend?.let { throw it }
            return response.orEmpty()
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingFactory(
        private val handles: ArrayDeque<LiteRtLlmProvider.EngineHandle>,
        private val throwOnGpuInit: Throwable? = null,
    ) : LiteRtLlmProvider.EngineFactory {
        val configs: MutableList<EngineConfig> = mutableListOf()

        override fun create(config: EngineConfig): LiteRtLlmProvider.EngineHandle {
            configs += config
            if (throwOnGpuInit != null && config.backend is Backend.GPU) {
                throw throwOnGpuInit
            }
            return handles.removeFirst()
        }
    }

    @Test
    fun `generate falls back to CPU when GPU sendMessage throws`() = runTest {
        val gpu = FakeHandle(
            throwOnSend = RuntimeException("Can not find OpenCL library on this device"),
        )
        val cpu = FakeHandle(response = "recap from CPU")
        val factory = RecordingFactory(ArrayDeque(listOf(gpu, cpu)))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val result = provider.generate("hi")

        assertEquals("recap from CPU", result)
        assertEquals(2, factory.configs.size)
        assertTrue(factory.configs[0].backend is Backend.GPU)
        assertTrue(factory.configs[1].backend is Backend.CPU)
        assertTrue(gpu.closed, "GPU handle should be closed before CPU retry")
        assertEquals(1, gpu.sendCount)
        assertEquals(1, cpu.sendCount)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `generate falls back to CPU when GPU engine init throws`() = runTest {
        val cpu = FakeHandle(response = "cpu response")
        val factory = RecordingFactory(
            handles = ArrayDeque(listOf(cpu)),
            throwOnGpuInit = RuntimeException("Can not initialize GPU"),
        )
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val result = provider.generate("hi")

        assertEquals("cpu response", result)
        assertEquals(2, factory.configs.size)
        assertTrue(factory.configs[0].backend is Backend.GPU)
        assertTrue(factory.configs[1].backend is Backend.CPU)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `subsequent instance skips GPU after first failure latches gpuKnownBad`() = runTest {
        val firstGpu = FakeHandle(
            throwOnSend = RuntimeException("Can not find OpenCL library on this device"),
        )
        val firstCpu = FakeHandle(response = "first")
        val firstFactory = RecordingFactory(ArrayDeque(listOf(firstGpu, firstCpu)))
        LiteRtLlmProvider(context, modelVariant, firstFactory).generate("hi")
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())

        val secondCpu = FakeHandle(response = "second")
        val secondFactory = RecordingFactory(ArrayDeque(listOf(secondCpu)))
        val result = LiteRtLlmProvider(context, modelVariant, secondFactory).generate("hi")

        assertEquals("second", result)
        assertEquals(1, secondFactory.configs.size)
        assertTrue(secondFactory.configs[0].backend is Backend.CPU)
    }

    @Test
    fun `generate propagates exception when CPU fallback also fails`() = runTest {
        val gpu = FakeHandle(throwOnSend = RuntimeException("GPU boom"))
        val cpu = FakeHandle(throwOnSend = RuntimeException("CPU boom"))
        val factory = RecordingFactory(ArrayDeque(listOf(gpu, cpu)))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        val e = assertThrows<RuntimeException> { provider.generate("hi") }
        assertEquals("CPU boom", e.message)
    }

    @Test
    fun `generate throws IllegalStateException when model file missing`() = runTest {
        File(tempDir.toFile(), "llm_models/${modelVariant.fileName}").delete()
        val factory = LiteRtLlmProvider.EngineFactory { _ ->
            throw AssertionError("factory should not be called when model file is missing")
        }
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        assertThrows<IllegalStateException> { provider.generate("hi") }
    }

    @Test
    fun `blank GPU response does not trigger CPU fallback`() = runTest {
        // Empty-response is our own guard, not a JNI failure: the cascade in
        // LlmProviderFactory should fall through to FallbackProvider without
        // latching gpuKnownBad.
        val gpu = FakeHandle(response = "")
        val factory = RecordingFactory(ArrayDeque(listOf(gpu)))
        val provider = LiteRtLlmProvider(context, modelVariant, factory)

        assertThrows<IllegalStateException> { provider.generate("hi") }
        assertEquals(1, factory.configs.size)
        assertTrue(factory.configs[0].backend is Backend.GPU)
        assertFalse(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }
}
