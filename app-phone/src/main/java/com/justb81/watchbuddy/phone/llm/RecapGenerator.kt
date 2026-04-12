package com.justb81.watchbuddy.phone.llm

import android.app.Application
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecapGenerator @Inject constructor(
    private val application: Application,
    private val llmOrchestrator: LlmOrchestrator,
    private val providerFactory: LlmProviderFactory
) {
    companion object {
        private val STILL_PLACEHOLDER_REGEX = Regex("""data-tmdb-still="S(\d+)E(\d+)"""")
    }

    suspend fun generateRecap(
        show: TmdbShow,
        watchedEpisodes: List<TmdbEpisode>,
        targetEpisode: TmdbEpisode,
        apiKey: String
    ): String {
        val prompt = buildPrompt(show, watchedEpisodes, targetEpisode)
        val rawHtml = inferWithLlm(prompt)
        return replaceTmdbPlaceholders(rawHtml, watchedEpisodes, apiKey)
    }

    private fun buildPrompt(
        show: TmdbShow,
        episodes: List<TmdbEpisode>,
        target: TmdbEpisode
    ): String {
        val episodeSummaries = episodes
            .takeLast(8)
            .joinToString("\n") { ep ->
                "S${ep.season_number.toString().padStart(2,'0')}E${ep.episode_number.toString().padStart(2,'0')} " +
                "\"${ep.name}\": ${ep.overview ?: application.getString(R.string.no_description)}"
            }

        val language = LocaleHelper.getLlmResponseLanguage()

        return """
You are a TV recap generator. Create a concise, spoiler-free "Previously on" recap for:

Show: ${show.name}
Next episode: S${target.season_number.toString().padStart(2,'0')}E${target.episode_number.toString().padStart(2,'0')} "${target.name}"

Already watched episodes (last 8):
$episodeSummaries

TASK: Generate an animated HTML slideshow with 4–6 slides as a single HTML string.

RULES:
- Use this placeholder for still images: <img data-tmdb-still="S02E04" alt="Scene"> (adjust S and E)
- No external URLs, no <script> tags — only inline CSS animations
- Keep it short and engaging, max 2 sentences per slide
- Format: complete HTML fragment (only <div>, no <html>/<body>)

Respond in $language.
""".trimIndent()
    }

    private suspend fun inferWithLlm(prompt: String): String {
        val config = llmOrchestrator.selectConfig()
        val provider = providerFactory.create(config)
            ?: return buildFallbackHtml(application.getString(R.string.no_description))

        return try {
            provider.generate(prompt)
        } catch (e: Exception) {
            buildFallbackHtml(application.getString(R.string.error_generic))
        }
    }

    private fun replaceTmdbPlaceholders(
        html: String,
        episodes: List<TmdbEpisode>,
        apiKey: String
    ): String {
        return STILL_PLACEHOLDER_REGEX.replace(html) { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val episode = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val stillPath = episodes
                .find { it.season_number == season && it.episode_number == episode }
                ?.still_path
            val url = TmdbImageHelper.still(stillPath) ?: ""
            """src="$url""""
        }
    }

    private fun buildFallbackHtml(message: String) = """
        <div style="display:flex;align-items:center;justify-content:center;height:100%;color:white;font-family:sans-serif;">
          <p>$message</p>
        </div>
    """.trimIndent()
}
