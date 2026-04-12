package com.justb81.watchbuddy.phone.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM provider that sends prompts to a remote Ollama server via HTTP POST.
 *
 * Expected Ollama endpoint: POST /api/generate
 * The server URL is user-configurable (e.g. http://192.168.1.100:11434).
 */
class RemoteOllamaProvider(
    private val serverUrl: String,
    private val model: String = "gemma3:4b"
) : LlmProvider {

    override val displayName: String = "Ollama ($model @ $serverUrl)"

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean = false
    )

    @Serializable
    private data class OllamaResponse(
        val response: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val url = URL("${serverUrl.trimEnd('/')}/api/generate")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000 // LLM generation can be slow

            val body = json.encodeToString(OllamaRequest(model, prompt))
            connection.outputStream.bufferedWriter().use { it.write(body) }

            val code = connection.responseCode
            if (code != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                throw IllegalStateException("Ollama returned HTTP $code: $error")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val parsed = json.decodeFromString<OllamaResponse>(responseBody)
            if (parsed.response.isBlank()) {
                throw IllegalStateException("Ollama returned empty response")
            }
            parsed.response
        } finally {
            connection.disconnect()
        }
    }
}
