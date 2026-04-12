package com.justb81.watchbuddy.phone.llm

import com.justb81.watchbuddy.core.model.TmdbEpisode

/**
 * Fallback "provider" that builds a simple HTML recap from TMDB episode synopses
 * without any LLM inference. Used when no LLM backend is available.
 */
class FallbackProvider(
    private val episodes: List<TmdbEpisode>
) : LlmProvider {

    override val displayName: String = "TMDB Synopsis Fallback"

    override suspend fun generate(prompt: String): String {
        val slides = episodes.takeLast(6).mapIndexed { index, ep ->
            val label = "S${ep.season_number.toString().padStart(2, '0')}" +
                    "E${ep.episode_number.toString().padStart(2, '0')}"
            val overview = ep.overview ?: "—"
            """
            <div class="slide" style="animation-delay:${index * 4}s">
              <img data-tmdb-still="$label" alt="$label">
              <h3>${ep.name} ($label)</h3>
              <p>$overview</p>
            </div>
            """.trimIndent()
        }

        return """
        <div style="font-family:sans-serif;color:white;padding:16px;">
          <style>
            .slide { opacity:0; animation: fadeSlide 4s ease-in-out forwards; margin-bottom:24px; }
            @keyframes fadeSlide { 0%{opacity:0;transform:translateY(20px)} 10%{opacity:1;transform:translateY(0)} 90%{opacity:1} 100%{opacity:0} }
            img { width:100%;border-radius:8px;margin-bottom:8px; }
            h3 { margin:4px 0; }
            p { font-size:14px;line-height:1.4; }
          </style>
          ${slides.joinToString("\n")}
        </div>
        """.trimIndent()
    }
}
