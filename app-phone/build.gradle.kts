import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.gradle.play.publisher)
}

android {
    namespace = "com.justb81.watchbuddy"
    compileSdk = 35

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
            .orElse("0.19.1").get() // x-release-please-version

        // ── Trakt configuration ───────────────────────────────────────────────
        // Leave empty → app does not use token proxy / does not show Trakt login.
        // Values can also be set via CI environment variables TRAKT_CLIENT_ID and
        // TOKEN_BACKEND_URL (recommended for release builds).
        buildConfigField(
            "String", "TRAKT_CLIENT_ID",
            "\"${providers.environmentVariable("TRAKT_CLIENT_ID").orElse("").get()}\""
        )
        buildConfigField(
            "String", "TOKEN_BACKEND_URL",
            "\"${providers.environmentVariable("TOKEN_BACKEND_URL").orElse("").get()}\""
        )

        // ── TMDB configuration ────────────────────────────────────────────────
        // A default TMDB API v3 key bundled at build time so the app works out of
        // the box without requiring users to supply their own key.  Users can still
        // override it in Settings.  Set via the TMDB_API_KEY CI secret; leave empty
        // in development builds to force users through the settings flow.
        buildConfigField(
            "String", "DEFAULT_TMDB_API_KEY",
            "\"${providers.environmentVariable("TMDB_API_KEY").orElse("").get()}\""
        )

        // Package native debug symbols into the AAB so Google Play Console can
        // symbolicate native stack traces.  LiteRT-LM, AICore and Tink all ship
        // .so libraries.  FULL is required — SYMBOL_TABLE strips line numbers
        // and Play Console still flags the bundle as "no symbols for debugging"
        // (#262).  AGP also emits native-debug-symbols.zip alongside the AAB,
        // which CI uploads to Play and attaches to the GitHub Release.
        ndk {
            debugSymbolLevel = "FULL"
        }
    }

    // CI signing: keystore path + credentials via environment variables.
    // takeIf guards against KEYSTORE_FILE being set to an empty string (e.g. when
    // the secret is absent and the workflow sets the variable to '' as a fallback).
    val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            // Use the release keystore for debug builds when available so that debug
            // and release APKs share the same signing certificate.  Without this,
            // upgrading from a CI debug APK to a release APK fails with
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE because each runner generates a
            // different ephemeral debug.keystore.
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Ktor (local HTTP server for TV ↔ Phone communication)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // LiteRT-LM (Gemma — RAM-adaptive)
    implementation(libs.litertlm.android)

    // AICore (Gemini Nano)
    implementation(libs.aicore)

    // WorkManager (background model updates)
    implementation(libs.work.runtime)

    // Security / Encrypted Storage
    implementation(libs.security.crypto)

    // Image loading
    implementation(libs.coil)

    // Testing
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
        layout.file(
            providers.environmentVariable("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE")
                .orElse("/dev/null")
                .map { file(it) }
        )
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
