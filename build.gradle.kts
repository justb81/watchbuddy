// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.gradle.play.publisher) apply false
    alias(libs.plugins.detekt) apply false
}

// detekt is configured per-module via the watchbuddy.detekt convention plugin.
// Baselines live next to each module so a failing rule clearly identifies which
// module violated it in CI output. See config/detekt/detekt.yml for the shared ruleset.
val detektModules = listOf(":core", ":app-phone", ":app-tv")

// Convenience aggregate tasks so CI can run every module's detekt in one shot.
tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on every subproject."
    dependsOn(detektModules.map { "$it:detekt" })
}

tasks.register("detektBaselineAll") {
    group = "verification"
    description = "Regenerates detekt baselines for every subproject."
    dependsOn(detektModules.map { "$it:detektBaseline" })
}
