package com.justb81.watchbuddy.phone.llm

import android.app.Application
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates an HTML slideshow recap for a TV show up to the current episode.
 *
 * Flow:
 *   1. Build prompt from watched episode synopses (TMDB) + show metadata
 *   2. LLM generates HTML with <img data-tmdb-still="S02E04"> placeholders
 *   3. Replace placeholders with real TMDB still image URLs
 *   4. Return final HTML string → sent to TV app for WebView rendering
 */
@Singleton
class RecapGenerator @Inject constructor(
    private val application: Application,
    private val llmOrchestrator: LlmOrchestrator
) {
    companion object {
        // TMDB still placeholder pattern: data-tmdb-still="S{season}E{episode}"
        private val STILL_PLACEHOLDER_REGEX = Regex("""data-tmdb-still="S(\d+)E(\d+)"""")
    }

    /**
     * @param show         TMDB show metadata
     * @param watchedEpisodes  All already-watched episodes (with overviews)
     * @param targetEpisode    The episode about to be watched (recap up to but not including this)
     * @param apiKey           TMDB API key (user's own key)
     */
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
            .takeLast(8)  // keep prompt manageable — last 8 episodes
            .joinToString("\n") { ep ->
                "S${ep.season_number.toString().padStart(2,'0')}E${ep.episode_number.toString().padStart(2,'0')} " +
                "\"${ep.name}\": ${ep.overview ?: application.getString(R.string.no_description)}"
            }

        return """
Du bist ein TV-Recap-Generator. Erstelle einen prägnanten, spoilerfreien "Was bisher geschah"-Recap für:

Serie: ${show.name}
Nächste Folge: S${target.season_number.toString().padStart(2,'0')}E${target.episode_number.toString().padStart(2,'0')} "${target.name}"

Bereits gesehene Folgen (die letzten 8):
$episodeSummaries

AUFGABE: Generiere eine animierte HTML-Slideshow mit 4–6 Folien als einzelnen HTML-String.

REGELN:
- Verwende für Standbilder den Platzhalter: <img data-tmdb-still="S02E04" alt="Szene"> (S und E anpassen)
- Keine externen URLs, keine <script>-Tags außer inline CSS-Animationen
- Halte es kurz und spannend, max. 2 Sätze pro Folie
- Sprache: Deutsch
- Format: vollständiges HTML-Fragment (nur <div>, kein <html>/<body>)
""".trimIndent()
    }

    private suspend fun inferWithLlm(prompt: String): String {
        // TODO: route to AICore or MediaPipe based on LlmOrchestrator.selectConfig()
        // Placeholder — real implementation connects to the active LLM backend
        return buildFallbackHtml("Recap wird geladen…")
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
