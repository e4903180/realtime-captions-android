import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The core is shared by the Android app (JVM target) and the iPhone app (iOS targets, built into
// CaptionsCore.xcframework). iOS targets need Xcode, so they are only added on macOS: Windows and
// Linux builds (and the Android CI job) stay exactly as before.
val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    if (isMac) {
        val xcf = XCFramework("CaptionsCore")
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
            it.binaries.framework {
                baseName = "CaptionsCore"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        jvmTest.dependencies {
            // Reference implementation for the OpenCC parity test.
            implementation("com.github.houbb:opencc4j:1.14.0")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Keep the old `./gradlew :core:test` command (README, CI) working: it runs the JVM tests.
tasks.register("test") {
    group = "verification"
    description = "Runs the core unit tests on the JVM."
    dependsOn("jvmTest")
}
