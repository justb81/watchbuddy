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

// detekt is applied to every module holding Kotlin source. Config and baselines
// live next to each module so a failing rule clearly identifies which module
// violated it in CI output. See config/detekt/detekt.yml for the shared ruleset.
val detektVersion = libs.versions.detekt.get()

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        val moduleBaseline = file("detekt-baseline.xml")
        if (moduleBaseline.exists()) {
            baseline = moduleBaseline
        }
    }

    dependencies {
        add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            xml.required.set(true)
            sarif.required.set(true)
            html.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        jvmTarget = "17"
    }
}

// Convenience aggregate tasks so CI can run every module's detekt in one shot.
tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on every subproject."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}

tasks.register("detektBaselineAll") {
    group = "verification"
    description = "Regenerates detekt baselines for every subproject."
    dependsOn(subprojects.map { "${it.path}:detektBaseline" })
}
