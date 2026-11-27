import org.gradle.internal.os.OperatingSystem

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val libsDir = layout.buildDirectory.dir("sherpa")

val downloadSherpaJar by tasks.registering(DownloadFile::class) {
    url.set(Sherpa.jvmJar.url)
    sha256.set(Sherpa.jvmJar.sha256)
    output.set(libsDir.map { it.file(Sherpa.jvmJar.fileName) })
}

// Native libraries for the machine running the benchmark (Windows x64 or Linux x64).
val nativeArtifact = if (OperatingSystem.current().isWindows) Sherpa.nativeWinX64 else Sherpa.nativeLinuxX64
val downloadSherpaNative by tasks.registering(DownloadFile::class) {
    url.set(nativeArtifact.url)
    sha256.set(nativeArtifact.sha256)
    output.set(libsDir.map { it.file(nativeArtifact.fileName) })
}

dependencies {
    implementation(project(":core"))
    implementation(files(downloadSherpaJar.flatMap { it.output }))
    runtimeOnly(files(downloadSherpaNative.flatMap { it.output }))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.github.houbb:opencc4j:1.14.0")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("tw.captions.desktop.BenchmarkKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
