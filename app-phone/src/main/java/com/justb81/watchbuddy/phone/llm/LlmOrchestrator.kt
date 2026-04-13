package com.justb81.watchbuddy.phone.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.justb81.watchbuddy.core.model.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects and initializes the appropriate LLM backend based on available RAM.
 *
 * Priority:
 *   1. AICore (Gemini Nano) — if device supports it (no download, auto-updated)
 *   2. MediaPipe + Gemma 4 E2B BF16  — ≥ 6 GB free RAM  (~9.6 GB model)
 *   3. MediaPipe + Gemma 4 E2B INT8  — ≥ 4 GB free RAM  (~4.6 GB model)
 *   4. MediaPipe + Gemma 4 E2B INT4  — ≥ 3 GB free RAM  (~3.2 GB model) ← default for Pixel 6a / Nothing 2a
 *   5. No LLM — TMDB synopsis text only
 */
@Singleton
class LlmOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class LlmConfig(
        val backend: LlmBackend,
        val modelVariant: ModelVariant?,
        val qualityScore: Int           // used by TV for device ranking
    )

    enum class ModelVariant(val fileName: String, val requiredRamMb: Int, val qualityScore: Int) {
        GEMMA4_E2B_BF16 ("gemma-4-e2b-it-bf16.task",  requiredRamMb = 6_000, qualityScore = 90),
        GEMMA4_E2B_INT8 ("gemma-4-e2b-it-int8.task",  requiredRamMb = 4_000, qualityScore = 75),
        GEMMA4_E2B_INT4 ("gemma-4-e2b-it-int4.task",  requiredRamMb = 3_000, qualityScore = 60),
    }

    fun selectConfig(): LlmConfig {
        // 1. Check AICore availability (Android 14+, Pixel 8+ class devices)
        if (isAiCoreAvailable()) {
            return LlmConfig(LlmBackend.AICORE, null, qualityScore = 150)
        }

        // 2. Select MediaPipe model based on free RAM
        val freeRamMb = getFreeRamMb()
        val variant = ModelVariant.entries
            .sortedByDescending { it.qualityScore }
            .firstOrNull { freeRamMb >= it.requiredRamMb }

        return if (variant != null) {
            LlmConfig(LlmBackend.MEDIAPIPE_GPU, variant, variant.qualityScore)
        } else {
            LlmConfig(LlmBackend.NONE, null, qualityScore = 0)
        }
    }

    private fun isAiCoreAvailable(): Boolean {
        // AICore requires Android 14+ and supported hardware
        // Real check via com.google.android.aicore package presence
        return try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            Build.VERSION.SDK_INT >= 34
        } catch (e: Exception) {
            false
        }
    }

    private fun getFreeRamMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.availMem / 1_048_576).toInt()
    }
}
