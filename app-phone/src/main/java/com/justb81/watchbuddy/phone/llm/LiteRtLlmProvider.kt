package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM provider backed by LiteRT-LM with a local Gemma model (.litertlm format).
 *
 * The model file (e.g. gemma-4-E2B-it.litertlm) must be present in the app's
 * internal files directory before calling [generate]. Model download is handled
 * separately by WorkManager (see SettingsViewModel.downloadModel).
 */
class LiteRtLlmProvider(
    private val context: Context,
    private val modelVariant: LlmOrchestrator.ModelVariant
) : LlmProvider {

    override val displayName: String = "LiteRT-LM (${modelVariant.fileName})"

    private var engine: Engine? = null

    private suspend fun getOrCreateEngine(): Engine {
        engine?.let { return it }

        val modelDir = File(context.filesDir, "llm_models")
        val modelPath = File(modelDir, modelVariant.fileName).absolutePath
        if (!File(modelPath).exists()) {
            throw IllegalStateException(
                "Model file not found: ${modelVariant.fileName}. Download it first via Settings."
            )
        }

        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU()
        )

        val newEngine = Engine(config)
        newEngine.initialize()
        engine = newEngine
        return newEngine
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val llm = getOrCreateEngine()
        val conversation = llm.createConversation(ConversationConfig())
        try {
            val message = conversation.sendMessage(prompt)
            val text = message.text
            if (text.isNullOrBlank()) {
                throw IllegalStateException("LiteRT-LM returned empty response")
            }
            text
        } finally {
            conversation.close()
        }
    }
}
