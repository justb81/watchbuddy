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

@DisplayName("LiteRtLlmProvider")
class LiteRtLlmProviderTest {

    @TempDir
    lateinit var tempDir: File

    private val context: Context = mockk(relaxed = true)
    private val modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B

    @BeforeEach
    fun setUp() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        File(tempDir, "llm_models").mkdirs()
        File(tempDir, "llm_models/${modelVariant.fileName}").writeText("stub")
        every { context.filesDir } returns tempDir
    }

    @AfterEach
    fun tearDown() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        unmockkAll()
    }

    private fun fakeHandle(
        response: String? = null,
        throwOnSend: Throwable? = null,
    ): LiteRtLlmProvider.EngineHandle = object : LiteRtLlmProvider.EngineHandle {
        override fun sendMessage(prompt: String): String {
            throwOnSend?.let { throw it }
            return response.orEmpty()
        }
    }

    private fun factory(
        handles: List<LiteRtLlmProvider.EngineHandle>,
        configs: MutableList<EngineConfig> = mutableListOf(),
        throwOnGpuInit: Throwable? = null,
    ): Pair<LiteRtLlmProvider.EngineFactory, MutableList<EngineConfig>> {
        val queue = ArrayDeque(handles)
        val result = LiteRtLlmProvider.EngineFactory { config ->
            configs += config
            if (throwOnGpuInit != null && config.backend is Backend.GPU) {
                throw throwOnGpuInit
            }
            queue.removeFirst()
        }
        return result to configs
    }

    @Test
    fun `generate falls back to CPU when GPU sendMessage throws`() = runTest {
        val gpu = fakeHandle(throwOnSend = RuntimeException("Can not find OpenCL library on this device"))
        val cpu = fakeHandle(response = "recap from CPU")
        val (f, configs) = factory(listOf(gpu, cpu))
        val provider = LiteRtLlmProvider(context, modelVariant, f)

        val result = provider.generate("hi")

        assertEquals("recap from CPU", result)
        assertEquals(2, configs.size)
        assertTrue(configs[0].backend is Backend.GPU)
        assertTrue(configs[1].backend is Backend.CPU)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `generate falls back to CPU when GPU engine init throws`() = runTest {
        val cpu = fakeHandle(response = "cpu response")
        val (f, configs) = factory(
            handles = listOf(cpu),
            throwOnGpuInit = RuntimeException("Can not initialize GPU"),
        )
        val provider = LiteRtLlmProvider(context, modelVariant, f)

        val result = provider.generate("hi")

        assertEquals("cpu response", result)
        assertEquals(2, configs.size)
        assertTrue(configs[0].backend is Backend.GPU)
        assertTrue(configs[1].backend is Backend.CPU)
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }

    @Test
    fun `subsequent instance skips GPU after first failure latches gpuKnownBad`() = runTest {
        val firstGpu = fakeHandle(throwOnSend = RuntimeException("Can not find OpenCL library on this device"))
        val firstCpu = fakeHandle(response = "first")
        val (firstFactory, _) = factory(listOf(firstGpu, firstCpu))
        LiteRtLlmProvider(context, modelVariant, firstFactory).generate("hi")
        assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())

        val secondCpu = fakeHandle(response = "second")
        val (secondFactory, secondConfigs) = factory(listOf(secondCpu))
        val result = LiteRtLlmProvider(context, modelVariant, secondFactory).generate("hi")

        assertEquals("second", result)
        assertEquals(1, secondConfigs.size)
        assertTrue(secondConfigs[0].backend is Backend.CPU)
    }

    @Test
    fun `generate propagates exception when CPU fallback also fails`() = runTest {
        val gpu = fakeHandle(throwOnSend = RuntimeException("GPU boom"))
        val cpu = fakeHandle(throwOnSend = RuntimeException("CPU boom"))
        val (f, _) = factory(listOf(gpu, cpu))
        val provider = LiteRtLlmProvider(context, modelVariant, f)

        val e = assertThrows<RuntimeException> { provider.generate("hi") }
        assertEquals("CPU boom", e.message)
    }

    @Test
    fun `generate throws IllegalStateException when model file missing`() = runTest {
        File(tempDir, "llm_models/${modelVariant.fileName}").delete()
        val neverCalled = LiteRtLlmProvider.EngineFactory { _ ->
            throw AssertionError("factory should not be called when model file is missing")
        }
        val provider = LiteRtLlmProvider(context, modelVariant, neverCalled)

        assertThrows<IllegalStateException> { provider.generate("hi") }
    }

    @Test
    fun `blank GPU response does not trigger CPU fallback`() = runTest {
        // Empty-response is our own guard, not a JNI failure: the cascade in
        // LlmProviderFactory should fall through to FallbackProvider without
        // latching gpuKnownBad.
        val gpu = fakeHandle(response = "")
        val (f, configs) = factory(listOf(gpu))
        val provider = LiteRtLlmProvider(context, modelVariant, f)

        assertThrows<IllegalStateException> { provider.generate("hi") }
        assertEquals(1, configs.size)
        assertTrue(configs[0].backend is Backend.GPU)
        assertFalse(LiteRtLlmProvider.isGpuKnownBadForTesting())
    }
}
