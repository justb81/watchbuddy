package com.justb81.watchbuddy.phone.llm

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.justb81.watchbuddy.core.model.LlmBackend
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LlmOrchestrator")
class LlmOrchestratorTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val activityManager: ActivityManager = mockk(relaxed = true)
    private lateinit var orchestrator: LlmOrchestrator

    @BeforeEach
    fun setUp() {
        every { context.packageManager } returns packageManager
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        // Default: AICore not available (package not found)
        every { packageManager.getPackageInfo("com.google.android.aicore", 0) } throws
                PackageManager.NameNotFoundException()

        orchestrator = LlmOrchestrator(context)
    }

    private fun mockFreeRam(mb: Long) {
        every { activityManager.getMemoryInfo(any()) } answers {
            val memInfo = firstArg<ActivityManager.MemoryInfo>()
            memInfo.availMem = mb * 1_048_576L
        }
    }

    @Nested
    @DisplayName("RAM-based variant selection")
    inner class RamBasedSelection {
        @Test
        fun `selects E4B when freeRam is at least 5000 MB`() {
            mockFreeRam(6000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.LITERT, config.backend)
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E4B, config.modelVariant)
            assertEquals(90, config.qualityScore)
        }

        @Test
        fun `selects E2B when freeRam is at least 3000 but less than 5000 MB`() {
            mockFreeRam(4000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.LITERT, config.backend)
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B, config.modelVariant)
            assertEquals(70, config.qualityScore)
        }

        @Test
        fun `selects NONE when freeRam is less than 3000 MB`() {
            mockFreeRam(2000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.NONE, config.backend)
            assertNull(config.modelVariant)
            assertEquals(0, config.qualityScore)
        }

        @Test
        fun `selects E4B at exact 5000 MB boundary`() {
            mockFreeRam(5000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E4B, config.modelVariant)
        }

        @Test
        fun `selects E2B at exact 3000 MB boundary`() {
            mockFreeRam(3000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B, config.modelVariant)
        }
    }

    @Nested
    @DisplayName("ModelVariant")
    inner class ModelVariantTest {
        @Test
        fun `entries are sorted by qualityScore descending`() {
            val sorted = LlmOrchestrator.ModelVariant.entries.sortedByDescending { it.qualityScore }
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E4B, sorted[0])
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B, sorted[1])
        }

        @Test
        fun `each variant has correct file name`() {
            assertEquals("gemma-4-E4B-it.litertlm", LlmOrchestrator.ModelVariant.GEMMA4_E4B.fileName)
            assertEquals("gemma-4-E2B-it.litertlm", LlmOrchestrator.ModelVariant.GEMMA4_E2B.fileName)
        }

        @Test
        fun `each variant has correct RAM requirement`() {
            assertEquals(5_000, LlmOrchestrator.ModelVariant.GEMMA4_E4B.requiredRamMb)
            assertEquals(3_000, LlmOrchestrator.ModelVariant.GEMMA4_E2B.requiredRamMb)
        }

        @Test
        fun `each variant has correct download URL`() {
            assertTrue(LlmOrchestrator.ModelVariant.GEMMA4_E4B.downloadUrl.contains("gemma-4-E4B-it"))
            assertTrue(LlmOrchestrator.ModelVariant.GEMMA4_E2B.downloadUrl.contains("gemma-4-E2B-it"))
        }
    }

    @Nested
    @DisplayName("AICore detection")
    inner class AiCoreTest {
        // Note: On JVM, Build.VERSION.SDK_INT defaults to 0, so AICore check
        // naturally fails (requires >= 34). This tests the non-AICore path.
        // AICore package presence alone is not enough -- SDK check must also pass.

        @Test
        fun `falls back to LiteRT when AICore package missing`() {
            mockFreeRam(8000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.LITERT, config.backend)
        }
    }
}
