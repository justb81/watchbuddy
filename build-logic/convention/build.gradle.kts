plugins {
    `kotlin-dsl`
}

dependencies {
    // Expose the generated LibrariesForLibs accessor class so that convention
    // plugin scripts can import org.gradle.accessors.dm.LibrariesForLibs and
    // call the<LibrariesForLibs>(). The class lives in a generated JAR that
    // belongs to the build-logic build; we locate it via the libs extension
    // that IS available here (in build scripts, not precompiled scripts).
    // See https://github.com/gradle/gradle/issues/15383
    compileOnly(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}
