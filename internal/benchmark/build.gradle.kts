import coil3.androidTest
import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("com.android.test")
    id("kotlin-android")
    id("androidx.baselineprofile")
}

androidTest(name = "coil3.benchmark", config = true) {
    val targetProject = System.getProperty("project", "compose")
    defaultConfig {
        minSdk = 28
        buildConfigField("String", "PROJECT", "\"$targetProject\"")

        // Enables Composition Tracing for benchmarks
        // testInstrumentationRunnerArguments["androidx.benchmark.fullTracing.enable"] = "true"
        // Enables Method tracing for benchmarks. Be aware this skews the performance results,
        // so don't use it for measuring exact timing
        // testInstrumentationRunnerArguments["androidx.benchmark.profiling.mode"] = "MethodTracing"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs["debug"]
            matchingFallbacks += "release"
        }
    }
    testOptions {
        managedDevices {
            devices {
                create<ManagedVirtualDevice>("pixel7Api34") {
                    device = "Pixel 7"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
    targetProjectPath = ":samples:$targetProject"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    managedDevices += "pixel7Api34"
    useConnectedDevices = false
    enableEmulatorDisplay = false
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.espresso)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.tracing.perfetto)
    implementation(libs.androidx.tracing.perfetto.binary)
}
