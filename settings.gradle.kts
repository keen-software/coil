pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    val enableWasm = settings.providers
        .gradleProperty("enableWasm")
        .getOrElse("false")
        .toBoolean()
    if (enableWasm) {
        resolutionStrategy {
            eachPlugin {
                if (requested.id.id == "org.jetbrains.compose") {
                    useVersion("1.6.0-beta01")
                }
            }
        }
    }
}

rootProject.name = "coil-root"

// https://docs.gradle.org/7.4/userguide/declaring_dependencies.html#sec:type-safe-project-accessors
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Public modules
include(
    "coil",
    "coil-core",
    "coil-compose",
    "coil-compose-core",
    "coil-network-core",
    "coil-network-ktor",
    "coil-network-okhttp",
    "coil-gif",
    "coil-svg",
    "coil-video",
    "coil-bom",
    "coil-test",
)

// Private modules
include(
    "internal:benchmark",
    "internal:test-utils",
    "internal:test-paparazzi",
    "internal:test-roborazzi",
    "samples:compose",
    "samples:shared",
    "samples:view",
)
