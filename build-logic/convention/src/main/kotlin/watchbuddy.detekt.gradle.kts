import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("io.gitlab.arturbosch.detekt")
}

val libs = the<LibrariesForLibs>()

configure<DetektExtension> {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    val moduleBaseline = file("detekt-baseline.xml")
    if (moduleBaseline.exists()) {
        baseline = moduleBaseline
    }
}

dependencies {
    add("detektPlugins", libs.detekt.formatting)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        xml.required.set(true)
        sarif.required.set(true)
        html.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}
