package com.justb81.watchbuddy.phone.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
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

        val newEngine = try {
            val gpuConfig = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
            Engine(gpuConfig).also { it.initialize() }
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend unavailable, falling back to CPU", e)
            val cpuConfig = EngineConfig(modelPath = modelPath, backend = Backend.CPU())
            Engine(cpuConfig).also { it.initialize() }
        }

        engine = newEngine
        return newEngine
    }

    companion object {
        private const val TAG = "LiteRtLlmProvider"
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val llm = getOrCreateEngine()
        val conversation = llm.createConversation(ConversationConfig())
        try {
            val response: Message = conversation.sendMessage(prompt)
            val text = response.toString()
            if (text.isBlank()) {
                throw IllegalStateException("LiteRT-LM returned empty response")
            }
            text
        } finally {
            conversation.close()
        }
    }
}
