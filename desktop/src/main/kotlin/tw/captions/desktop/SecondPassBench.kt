package tw.captions.desktop

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import tw.captions.core.PipelineConfig
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * A whole-sentence (non-streaming) model from the sherpa-onnx asr-models release, used for the
 * second pass (ADR-025). [dirPrefix] matches the unpacked folder under the models directory, so
 * int8 or dated variants are found too.
 */
data class OfflineAsrModel(val name: String, val kind: String, val dirPrefix: String, val model: String = "model.int8.onnx") {
    companion object {
        val SENSE_VOICE_2024 = OfflineAsrModel("SenseVoice small int8 (2024-07-17)", "sense_voice", "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17")
        val SENSE_VOICE_2025 = OfflineAsrModel("SenseVoice small int8 (2025-09-09)", "sense_voice", "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        val PARAFORMER_SMALL = OfflineAsrModel("Paraformer zh small int8 (2024-03-09)", "paraformer", "sherpa-onnx-paraformer-zh-small-2024-03-09")
        val PARAFORMER = OfflineAsrModel("Paraformer zh int8 (2024-03-09)", "paraformer", "sherpa-onnx-paraformer-zh-2024-03-09")
        val ALL = listOf(SENSE_VOICE_2024, SENSE_VOICE_2025, PARAFORMER_SMALL, PARAFORMER)
    }

    fun dir(modelsDir: File): File? = modelsDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(dirPrefix) }.minByOrNull { it.name.length }
}

class SherpaOfflineAsr(val model: OfflineAsrModel, modelsDir: File, numThreads: Int = 2) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val dir = requireNotNull(model.dir(modelsDir)) { "missing ${model.dirPrefix}* in $modelsDir" }
        fun path(name: String) = File(dir, name).also { require(it.isFile) { "missing model file $it" } }.path
        val m = OfflineModelConfig.builder().setTokens(path("tokens.txt")).setNumThreads(numThreads)
        when (model.kind) {
            "sense_voice" -> m.setSenseVoice(OfflineSenseVoiceModelConfig.builder().setModel(path(model.model)).setLanguage("zh").setInverseTextNormalization(true).build())
            "paraformer" -> m.setParaformer(OfflineParaformerModelConfig.builder().setModel(path(model.model)).build())
            else -> error("unknown kind ${model.kind}")
        }
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                .setOfflineModelConfig(m.build())
                .setDecodingMethod("greedy_search")
                .build(),
        )
    }

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/** Time spent on the second pass, per finished utterance. */
class SecondPassStats {
    val seconds = mutableListOf<Double>()
    val audioSeconds = mutableListOf<Double>()
    var replaced = 0
    var kept = 0
}

/**
 * `--second-pass`: live captions alone vs live captions corrected by each whole-sentence model, on
 * the Taiwan-accent sets. Uses the iPhone settings (line end after 0.6 s, no volume filter).
 */
fun secondPassBenchmark(modelsDir: File, dir: File, out: File, quick: Boolean) {
    val files = loadTruths(dir).filter { it.language == "zh-TW" }.let { fs -> if (quick) fs.groupBy { it.dialog.substringBefore('_') }.values.flatMap { it.take(3) } else fs }
    require(files.isNotEmpty()) { "no zh-TW files in $dir" }
    val setup = EngineSetup(modelsDir, pipeline = PipelineConfig(vadEndpointSilenceSec = 0.6f, focusMarginDb = null, keepUtteranceAudioSec = 30f), numThreads = 4)
    val models = OfflineAsrModel.ALL.filter { it.dir(modelsDir) != null }
    System.err.println("second-pass models found: ${models.map { it.name }}")
    val rows = mutableListOf<Triple<String, List<FileResult>, SecondPassStats?>>()
    SherpaStack(setup).use { stack ->
        System.err.println("== live only")
        rows += Triple("Live only (X-ASR 160 ms)", files.map { runFile(stack, it, dir, false) }, null)
        for (m in models) {
            System.err.println("== + ${m.name}")
            SherpaOfflineAsr(m, modelsDir, numThreads = 2).use { asr ->
                val stats = SecondPassStats()
                rows += Triple("Live + ${m.name}", files.map { runFile(stack, it, dir, false, secondPass = asr, secondPassStats = stats) }, stats)
            }
        }
    }
    fun pct(x: Double) = String.format(Locale.ROOT, "%.1f%%", 100 * x)
    fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
    fun cer(rs: List<FileResult>) = rs.sumOf { it.errors }.toDouble() / rs.sumOf { it.refTokens }.coerceAtLeast(1)
    fun cerNf(rs: List<FileResult>) = rs.sumOf { it.errorsNoFiller }.toDouble() / rs.sumOf { it.refNoFiller }.coerceAtLeast(1)
    val names = mapOf("cvtw" to "Common Voice zh-TW (read)", "yttw" to "YouTube talks (real-life)", "ml2021" to "NTU ML lectures (real-life)")
    out.parentFile?.mkdirs()
    out.writeText(buildString {
        appendLine("# Second pass: correcting finished lines with a whole-sentence model")
        appendLine()
        appendLine("Generated by `:desktop --second-pass` on ${LocalDate.now()} (${System.getProperty("os.name")}, ${Runtime.getRuntime().availableProcessors()} logical CPUs). " +
            "Live captions use the iPhone settings (X-ASR 160 ms, line end after 0.6 s silence). After each line ends, the whole-sentence model hears that line's audio " +
            "again and its text replaces the live text (unless it is much shorter or longer; see `SecondPass.choose`). See ADR-025.")
        appendLine()
        appendLine("| Setup | Dataset | Files | CER | CER (no fillers) | Lines replaced | Second pass per line p50 / p90 (s) | Second-pass RTF |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for ((label, rs, st) in rows) for ((ds, g) in rs.groupBy { it.truth.dialog.substringBefore('_') }) {
            val timing = st?.let { s ->
                val sorted = s.seconds.sorted()
                fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
                "${s.replaced} / ${s.replaced + s.kept} | ${f2(p(0.5))} / ${f2(p(0.9))} | ${f2(s.seconds.sum() / s.audioSeconds.sum().coerceAtLeast(1e-9))}"
            } ?: "– | – | –"
            appendLine("| $label | ${names[ds] ?: ds} | ${g.size} | ${pct(cer(g))} | ${pct(cerNf(g))} | $timing |")
        }
        appendLine()
        appendLine("Timing is on this computer with 2 threads; a phone is roughly 2–4 times slower. Lines replaced and timing are over all datasets.")
    })
    System.err.println("wrote $out")
}
