package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM provider backed by Android AICore (Gemini Nano).
 *
 * AICore is available on Android 14+ with supported hardware (Pixel 8+ class).
 * The model is managed by Google Play Services — no manual download required.
 *
 * The [GenerativeModel] is held in [LlmEngineCache] so the bind to Play
 * Services happens once per process instead of once per recap call.
 */
class AiCoreLlmProvider(
    private val context: Context,
    private val engineCache: LlmEngineCache,
) : LlmProvider {

    override val displayName: String = "AICore (Gemini Nano)"

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val model = engineCache.getOrCreateAiCoreModel { createModel() }
        val response = model.generateContent(prompt)
        val text = response.text
        if (text.isNullOrBlank()) {
            error("AICore returned empty response")
        }
        text
    }

    /**
     * Pre-loads the AICore [GenerativeModel] off the request path. Swallows
     * exceptions so a missing AICore package or transient Play Services error
     * doesn't take down the warm-up coroutine — the inference path will surface
     * the failure via the cascade.
     */
    suspend fun warmUp() {
        try {
            engineCache.getOrCreateAiCoreModel { createModel() }
            DiagnosticLog.event(TAG, "warmUp ok")
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "warmUp skipped: ${e.javaClass.simpleName}", e)
        }
    }

    private fun createModel(): GenerativeModel {
        val config = generationConfig {
            this.context = this@AiCoreLlmProvider.context
        }
        return GenerativeModel(config)
    }

    private companion object {
        const val TAG = "AiCoreLlmProvider"
    }
}
