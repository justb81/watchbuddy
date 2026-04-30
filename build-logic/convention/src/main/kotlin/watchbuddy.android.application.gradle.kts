import org.gradle.accessors.dm.LibrariesForLibs

// Kotlin compiler plugins (compose, serialization) are NOT applied here because
// their Gradle plugin JARs are split into separate Maven artifacts in Kotlin 2.x
// and cannot be reliably discovered from the build-logic classpath without
// hard-coding versions. Modules that need them add alias(libs.plugins.*) directly.
plugins {
    id("com.android.application")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val libs = the<LibrariesForLibs>()

// CI signing: keystore path via environment variable; credentials via Gradle
// properties (watchbuddy.signing.*) written to ~/.gradle/gradle.properties by
// the workflow with chmod 600, so they never appear in env-var dumps or logs.
// takeIf guards against KEYSTORE_FILE being set to an empty string (e.g. when
// the secret is absent and the workflow sets the variable to '' as a fallback).
val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    compileSdk = 37

    defaultConfig {
        // Package native debug symbols into the AAB so Google Play Console can
        // symbolicate native stack traces. FULL is required — SYMBOL_TABLE strips
        // line numbers and Play Console still flags the bundle as "no symbols for
        // debugging" (#262).
        ndk {
            debugSymbolLevel = "FULL"
        }
    }

    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.gradleProperty("watchbuddy.signing.storePassword").orNull
                keyAlias = providers.gradleProperty("watchbuddy.signing.keyAlias").orNull
                keyPassword = providers.gradleProperty("watchbuddy.signing.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            // Use the release keystore for debug builds when available so that debug
            // and release APKs share the same signing certificate. Without this,
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

    // Android Lint: SARIF output is consumed by the GitHub code-scanning upload
    // in .github/workflows/build-android.yml. abortOnError makes the job fail
    // on any new finding; the committed lint-baseline.xml covers pre-existing
    // issues so they don't block new PRs.
    lint {
        sarifReport = true
        htmlReport = true
        xmlReport = false
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    add("implementation", libs.hilt.android)
    add("ksp", libs.hilt.compiler)

    add("testImplementation", libs.junit5.api)
    add("testImplementation", libs.junit5.params)
    add("testRuntimeOnly", libs.junit5.engine)
    add("testRuntimeOnly", libs.junit5.platform.launcher)
    add("testImplementation", libs.mockk)
    add("testImplementation", libs.mockk.android)
    add("testImplementation", libs.kotlinx.coroutines.test)
    add("testImplementation", libs.turbine)
    add("testImplementation", libs.robolectric)
    add("testImplementation", libs.androidx.test.core)
    add("testImplementation", libs.androidx.arch.core.testing)
    add("testImplementation", libs.okhttp.mockwebserver)
}
