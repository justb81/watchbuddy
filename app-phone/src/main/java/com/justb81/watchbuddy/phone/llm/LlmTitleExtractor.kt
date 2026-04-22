package com.justb81.watchbuddy.phone.llm

import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.LibraryHint
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side last-resort fallback for [MediaSessionScrobbler][com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler].
 *
 * Receives a raw `MediaMetadataSnapshot` (every field the streaming app shipped)
 * plus a list of library hints (shows the user already watches), asks the
 * on-device LLM to return normalized `(showTitle, season?, episode?)` in a
 * strict JSON shape, then validates the response:
 *   - JSON must parse cleanly (malformed output is rejected, no fuzzy salvage).
 *   - If the LLM claims a `libraryTraktId`, that ID must appear in the hint
 *     list — otherwise the field is cleared (blocks hallucinated IDs).
 *   - Confidence is clamped to `[0, 1]`.
 *
 * The scrobbler does its own cache match against the normalized title, so even
 * a successful LLM result never bypasses library membership checks.
 */
@Singleton
class LlmTitleExtractor @Inject constructor(
    private val llmProviderFactory: LlmProviderFactory,
) {
    companion object {
        private const val TAG = "LlmTitleExtractor"
        private const val MAX_HINTS = 50
        private const val MAX_SEASON = 100
        private const val MAX_EPISODE = 9_999
        private const val MIN_PRINTABLE_ASCII = 0x20
    }

    /**
     * Runs inference on the injected LLM cascade. Returns `null` when no
     * on-device LLM is available (the TV then falls through to its existing
     * TMDB search) or when the LLM output fails validation.
     */
    suspend fun extract(
        snapshot: MediaMetadataSnapshot,
        libraryHints: List<LibraryHint>,
    ): TitleExtractionResponse? {
        val trimmedHints = libraryHints.take(MAX_HINTS)
        val prompt = buildPrompt(snapshot, trimmedHints)
        val raw = runCatching { llmProviderFactory.generateOrNull(prompt) }
            .onFailure { Log.w(TAG, "LLM inference threw", it) }
            .getOrNull()
            ?: return null
        return parseAndValidate(raw, trimmedHints)
    }

    /** Package-private for unit tests that feed synthetic LLM output. */
    internal fun parseAndValidate(
        raw: String,
        libraryHints: List<LibraryHint>,
    ): TitleExtractionResponse? {
        val json = extractJsonObject(raw) ?: return null
        val parsed = runCatching {
            WatchBuddyJson.decodeFromString(TitleExtractionResponse.serializer(), json)
        }.onFailure { DiagnosticLog.warn(TAG, "LLM output failed strict JSON parse: ${it.message}") }
            .getOrNull()
            ?: return null

        val showTitle = parsed.showTitle?.trim()?.takeIf { it.isNotBlank() }
        val confidence = parsed.confidence.coerceIn(0f, 1f)
        val libraryTraktId = parsed.libraryTraktId?.takeIf { id ->
            libraryHints.any { it.traktId == id }
        }
        val season = parsed.season?.takeIf { it in 0..MAX_SEASON }
        val episode = parsed.episode?.takeIf { it in 0..MAX_EPISODE }
        return TitleExtractionResponse(
            showTitle = showTitle,
            season = season,
            episode = episode,
            libraryTraktId = libraryTraktId,
            confidence = confidence,
        )
    }

    /**
     * LLMs (especially the smaller LiteRT ones) often wrap JSON in prose or
     * markdown fences. Lift the first balanced `{...}` block out of the raw
     * response so strict deserialization has a shot. Tracks whether we're
     * inside a string literal (and respects the `\"` escape) so a `}` sitting
     * inside `"showTitle":"The Office }"` doesn't terminate the scan early.
     */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun buildPrompt(
        snapshot: MediaMetadataSnapshot,
        libraryHints: List<LibraryHint>,
    ): String {
        val hintsJson = libraryHints.joinToString(",\n") { h ->
            val parts = buildList {
                h.traktId?.let { add("\"traktId\":$it") }
                h.tmdbId?.let { add("\"tmdbId\":$it") }
                add("\"title\":\"${jsonEscape(h.title)}\"")
                h.year?.let { add("\"year\":$it") }
            }
            "{${parts.joinToString(",")}}"
        }
        val snapshotLines = buildString {
            appendLine("packageName: ${snapshot.packageName}")
            snapshot.title?.let { appendLine("TITLE: $it") }
            snapshot.displayTitle?.let { appendLine("DISPLAY_TITLE: $it") }
            snapshot.displaySubtitle?.let { appendLine("DISPLAY_SUBTITLE: $it") }
            snapshot.displayDescription?.let { appendLine("DISPLAY_DESCRIPTION: $it") }
            snapshot.artist?.let { appendLine("ARTIST: $it") }
            snapshot.albumArtist?.let { appendLine("ALBUM_ARTIST: $it") }
            snapshot.album?.let { appendLine("ALBUM: $it") }
            snapshot.subtitle?.let { appendLine("SUBTITLE: $it") }
        }
        return """
You extract TV-show metadata from Android MediaSession fields published by
streaming apps (Netflix, Prime Video, Disney+, Plex, Jellyfin, YouTube, etc.).
Different apps put the show name, season, and episode in different fields.

Input fields from the currently-playing session:
$snapshotLines
Shows the user already watches (prefer matching one of these if plausible):
[
$hintsJson
]

Return ONLY a single JSON object — no prose, no markdown, no code fences.

Shape:
{
  "showTitle": "normalized show title (prefer a hint title when it matches)",
  "season": 1,           // integer or null if not determinable
  "episode": 1,          // integer or null if not determinable
  "libraryTraktId": 12,  // integer traktId from the hint list, or null if no hint matches
  "confidence": 0.0      // float 0.0–1.0 — your self-assessed certainty
}

Rules:
- If nothing plausibly identifies a TV show (e.g. the user is watching news or music), set confidence to 0.
- Only set libraryTraktId to a value that appears verbatim in the hint list above.
- Do not invent shows; when in doubt return confidence 0.
""".trimIndent()
    }

    private fun jsonEscape(s: String): String = buildString(s.length + 2) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < MIN_PRINTABLE_ASCII) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
