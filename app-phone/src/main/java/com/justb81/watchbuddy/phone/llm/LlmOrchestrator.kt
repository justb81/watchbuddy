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
 *   2. LiteRT-LM + Gemma 4 E4B — ≥ 5 GB free RAM  (~3.4 GB model)
 *   3. LiteRT-LM + Gemma 4 E2B — ≥ 3 GB free RAM  (~2.4 GB model)
 *   4. No LLM — TMDB synopsis text only
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

    enum class ModelVariant(
        val fileName: String,
        val downloadUrl: String,
        val requiredRamMb: Int,
        val qualityScore: Int
    ) {
        GEMMA4_E4B(
            fileName = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            requiredRamMb = 5_000,
            qualityScore = 90
        ),
        GEMMA4_E2B(
            fileName = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            requiredRamMb = 3_000,
            qualityScore = 70
        ),
    }

    fun selectConfig(): LlmConfig {
        // 1. Check AICore availability (Android 14+, Pixel 8+ class devices)
        if (isAiCoreAvailable()) {
            return LlmConfig(LlmBackend.AICORE, null, qualityScore = 150)
        }

        // 2. Select LiteRT-LM model based on free RAM
        val freeRamMb = getFreeRamMb()
        val variant = ModelVariant.entries
            .sortedByDescending { it.qualityScore }
            .firstOrNull { freeRamMb >= it.requiredRamMb }

        return if (variant != null) {
            LlmConfig(LlmBackend.LITERT, variant, variant.qualityScore)
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
