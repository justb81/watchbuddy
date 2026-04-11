plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.justb81.watchbuddy.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Network
    api(libs.retrofit)
    api(libs.retrofit.serialization)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Serialization
    api(libs.kotlinx.serialization)

    // Coroutines
    api(libs.kotlinx.coroutines)

    // Room
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    api(libs.datastore.preferences)

    // Security
    api(libs.security.crypto)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
