package tw.captions.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import tw.captions.core.PipelineConfig
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeakerClusterer
import tw.captions.core.SpeechEngine
import tw.captions.core.StreamingPipeline
import tw.captions.core.TranscriptEvent
import java.io.File

/**
 * Defaults every benchmark builds [EngineSetup] with. They are the shipped Android app's settings
 * (app/.../engine/SherpaOnnxEngine.kt and CaptionViewModel.kt): lines end after 0.6 s of silence,
 * the caption screen joins one person's utterances less than 1.5 s apart (ADR-024), audio comes in
 * 20 ms chunks and the recognizer uses 4 threads. `--legacy-config` gives the settings the reports
 * before 2026-11-28 used (0.4 s, no joining, 100 ms chunks, 2 threads); `--vad-end`, `--merge-gap`
 * (0 = off), `--chunk-ms` and `--threads` override single values.
 */
object BenchDefaults {
    const val APP_VAD_END_SEC = 0.6f
    const val APP_MERGE_GAP_SEC = 1.5
    const val APP_CHUNK_SAMPLES = 320
    const val APP_THREADS = 4

    var vadEndpointSilenceSec: Float = APP_VAD_END_SEC
    var mergeGapSec: Double? = APP_MERGE_GAP_SEC
    var chunkSamples: Int = APP_CHUNK_SAMPLES
    var numThreads: Int = APP_THREADS

    fun applyArgs(opts: List<String>) {
        fun opt(name: String) = opts.indexOf(name).takeIf { it >= 0 && it + 1 < opts.size }?.let { opts[it + 1] }
        if ("--legacy-config" in opts) {
            vadEndpointSilenceSec = 0.4f
            mergeGapSec = null
            chunkSamples = 1600
            numThreads = 2
        }
        opt("--vad-end")?.let { vadEndpointSilenceSec = it.toFloat() }
        opt("--merge-gap")?.let { mergeGapSec = it.toDouble().takeIf { g -> g > 0 } }
        opt("--chunk-ms")?.let { chunkSamples = it.toInt() * 16 }
        opt("--threads")?.let { numThreads = it.toInt() }
    }
}

/** Everything needed to build the offline engine on the desktop. */
data class EngineSetup(
    val modelsDir: File,
    val asrModel: AsrModel = AsrModel.XASR_160,
    val speakerModel: SpeakerModel? = SpeakerModel.CAMPPLUS_ZH_EN,
    val useVad: Boolean = true,
    val speakerThreshold: Float = 0.5f,
    val asrTrailingSilenceSec: Float = 0.8f,
    val vadMinSilenceSec: Float = 0.25f,
    val vadThreshold: Float = 0.65f,
    val pipeline: PipelineConfig = PipelineConfig(vadEndpointSilenceSec = BenchDefaults.vadEndpointSilenceSec),
    val numThreads: Int = BenchDefaults.numThreads,
    /** Drop background lines (focus filter) instead of showing them dimmed. */
    val hideBackground: Boolean = false,
    val hotwords: File? = null,
    val beamSearch: Boolean = false,
    val hotwordsScore: Float = 1.5f,
    val hotwordsUnit: String = "bpe",
    val hotwordsSimplified: Boolean = false,
    val hotwordsSpaced: Boolean = false,
    val denoiser: DenoiserModel? = null,
    /** The caption screen joins one person's utterances less than this many seconds apart (null = one line each). */
    val mergeGapSec: Double? = BenchDefaults.mergeGapSec,
    /** Audio samples handed to the engine at a time (320 = 20 ms, 1600 = 100 ms). */
    val chunkSamples: Int = BenchDefaults.chunkSamples,
) {
    /** Whether line end, line joining, chunk size and threads are the Android app's. */
    val matchesApp: Boolean
        get() = useVad && pipeline.vadEndpointSilenceSec == BenchDefaults.APP_VAD_END_SEC && mergeGapSec == BenchDefaults.APP_MERGE_GAP_SEC &&
            chunkSamples == BenchDefaults.APP_CHUNK_SAMPLES && numThreads == BenchDefaults.APP_THREADS

    /** Every setting that changes results, for the top of each report. */
    val configSummary: String
        get() = buildString {
            append("ASR ${asrModel.name}, $numThreads threads, ${chunkSamples * 1000 / 16000} ms audio chunks")
            append(if (useVad) ", VAD threshold $vadThreshold, line end after ${pipeline.vadEndpointSilenceSec?.let { "$it s" } ?: "(ASR endpoint only)"} of silence" else ", no VAD")
            append(", ASR endpoint $asrTrailingSilenceSec s")
            append(", line joining ${mergeGapSec?.let { "< $it s" } ?: "off"}")
            append(", speaker model ${speakerModel?.name ?: "none"}")
            speakerModel?.let { append(" @ $speakerThreshold (new speaker from ≥ ${pipeline.minNewSpeakerSec} s)") }
            append(", focus filter ${pipeline.focusMarginDb?.let { "$it dB" } ?: "off"}")
            append(", denoiser ${denoiser?.label ?: "off"}")
            if (hideBackground) append(", background lines hidden")
            if (hotwords != null) append(", hotwords ${hotwords.name}") else if (beamSearch) append(", beam search")
        }

    val label: String
        get() = buildString {
            append(asrModel.name)
            denoiser?.let { append(" + ${it.label} denoiser") }
            append(if (useVad) " + VAD ${vadThreshold}" else " (no VAD)")
            speakerModel?.let { append(" + ${it.name} @${speakerThreshold}") }
            pipeline.vadEndpointSilenceSec?.takeIf { useVad }?.let { append(", VAD end ${it}s") }
            append(", ASR end ${asrTrailingSilenceSec}s")
            if (!matchesApp) append(", ${chunkSamples * 1000 / 16000} ms chunks, $numThreads threads, joining ${mergeGapSec?.let { "${it}s" } ?: "off"}")
        }
}

