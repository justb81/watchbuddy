package com.justb81.watchbuddy.phone.llm

import android.content.Context

/**
 * LLM provider backed by Android AICore (Gemini Nano).
 *
 * AICore is available on Android 14+ with supported hardware (Pixel 8+ class).
 * The model is managed by Google Play Services — no manual download required.
 *
 * TODO: Replace stub with real AICore GenerativeModel calls once
 *       com.google.android.gms:play-services-aicore is added as a dependency.
 *       API reference: https://developer.android.com/ai/aicore
 */
class AiCoreLlmProvider(
    private val context: Context
) : LlmProvider {

    override val displayName: String = "AICore (Gemini Nano)"

    override suspend fun generate(prompt: String): String {
        // TODO: Implement with play-services-aicore GenerativeModel API:
        //   val generativeModel = GenerativeModel.newBuilder()
        //       .setModelName("gemini-nano")
        //       .build()
        //   val response = generativeModel.generateContent(prompt)
        //   return response.text ?: throw IllegalStateException("AICore returned empty response")
        throw UnsupportedOperationException(
            "AICore provider not yet implemented — add play-services-aicore dependency"
        )
    }
}
