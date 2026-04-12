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
        fun `selects BF16 when freeRam is at least 6000 MB`() {
            mockFreeRam(6000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.MEDIAPIPE_GPU, config.backend)
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_BF16, config.modelVariant)
            assertEquals(90, config.qualityScore)
        }

        @Test
        fun `selects INT8 when freeRam is at least 4000 but less than 6000 MB`() {
            mockFreeRam(4500)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.MEDIAPIPE_GPU, config.backend)
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT8, config.modelVariant)
            assertEquals(75, config.qualityScore)
        }

        @Test
        fun `selects INT4 when freeRam is at least 3000 but less than 4000 MB`() {
            mockFreeRam(3500)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.MEDIAPIPE_GPU, config.backend)
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT4, config.modelVariant)
            assertEquals(60, config.qualityScore)
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
        fun `selects BF16 at exact 6000 MB boundary`() {
            mockFreeRam(6000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_BF16, config.modelVariant)
        }

        @Test
        fun `selects INT8 at exact 4000 MB boundary`() {
            mockFreeRam(4000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT8, config.modelVariant)
        }

        @Test
        fun `selects INT4 at exact 3000 MB boundary`() {
            mockFreeRam(3000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT4, config.modelVariant)
        }
    }

    @Nested
    @DisplayName("ModelVariant")
    inner class ModelVariantTest {
        @Test
        fun `entries are sorted by qualityScore descending`() {
            val sorted = LlmOrchestrator.ModelVariant.entries.sortedByDescending { it.qualityScore }
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_BF16, sorted[0])
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT8, sorted[1])
            assertEquals(LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT4, sorted[2])
        }

        @Test
        fun `each variant has correct file name`() {
            assertEquals("gemma-4-e2b-it-bf16.task", LlmOrchestrator.ModelVariant.GEMMA4_E2B_BF16.fileName)
            assertEquals("gemma-4-e2b-it-int8.task", LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT8.fileName)
            assertEquals("gemma-4-e2b-it-int4.task", LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT4.fileName)
        }

        @Test
        fun `each variant has correct RAM requirement`() {
            assertEquals(6_000, LlmOrchestrator.ModelVariant.GEMMA4_E2B_BF16.requiredRamMb)
            assertEquals(4_000, LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT8.requiredRamMb)
            assertEquals(3_000, LlmOrchestrator.ModelVariant.GEMMA4_E2B_INT4.requiredRamMb)
        }
    }

    @Nested
    @DisplayName("AICore detection")
    inner class AiCoreTest {
        // Note: On JVM, Build.VERSION.SDK_INT defaults to 0, so AICore check
        // naturally fails (requires >= 34). This tests the non-AICore path.
        // AICore package presence alone is not enough -- SDK check must also pass.

        @Test
        fun `falls back to MediaPipe when AICore package missing`() {
            mockFreeRam(8000)
            val config = orchestrator.selectConfig()
            assertEquals(LlmBackend.MEDIAPIPE_GPU, config.backend)
        }
    }
}
