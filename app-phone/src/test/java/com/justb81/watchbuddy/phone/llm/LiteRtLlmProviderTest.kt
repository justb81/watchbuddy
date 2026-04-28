package com.justb81.watchbuddy.phone.llm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("LiteRtLlmProvider")
class LiteRtLlmProviderTest {

    @TempDir
    lateinit var tempDir: File

    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
        every { context.filesDir } returns File(tempDir, "files")
    }

    @AfterEach
    fun tearDown() {
        LiteRtLlmProvider.resetGpuKnownBadForTesting()
    }

    private fun createModelFile() {
        val modelDir = File(File(tempDir, "files"), "llm_models")
        modelDir.mkdirs()
        File(modelDir, LlmOrchestrator.ModelVariant.GEMMA4_E2B.fileName).createNewFile()
    }

    private fun fakeHandle(response: String = "Generated recap"): LiteRtLlmProvider.EngineHandle =
        object : LiteRtLlmProvider.EngineHandle {
            override fun sendMessage(prompt: String): String = response
        }

    private fun throwingHandle(message: String = "Engine failure"): LiteRtLlmProvider.EngineHandle =
        object : LiteRtLlmProvider.EngineHandle {
            override fun sendMessage(prompt: String): String = throw RuntimeException(message)
        }

    private fun provider(factory: LiteRtLlmProvider.EngineFactory): LiteRtLlmProvider =
        LiteRtLlmProvider(context, LlmOrchestrator.ModelVariant.GEMMA4_E2B, factory)

    @Nested
    @DisplayName("GPU to CPU fallback — sendMessage failure")
    inner class SendMessageFallback {

        @Test
        fun `GPU sendMessage throws then CPU retry succeeds`() = runTest {
            createModelFile()
            var callCount = 0
            val factory = LiteRtLlmProvider.EngineFactory { _, useGpu ->
                callCount++
                if (useGpu) throwingHandle("OpenCL unavailable") else fakeHandle("CPU response")
            }
            val result = provider(factory).generate("test prompt")
            assertEquals("CPU response", result)
            assertEquals(2, callCount)
            assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
        }
    }

    @Nested
    @DisplayName("GPU to CPU fallback — init failure")
    inner class InitFallback {

        @Test
        fun `GPU engine init throws then CPU fallback succeeds`() = runTest {
            createModelFile()
            var callCount = 0
            val factory = LiteRtLlmProvider.EngineFactory { _, useGpu ->
                callCount++
                if (useGpu) throw RuntimeException("GPU init failed")
                else fakeHandle("CPU response")
            }
            val result = provider(factory).generate("test prompt")
            assertEquals("CPU response", result)
            assertEquals(2, callCount)
            assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())
        }
    }

    @Nested
    @DisplayName("gpuKnownBad latch")
    inner class GpuKnownBadLatch {

        @Test
        fun `second provider instance skips GPU when latch is set`() = runTest {
            createModelFile()
            val factory1 = LiteRtLlmProvider.EngineFactory { _, useGpu ->
                if (useGpu) throwingHandle("OpenCL unavailable") else fakeHandle("CPU response")
            }
            provider(factory1).generate("first prompt")
            assertTrue(LiteRtLlmProvider.isGpuKnownBadForTesting())

            var gpuRequested = false
            val factory2 = LiteRtLlmProvider.EngineFactory { _, useGpu ->
                if (useGpu) gpuRequested = true
                fakeHandle("CPU direct")
            }
            val result = provider(factory2).generate("second prompt")
            assertEquals("CPU direct", result)
            assertFalse(gpuRequested)
        }
    }

    @Nested
    @DisplayName("Both backends fail")
    inner class BothBackendsFail {

        @Test
        fun `exception propagates when GPU and CPU both fail`() = runTest {
            createModelFile()
            val factory = LiteRtLlmProvider.EngineFactory { _, _ -> throwingHandle("all failed") }
            assertThrows<RuntimeException> {
                provider(factory).generate("test prompt")
            }
        }
    }

    @Nested
    @DisplayName("Model file missing")
    inner class ModelFileMissing {

        @Test
        fun `throws IllegalStateException and never invokes factory`() = runTest {
            var factoryInvoked = false
            val factory = LiteRtLlmProvider.EngineFactory { _, _ ->
                factoryInvoked = true
                fakeHandle()
            }
            assertThrows<IllegalStateException> {
                provider(factory).generate("test prompt")
            }
            assertFalse(factoryInvoked)
        }
    }

    @Nested
    @DisplayName("Blank response handling")
    inner class BlankResponseHandling {

        @Test
        fun `blank GPU response throws ISE without latching gpuKnownBad`() = runTest {
            createModelFile()
            var callCount = 0
            val factory = LiteRtLlmProvider.EngineFactory { _, _ ->
                callCount++
                fakeHandle("")
            }
            assertThrows<IllegalStateException> {
                provider(factory).generate("test prompt")
            }
            assertFalse(LiteRtLlmProvider.isGpuKnownBadForTesting())
            assertEquals(1, callCount)
        }
    }
}
