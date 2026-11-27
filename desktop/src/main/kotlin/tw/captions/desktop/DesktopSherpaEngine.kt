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
    val pipeline: PipelineConfig = PipelineConfig(),
    val numThreads: Int = 2,
    /** Drop background lines (focus filter) instead of showing them dimmed. */
    val hideBackground: Boolean = false,
    val hotwords: File? = null,
    val beamSearch: Boolean = false,
    val denoiser: DenoiserModel? = null,
) {
    val label: String
        get() = buildString {
            append(asrModel.name)
            denoiser?.let { append(" + ${it.label} denoiser") }
            append(if (useVad) " + VAD ${vadThreshold}" else " (no VAD)")
            speakerModel?.let { append(" + ${it.name} @${speakerThreshold}") }
            pipeline.vadEndpointSilenceSec?.takeIf { useVad }?.let { append(", VAD end ${it}s") }
            append(", ASR end ${asrTrailingSilenceSec}s")
        }
}

/** Loaded sherpa-onnx models wired into a [StreamingPipeline]. Reusable across audio streams. */
class SherpaStack(val setup: EngineSetup) : AutoCloseable {
    private val asr = SherpaAsr(setup.asrModel, setup.modelsDir, setup.numThreads, setup.asrTrailingSilenceSec, setup.hotwords, setup.beamSearch)
    private val vad = if (setup.useVad) SherpaVad(setup.modelsDir, setup.vadMinSilenceSec, setup.vadThreshold) else null
    private val embedder = setup.speakerModel?.let { SherpaEmbedder(it, setup.modelsDir) }
    private val denoiser = setup.denoiser?.let { SherpaDenoiser(it, setup.modelsDir) }

    /** A fresh pipeline (new speaker profiles) sharing the loaded models. */
    fun newPipeline(): StreamingPipeline =
        StreamingPipeline(asr, vad, embedder, SpeakerClusterer(setup.speakerThreshold), setup.pipeline, denoiser).also { it.reset() }

    override fun close() {
        asr.close()
        vad?.close()
        embedder?.close()
        denoiser?.close()
    }
}

/**
 * Offline sherpa-onnx [SpeechEngine] for the desktop. The audio comes from a wav file, played in
 * real time in 100 ms chunks as if it were a microphone.
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
        val chunk = 1600
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            pipeline.feed(audio.copyOfRange(i, i + n)).forEach { emit(it) }
            i += n
            if (realTime) delay(100)
        }
        pipeline.flush().forEach { emit(it) }
    }.flowOn(Dispatchers.Default)
}
