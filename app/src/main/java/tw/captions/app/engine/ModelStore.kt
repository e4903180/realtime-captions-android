package tw.captions.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Offline models are downloaded on first use into app storage instead of being bundled in the APK
 * (about 165 MB in total). Every download is checked against its SHA-256.
 */
class ModelStore(context: Context) {
    val dir = File(context.filesDir, "models")

    /** A model file, or a .tar.bz2 archive from which only [extract] entries are kept. */
    data class Remote(
        val url: String,
        val sha256: String,
        val bytes: Long,
        /** archive entry file name → local file name; empty for a plain file saved as [localName]. */
        val extract: Map<String, String> = emptyMap(),
        val localName: String = url.substringAfterLast('/'),
    )

    companion object {
        private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

        /** X-ASR streaming zipformer, zh + en with punctuation, 160 ms chunks, int8. */
        val ASR = Remote(
            "$RELEASES/asr-models/sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399",
            133_898_007,
            extract = mapOf(
                "encoder.int8.onnx" to "asr-encoder.onnx",
                "decoder.onnx" to "asr-decoder.onnx",
                "joiner.int8.onnx" to "asr-joiner.onnx",
                "tokens.txt" to "asr-tokens.txt",
            ),
        )
        val VAD = Remote(
            "$RELEASES/asr-models/silero_vad.onnx",
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            643_854,
        )
        val SPEAKER = Remote(
            "$RELEASES/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            "53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b",
            28_281_165,
            localName = "speaker.onnx",
        )
        val ALL = listOf(ASR, VAD, SPEAKER)
        private const val TAG = "CaptionsModels"
    }

    fun file(name: String) = File(dir, name)

    private fun done(r: Remote) = File(dir, "${r.sha256}.ok")

    val isReady: Boolean get() = ALL.all { done(it).exists() }

    /** Downloads whatever is missing. [onProgress] gets 0..1 over all files. */
    suspend fun ensure(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val total = ALL.sumOf { it.bytes }.toFloat()
        var finished = 0L
        for (r in ALL) {
            if (!done(r).exists()) {
                Log.i(TAG, "downloading ${r.url}")
                fetch(r) { bytes -> onProgress((finished + bytes) / total) }
                done(r).writeText(r.url)
            }
            finished += r.bytes
            onProgress(finished / total)
        }
    }

    private suspend fun fetch(r: Remote, onBytes: (Long) -> Unit) {
        val tmp = File(dir, r.localName + ".part")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                download(r.url, tmp, onBytes)
                lastError = null
                break
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                Log.w(TAG, "download attempt $attempt failed", e)
                lastError = e
            }
        }
        lastError?.let { throw it }
        val digest = MessageDigest.getInstance("SHA-256")
        val sha = DigestInputStream(tmp.inputStream().buffered(), digest).use { s ->
            if (r.extract.isEmpty()) {
                s.copyTo(OutputSink)
            } else {
                untar(s, r.extract)
                s.copyTo(OutputSink) // hash any trailing bytes
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (sha != r.sha256) {
            tmp.delete()
            r.extract.values.forEach { File(dir, it).delete() }
            error("checksum mismatch for ${r.url}")
        }
        if (r.extract.isEmpty()) {
            val target = File(dir, r.localName)
            target.delete()
            check(tmp.renameTo(target)) { "rename failed: $target" }
        } else {
            tmp.delete()
        }
    }

    private suspend fun download(url: String, to: File, onBytes: (Long) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub release links redirect to another host; follow them by hand to be safe.
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
        }
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
        conn.inputStream.use { input ->
            to.outputStream().buffered().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        }
    }

    private fun untar(input: InputStream, extract: Map<String, String>) {
        val tar = TarArchiveInputStream(BZip2CompressorInputStream(NonClosing(input)))
        var found = 0
        while (true) {
            val entry = tar.nextEntry ?: break
            val name = entry.name.substringAfterLast('/')
            val local = extract[name] ?: continue
            if (entry.isDirectory || entry.name.contains("/test_wavs/")) continue
            File(dir, local).outputStream().buffered().use { tar.copyTo(it) }
            found++
        }
        check(found == extract.size) { "archive is missing files: found $found of ${extract.size}" }
    }

    private class NonClosing(s: InputStream) : FilterInputStream(s) {
        override fun close() {}
    }

    private object OutputSink : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
