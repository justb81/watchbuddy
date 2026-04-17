plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.justb81.watchbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.justb81.watchbuddy"   // same package as phone app!
        minSdk = 31
        targetSdk = 35

        // versionCode: CI sets VERSION_CODE (run_number).
        // Multiplier scheme avoids collisions between phone and TV APKs that
        // share the same applicationId: phone = *10+1, TV = *10+2.
        val ciVersionCode = providers.environmentVariable("VERSION_CODE")
            .orElse("1").get().toIntOrNull() ?: 1
        versionCode = ciVersionCode * 10 + 2

        // versionName: release-please sets VERSION_NAME, fallback to hardcoded value
        versionName = providers.environmentVariable("VERSION_NAME")
            .orElse("0.14.2").get() // x-release-please-version

        // Package native debug symbols into the AAB so Google Play Console can
        // symbolicate native stack traces (Tink via security-crypto is the main
        // contributor on TV).  SYMBOL_TABLE yields readable frames without the
        // size overhead of FULL (which also includes line numbers).
        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
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
}

dependencies {
    implementation(project(":core"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Compose for TV (Leanback replacement)
    // tv-foundation is transitively included in tv-material
    implementation(libs.compose.tv.material)

    // Standard Material3 — CircularProgressIndicator / LinearProgressIndicator for TV
    implementation(libs.compose.material3)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Image loading
    implementation(libs.coil)

    // WorkManager (background Trakt sync / scrobble history)
    implementation(libs.work.runtime)

    // Error-prone annotations — compileOnly so R8 can resolve references from Tink
    // without bundling the annotation library in the APK.
    compileOnly(libs.errorprone.annotations)

    // Testing
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
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
