import coil.addAndroidTestDependencies
import coil.addTestDependencies
import coil.setupLibraryModule
import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

setupLibraryModule()

dependencies {
    api(project(":coil-base"))

    implementation(libs.androidx.core)
    implementation(libs.svg)

    addTestDependencies(KotlinCompilerVersion.VERSION)
    addAndroidTestDependencies(KotlinCompilerVersion.VERSION)
}
