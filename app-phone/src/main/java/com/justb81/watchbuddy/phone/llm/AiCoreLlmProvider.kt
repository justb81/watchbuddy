package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM provider backed by Android AICore (Gemini Nano).
 *
 * AICore is available on Android 14+ with supported hardware (Pixel 8+ class).
 * The model is managed by Google Play Services — no manual download required.
 */
class AiCoreLlmProvider(
    private val context: Context
) : LlmProvider {

    override val displayName: String = "AICore (Gemini Nano)"

    private var generativeModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        generativeModel?.let { return it }

        val config = generationConfig {
            this.context = this@AiCoreLlmProvider.context
        }

        val model = GenerativeModel(config)
        generativeModel = model
        return model
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val model = getOrCreateModel()
        val response = model.generateContent(prompt)
        val text = response.text
        if (text.isNullOrBlank()) {
            throw IllegalStateException("AICore returned empty response")
        }
        text
    }
}
