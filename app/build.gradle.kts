plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tw.captions.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.captions.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Phones (arm64) and the emulator (x86_64). Keeps the sherpa-onnx native libraries small.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Plain JVM tests: android.util.Log and friends return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// sherpa-onnx publishes its Android library on GitHub releases, not Maven: download it (checked by SHA-256).
val downloadSherpaAar by tasks.registering(DownloadFile::class) {
    url.set(Sherpa.androidAar.url)
    sha256.set(Sherpa.androidAar.sha256)
    output.set(layout.buildDirectory.file("sherpa/${Sherpa.androidAar.fileName}"))
}

dependencies {
    implementation(project(":core"))
    implementation(files(downloadSherpaAar.flatMap { it.output }))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.houbb:opencc4j:1.14.0")
    // Azure AI Speech (cloud engine, user's own key).
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.49.1")
    // AI proof-reading (ADR-026): the official Anthropic SDK.
    implementation("com.anthropic:anthropic-java:3.3.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