/** Paragraph for the top of a generated report: the settings it was measured with. */
fun configLine(setup: EngineSetup): String =
    "**Config.** ${setup.configSummary}. " +
        if (setup.matchesApp) "Line end, line joining, chunk size and threads are the Android app's." else
            "**Differs from the Android app** (app: 0.6 s line end, line joining < 1.5 s, 20 ms chunks, 4 threads)."

/** Loaded sherpa-onnx models wired into a [StreamingPipeline]. Reusable across audio streams. */
class SherpaStack(val setup: EngineSetup) : AutoCloseable {
    private val asr = SherpaAsr(
        setup.asrModel, setup.modelsDir, setup.numThreads, setup.asrTrailingSilenceSec, setup.hotwords, setup.beamSearch,
        setup.hotwordsScore, setup.hotwordsUnit, setup.hotwordsSimplified, setup.hotwordsSpaced,
    )
    private val vad = if (setup.useVad) SherpaVad(setup.modelsDir, setup.vadMinSilenceSec, setup.vadThreshold) else null
    private val embedder = setup.speakerModel?.let { SherpaEmbedder(it, setup.modelsDir) }
    private val denoiser = setup.denoiser?.let { SherpaDenoiser(it, setup.modelsDir) }

    /** A fresh pipeline (new speaker profiles) sharing the loaded models. */
    fun newPipeline(
        registry: tw.captions.core.VoiceRegistry? = null,
        clusterer: SpeakerClusterer = SpeakerClusterer(setup.speakerThreshold),
    ): StreamingPipeline =
        StreamingPipeline(asr, vad, embedder, clusterer, setup.pipeline, denoiser, registry).also { it.reset() }

    /** Speaker embedding of some audio (for registering voices in tests). */
    fun embed(samples: FloatArray): FloatArray = requireNotNull(embedder) { "no speaker model" }.embed(samples)

    override fun close() {
        asr.close()
        vad?.close()
        embedder?.close()
        denoiser?.close()
    }
}

/**
 * Offline sherpa-onnx [SpeechEngine] for the desktop. The audio comes from a wav file, played in
 * real time in [EngineSetup.chunkSamples] chunks as if it were a microphone.
 */
class DesktopSherpaEngine(
    private val stack: SherpaStack,
    private val wav: File,
    private val realTime: Boolean = true,
) : SpeechEngine {
    override val id = "sherpa-onnx"
    override val displayName = "離線（sherpa-onnx）"
    override val requiresNetwork = false

    override fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent> = flow {
        val pipeline = stack.newPipeline()
        val audio = readWav16k(wav)
        val chunk = stack.setup.chunkSamples
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            pipeline.feed(audio.copyOfRange(i, i + n)).forEach { emit(it) }
            i += n
            if (realTime) delay(chunk * 1000L / 16000)
        }
        pipeline.flush().forEach { emit(it) }
    }.flowOn(Dispatchers.Default)
}
