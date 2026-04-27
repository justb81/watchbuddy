import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val libs = the<LibrariesForLibs>()

android {
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    add("testImplementation", libs.kotlinx.coroutines.test)
    add("testImplementation", libs.turbine)
    add("testImplementation", libs.okhttp.mockwebserver)
}
