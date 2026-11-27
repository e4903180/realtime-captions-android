import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads [url] to [output] once and checks its SHA-256. Used for prebuilt sherpa-onnx
 * libraries, which are published on GitHub releases rather than Maven Central.
 * A copy in ~/.cache/realtime-captions (or $CAPTIONS_CACHE) is reused across clean builds.
 */
abstract class DownloadFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val out = output.get().asFile
        val cacheDir = File(System.getenv("CAPTIONS_CACHE") ?: "${System.getProperty("user.home")}/.cache/realtime-captions")
        val cached = File(cacheDir, out.name)
        if (!(cached.isFile && sha256Of(cached) == sha256.get())) {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, out.name + ".part")
            var lastError: Exception? = null
            for (attempt in 1..4) {
                try {
                    logger.lifecycle("Downloading ${url.get()} (attempt $attempt)")
                    URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(2000L * attempt)
                }
            }
            lastError?.let { throw GradleException("Download failed: ${url.get()}", it) }
            val actual = sha256Of(tmp)
            if (actual != sha256.get()) {
                tmp.delete()
                throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
            }
            if (cached.exists()) cached.delete()
            tmp.renameTo(cached)
        }
        out.parentFile.mkdirs()
        cached.copyTo(out, overwrite = true)
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Prebuilt sherpa-onnx artifacts (https://github.com/k2-fsa/sherpa-onnx/releases). */
object Sherpa {
    const val VERSION = "2.0.3"
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION"

    data class Artifact(val fileName: String, val sha256: String) {
        val url get() = "$BASE/$fileName"
    }

    val jvmJar = Artifact("sherpa-onnx-jvm-$VERSION.jar", "ad69b6a54462ba3671782617b11d35975c8d6b7c84ae11765e3a06dd0c56c0e5")
    val androidAar = Artifact("sherpa-onnx-$VERSION.aar", "4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50")
    val nativeWinX64 = Artifact("sherpa-onnx-native-lib-win-x64-$VERSION.jar", "26a9072f4a47be3a73bfbbba5f25e6e8b84b24734be651de2d480ae753ae6508")
    val nativeLinuxX64 = Artifact("sherpa-onnx-native-lib-linux-x64-$VERSION.jar", "42330fbcfe4f771386545397ebf8a8dd2176ff913a254331226c01ad87982e17")
}
