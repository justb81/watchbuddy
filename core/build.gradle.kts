plugins {
    id("watchbuddy.android.library")
}

android {
    namespace = "com.justb81.watchbuddy.core"

    defaultConfig {
        minSdk = 31
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
