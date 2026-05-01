package com.justb81.watchbuddy.core.scrobbler

/**
 * Per-streaming-app metadata profile.
 *
 * Captures stable, well-known behaviour differences between streaming apps: which
 * source-tag lines carry the show title, what S/E marker format they use, and
 * whether Phase 1 cache-match should be skipped entirely.
 *
 * Adding a profile is a one-line PR + a unit-test fixture. There is no remote
 * config — profiles ship in the APK and are version-controlled.
 *
 * **How to add a new profile** (when a streaming app shows up in TV Diagnostics
 * without a profile):
 * 1. Capture a MediaSession dump: `adb shell dumpsys media_session` while playing.
 * 2. Capture a notification dump: `adb shell dumpsys notification --noredact`.
 * 3. Identify which source tag holds the show title and how the S/E marker is encoded.
 * 4. Add an `AppProfile(...)` entry + an `AppProfilesTest` fixture. Ship.
 */
data class AppProfile(
    val packageName: String,
    /**
     * Source-tag prefixes to hoist before the default line order. Lines whose tag
     * starts with any prefix here are scored first; within this group the registry
     * order is preserved. Uses the same tag namespace as [MediaSnapshotBuilder]:
     * `mediaSession.*`, `notification.*`, `watchNext.*`.
     */
    val preferredSourceTags: List<String> = emptyList(),
    /**
     * Extra regexes to extract a `(season, episode)` pair beyond the default
     * `S##E##` pattern. Each regex must expose exactly two capture groups:
     * group 1 = season number, group 2 = episode number. The list is tried in
     * order; the default `S##E##` pattern is tried after all entries here.
     */
    val markerRegexes: List<Regex> = emptyList(),
    /**
     * When `true`, skip Phase 1 cache-match entirely and go straight to the LLM
     * extractor. Use for apps known to publish only the app name or completely
     * unusable metadata in MediaSession.
     */
    val skipPhase1: Boolean = false,
    /**
     * Optional hint appended verbatim to the LLM prompt as
     * `"App-specific note: <hint>"` to convey app-specific encoding quirks that
     * the LLM cannot infer from the evidence lines alone.
     */
    val llmHint: String? = null,
)

/** Static registry mapping package names to their [AppProfile]. */
object AppProfiles {

    val ALL: Map<String, AppProfile> = listOf(
        // Netflix on Android TV — ships S##:E## in notification.text
        AppProfile(
            packageName = "com.netflix.ninja",
            preferredSourceTags = listOf("notification.text", "notification.title", "mediaSession.title"),
            markerRegexes = listOf(Regex("""S(\d+):E(\d+)""", RegexOption.IGNORE_CASE)),
        ),
        // Netflix mobile/tablet variant occasionally installed on TV devices
        AppProfile(
            packageName = "com.netflix.mediaclient",
            preferredSourceTags = listOf("notification.text", "notification.title", "mediaSession.title"),
            markerRegexes = listOf(Regex("""S(\d+):E(\d+)""", RegexOption.IGNORE_CASE)),
        ),
        // Disney+ — show title in albumArtist; marker encoded as "T1 E1"
        AppProfile(
            packageName = "com.disney.disneyplus",
            preferredSourceTags = listOf("mediaSession.albumArtist", "watchNext.title", "notification.title"),
            markerRegexes = listOf(Regex("""T(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)),
        ),
        // Prime Video on living-room / TV — show title in albumArtist or album
        AppProfile(
            packageName = "com.amazon.amazonvideo.livingroom",
            preferredSourceTags = listOf("mediaSession.albumArtist", "mediaSession.album", "watchNext.title"),
        ),
        // Apple TV+ on Android TV — episode numbers embedded as "Episode N — Title"
        AppProfile(
            packageName = "com.apple.atve.androidtv.appletv",
            preferredSourceTags = listOf("watchNext.title", "notification.title"),
            llmHint = "Apple TV+ embeds episode numbers in EXTRA_TEXT as 'Episode 4 — Title'.",
        ),
        // Plex — show title in mediaSession.title; standard S##E## markers
        AppProfile(
            packageName = "com.plexapp.android",
            preferredSourceTags = listOf("mediaSession.title", "notification.title"),
        ),
        // Jellyfin — show title in mediaSession.title; standard S##E## markers
        AppProfile(
            packageName = "org.jellyfin.androidtv",
            preferredSourceTags = listOf("mediaSession.title", "notification.title"),
        ),
        // Kodi — show title in mediaSession.title only; no notification channel
        AppProfile(
            packageName = "org.xbmc.kodi",
            preferredSourceTags = listOf("mediaSession.title"),
        ),
        // Joyn (German free streaming, ProSiebenSat.1) — episode marker uses "Staffel Y, Folge X"
        AppProfile(
            packageName = "de.prosiebensat1digital.seventv",
            preferredSourceTags = listOf("watchNext.contentId", "watchNext.episodeNumber", "mediaSession.subtitle"),
            markerRegexes = listOf(
                // "Staffel 2, Folge 3" or "Staffel 2 Folge 3"
                Regex("""Staffel\s*(\d+)[,\s]+Folge\s*(\d+)""", RegexOption.IGNORE_CASE),
            ),
            llmHint = "App is Joyn (German free streaming, ProSiebenSat.1). Episode markers may use 'Folge'/'Staffel' German keywords.",
        ),
        // YouTube TV — titles are descriptive; skip Phase 1 to avoid noisy cache matches
        AppProfile(
            packageName = "com.google.android.youtube.tv",
            skipPhase1 = true,
            llmHint = "App is YouTube. The 'series' may be a creator's playlist; the title is usually descriptive ('Episode 47: How to ...'). If no SxxExx pattern is detected, return null rather than guessing.",
        ),
    ).associateBy { it.packageName }

    fun forPackage(pkg: String): AppProfile? = ALL[pkg]
}
