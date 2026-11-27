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
 * Offline models are downloaded on demand into app storage instead of being bundled in the APK.
 * They are grouped in [Pack]s: each offline engine needs some packs, and shared packs (VAD,
 * speaker model, denoiser) are downloaded once. Every pack can be removed again. Every download
 * is checked against its SHA-256.
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
        /** GTCRN streaming speech enhancement (0.5 MB). */
        val DENOISER = Remote(
            "$RELEASES/speech-enhancement-models/gtcrn_simple.onnx",
            "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534",
            535_638,
            localName = "denoiser.onnx",
        )
        /** X-ASR 480 ms chunks: same accuracy, less compute, first words ~0.1 s later. */
        val ASR_480 = Remote(
            "$RELEASES/asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            "fa5f63d618e5a01526e275a358bb7772e403f84808a4769fba52cffd8160bf74",
            133_895_136,
            extract = mapOf(
                "encoder.int8.onnx" to "asr480-encoder.onnx",
                "decoder.onnx" to "asr480-decoder.onnx",
                "joiner.int8.onnx" to "asr480-joiner.onnx",
                "tokens.txt" to "asr480-tokens.txt",
            ),
        )

        val XASR_160 = Pack("xasr-160", "語音辨識 X-ASR 160 ms", listOf(ASR))
        val XASR_480 = Pack("xasr-480", "語音辨識 X-ASR 480 ms", listOf(ASR_480))
        val SHARED = Pack("shared", "人聲偵測、說話者、降噪", listOf(VAD, SPEAKER, DENOISER))
        val PACKS = listOf(XASR_160, XASR_480, SHARED)
        private const val TAG = "CaptionsModels"
    }

    /** A group of files downloaded and removed together. */
    data class Pack(val id: String, val title: String, val files: List<Remote>) {
        val downloadBytes: Long get() = files.sumOf { it.bytes }
    }

    fun file(name: String) = File(dir, name)

    private fun done(r: Remote) = File(dir, "${r.sha256}.ok")

    fun isInstalled(pack: Pack): Boolean = pack.files.all { done(it).exists() }

    fun isInstalled(packs: List<Pack>): Boolean = packs.all { isInstalled(it) }

    /** Bytes the pack's files use on disk now. */
    fun sizeOnDisk(pack: Pack): Long = pack.files.flatMap { localFiles(it) }.sumOf { if (it.exists()) it.length() else 0L }

    private fun localFiles(r: Remote): List<File> =
        if (r.extract.isEmpty()) listOf(File(dir, r.localName)) else r.extract.values.map { File(dir, it) }

    /** Removes a pack's files. Callers make sure no installed engine still needs it. */
    fun delete(pack: Pack) {
        for (r in pack.files) {
            localFiles(r).forEach { it.delete() }
            done(r).delete()
            File(dir, r.localName + ".part").delete()
        }
        Log.i(TAG, "deleted ${pack.id}")
    }

    /** Downloads whatever is missing from [packs]. [onProgress] gets 0..1 over the missing files. */
    suspend fun ensure(packs: List<Pack>, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val missing = packs.flatMap { it.files }.distinct().filter { !done(it).exists() }
        val total = missing.sumOf { it.bytes }.coerceAtLeast(1).toFloat()
        var finished = 0L
        for (r in missing) {
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
