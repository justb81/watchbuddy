plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.justb81.watchbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.justb81.watchbuddy"
        minSdk = 26
        targetSdk = 35

        // versionCode: CI setzt VERSION_CODE (run_number). Phone-Offset 1000.
        val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = 1000 + ciVersionCode

        // versionName: release-please setzt VERSION_NAME, Fallback auf x-generic-string
        versionName = System.getenv("VERSION_NAME") ?: "0.1.5" // x-release-please-version

        // ── Trakt-Konfiguration ───────────────────────────────────────────────
        // Leer lassen → App nutzt keinen Token-Proxy / zeigt keinen Trakt-Login.
        // Werte können auch über CI-Umgebungsvariablen TRAKT_CLIENT_ID und
        // TOKEN_BACKEND_URL gesetzt werden (empfohlen für Release-Builds).
        buildConfigField(
            "String", "TRAKT_CLIENT_ID",
            "\"${System.getenv("TRAKT_CLIENT_ID") ?: ""}\""
        )
        buildConfigField(
            "String", "TOKEN_BACKEND_URL",
            "\"${System.getenv("TOKEN_BACKEND_URL") ?: ""}\""
        )
    }

    // CI-Signing: Keystore-Pfad + Credentials über Umgebungsvariablen
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Ktor + Netty bringen mehrere META-INF-Dateien mit — bei Konflikten einfach die erste nehmen
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

    // MediaPipe LLM (Gemma — RAM-adaptive)
    implementation(libs.mediapipe.tasks.genai)

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
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
