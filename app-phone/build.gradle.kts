import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    id("watchbuddy.android.application")
    id("watchbuddy.detekt")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.play.publisher)
}

android {
    namespace = "com.justb81.watchbuddy"

    defaultConfig {
        applicationId = "com.justb81.watchbuddy"
        minSdk = 34
        targetSdk = 35

        // versionCode: CI sets VERSION_CODE (run_number).
        // Multiplier scheme avoids collisions between phone and TV APKs that
        // share the same applicationId: phone = *10+1, TV = *10+2.
        val ciVersionCode = providers.environmentVariable("VERSION_CODE")
            .orElse("1").get().toIntOrNull() ?: 1
        versionCode = ciVersionCode * 10 + 1

        // versionName: release-please sets VERSION_NAME, fallback to hardcoded value
        versionName = providers.environmentVariable("VERSION_NAME")
            .orElse("0.42.0").get() // x-release-please-version

        // ── Trakt configuration ───────────────────────────────────────────────
        buildConfigField(
            "String", "TRAKT_CLIENT_ID",
            "\"${providers.environmentVariable("TRAKT_CLIENT_ID").orElse("").get()}\""
        )
        buildConfigField(
            "String", "TOKEN_BACKEND_URL",
            "\"${providers.environmentVariable("TOKEN_BACKEND_URL").orElse("").get()}\""
        )

        // ── TMDB configuration ────────────────────────────────────────────────
        buildConfigField(
            "String", "DEFAULT_TMDB_API_KEY",
            "\"${providers.environmentVariable("TMDB_API_KEY").orElse("").get()}\""
        )
    }

    // Ktor + Netty bring multiple META-INF files — exclude duplicates
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Ktor (local HTTP server for TV ↔ Phone communication)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.auth)

    // LiteRT-LM (Gemma — RAM-adaptive)
    implementation(libs.litertlm.android)

    // AICore (Gemini Nano)
    implementation(libs.aicore)

    // WorkManager (background model updates)
    implementation(libs.work.runtime)

    // HTML sanitization for LLM recap output (prevents XSS in WebView)
    implementation(libs.jsoup)

    // Security / Encrypted Storage (Tink AEAD + Android Keystore-wrapped KEK)
    implementation(libs.tink.android)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Testing
    testImplementation(libs.ktor.server.test.host)
}

// Gradle Play Publisher — uploads phone + TV AABs as one atomic Play edit.
// The workflow stages both AABs into play-artifacts/; GPP's artifactDir mode
// uploads every AAB found there under a single edit. Each AAB carries its own
// R8 mapping.txt embedded by AGP at BUNDLE-METADATA/com.android.tools.build.
// obfuscation/proguard.map, which Play reads per versionCode for stack-trace
// de-obfuscation (#273). Keeping the plugin on app-phone only means there is
// a single `publishReleaseBundle` task, not one per module — the shared
// applicationId requires a single release on the track.
play {
    serviceAccountCredentials.set(
        providers.environmentVariable("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE")
            .orElse("/dev/null")
            .map { path -> layout.projectDirectory.file(path) }
    )

    track.set(providers.gradleProperty("playTrack").orElse("internal"))

    releaseStatus.set(
        providers.gradleProperty("playStatus")
            .orElse("COMPLETED")
            .map { ReleaseStatus.valueOf(it) }
    )

    defaultToAppBundles.set(true)
    resolutionStrategy.set(ResolutionStrategy.FAIL)

    // Upload AABs from the workflow's staging dir rather than this module's
    // own output, so both phone and TV AABs ship in the same Play edit.
    artifactDir.set(rootProject.layout.projectDirectory.dir("play-artifacts"))
}
