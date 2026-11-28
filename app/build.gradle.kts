import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing (ADR-037). The key and its passwords live outside git (datasets/release/ is ignored).
// Point to the properties file with the env var CAPTIONS_KEYSTORE_PROPERTIES or with
// captions.keystore.properties=<path> in local.properties. Without it, release builds are unsigned
// (CI keeps working).
val keystoreProperties: Properties? = run {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    val path = System.getenv("CAPTIONS_KEYSTORE_PROPERTIES") ?: local.getProperty("captions.keystore.properties")
    path?.let(::file)?.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
}

// Each release must have a higher versionCode than the one installed, or Android refuses the update.
// Pass -PversionCode=N; otherwise the number of commits is used.
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toInt() ?: runCatching {
    val p = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().toInt().also { p.waitFor() }
}.getOrDefault(1)

android {
    namespace = "tw.captions.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.captions.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "0.2.$appVersionCode"

        ndk {
            // Phones (arm64) and the emulator (x86_64). Keeps the sherpa-onnx native libraries small.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        keystoreProperties?.let { k ->
            create("release") {
                storeFile = file(k.getProperty("storeFile"))
                storePassword = k.getProperty("storePassword")
                keyAlias = k.getProperty("keyAlias")
                keyPassword = k.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No R8 yet: it would need keep rules for sherpa-onnx (JNI), the Azure SDK and the Anthropic SDK.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    // CI runs lint as a report only (build/reports/lint-results-debug.html) for now.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
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

// Release APKs for phones carry only the arm64 native libraries (about half the size).
// -PwithX86=true keeps x86_64 too, to try a release build on the emulator.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        if (findProperty("withX86") != "true") variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
    }
}
