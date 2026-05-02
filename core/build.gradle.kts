plugins {
    id("watchbuddy.android.library")
    id("watchbuddy.detekt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.justb81.watchbuddy.core"

    defaultConfig {
        minSdk = 31
        // Propagate the Retrofit interface keep rule to all consuming app modules
        // (app-phone, app-tv) so new interfaces added here are automatically
        // protected without manual entries in their proguard-rules.pro files.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX Core (FileProvider for diagnostic share intent)
    api(libs.androidx.core.ktx)

    // Network
    api(libs.retrofit)
    api(libs.retrofit.serialization)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Serialization
    api(libs.kotlinx.serialization)

    // Coroutines
    api(libs.kotlinx.coroutines)

    // DataStore
    api(libs.datastore.preferences)
}
