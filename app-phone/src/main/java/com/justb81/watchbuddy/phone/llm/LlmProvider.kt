package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.justb81.watchbuddy.core.model.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface LlmProvider {
    suspend fun generate(prompt: String): String
    val displayName: String
}

class AiCoreLlmProvider(
    private val context: Context
) : LlmProvider {
    override val displayName = "Gemini Nano (on-device)"

    override suspend fun generate(prompt: String): String {
        // Android AICore / Gemini Nano integration
        // Requires com.google.android.gms:play-services-aicore at runtime
        throw UnsupportedOperationException("AICore not available in current build")
    }
}

class MediaPipeLlmProvider(
    private val context: Context,
    private val modelPath: String
) : LlmProvider {
    override val displayName = "MediaPipe (local model)"

    override suspend fun generate(prompt: String): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(512)
            .build()
        val inference = LlmInference.createFromOptions(context, options)
        return inference.generateResponse(prompt)
    }
}

@Singleton
class LlmProviderFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun create(config: LlmOrchestrator.LlmConfig): LlmProvider? {
        return when (config.backend) {
            LlmBackend.AICORE -> AiCoreLlmProvider(context)
            LlmBackend.MEDIAPIPE_GPU, LlmBackend.MEDIAPIPE_CPU -> {
                val variant = config.modelVariant ?: return null
                val modelFile = File(context.filesDir, "llm_models/${variant.fileName}")
                if (!modelFile.exists()) return null
                MediaPipeLlmProvider(context, modelFile.absolutePath)
            }
            LlmBackend.NONE -> null
        }
    }
}
