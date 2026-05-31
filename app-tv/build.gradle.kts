plugins {
    id("watchbuddy.android.application")
    id("watchbuddy.detekt")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.justb81.watchbuddy"

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
            .orElse("0.43.0").get() // x-release-please-version
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
    implementation(libs.compose.material.icons.core)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Room — persistent cache for JustWatch deep links
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // TV Provider — WatchNext content-provider access for WatchNextMetadataSource
    implementation(libs.androidx.tvprovider)

    // Error-prone annotations — compileOnly so R8 can resolve references from Tink
    // without bundling the annotation library in the APK.
    compileOnly(libs.errorprone.annotations)
}

// The TV app does not use WorkManager. If a transitive dep brings in
// androidx.work:work-runtime, its InitializationProvider auto-init crashes
// pre-Application.onCreate on release builds (Room reflectively invokes the
// no-arg constructor on WorkDatabase_Impl, which R8 full mode strips — see
// issue #232 and the 0.12.0 trace on #244). Dropping the group removes both
// the DEX classes and the merged-manifest contribution, so WorkManager cannot
// be registered as an androidx.startup initializer in the first place.
configurations.all {
    exclude(group = "androidx.work")
}

// Room schema export — lets KSP write a versioned JSON snapshot of the database
// schema next to the source so future Migration(n, n+1) objects can generate it.
// The schemas/ directory is committed to git; CI generates/updates the file on each build.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
