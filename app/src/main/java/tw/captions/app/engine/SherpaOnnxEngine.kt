package tw.captions.app.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import tw.captions.core.AudioEnhancer
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeakerClusterer
import tw.captions.core.SpeakerEmbedder
import tw.captions.core.SpeechEngine
import tw.captions.core.StreamingAsr
import tw.captions.core.StreamingPipeline
import tw.captions.core.TranscriptEvent
import tw.captions.core.VoiceActivityDetector

/**
 * Fully offline engine built on sherpa-onnx: a streaming zh/en zipformer for text, Silero VAD for
 * quick line endings, and CAM++ speaker embeddings with online clustering for speaker tags.
 * Models come from [ModelStore] (downloaded on first use). Settings match the desktop benchmark
 * (docs/benchmark.md).
 */
class SherpaOnnxEngine(
    private val models: ModelStore,
    private val openInput: () -> AudioInput,
    /** Which X-ASR model: 160 ms chunks (fastest words, default) or 480 ms (less compute). */
    val variant: Variant = Variant.X160,
    private val speakerThreshold: Float = 0.5f,
    /** Run the GTCRN speech-enhancement model before recognition (see docs/benchmark-noise.md). */
    private val useDenoiser: Boolean = DENOISER_DEFAULT,
) : SpeechEngine {
    override val id = variant.id
    override val displayName = variant.displayName
    override val requiresNetwork = false

    /** Model packs this engine needs on disk. */
    val packs: List<ModelStore.Pack> get() = variant.packs

    enum class Variant(val id: String, val displayName: String, val filePrefix: String, val packs: List<ModelStore.Pack>) {
        X160(ID, "離線＋說話者", "asr", listOf(ModelStore.XASR_160, ModelStore.SHARED)),
        X480("sherpa-onnx-480", "離線＋說話者（省電）", "asr480", listOf(ModelStore.XASR_480, ModelStore.SHARED)),
    }

    companion object {
        const val ID = "sherpa-onnx"

        /** Chosen from the Taiwan-accent noise benchmark (docs/benchmark-noise.md). */
        const val DENOISER_DEFAULT = false
        private const val TAG = "CaptionsSherpa"
    }

    override fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent> = flow {
        if (!models.isInstalled(packs)) {
            emit(TranscriptEvent.Error("離線模型還沒下載完成", recoverable = false))
            return@flow
        }
        val asr = Asr(models, variant.filePrefix)
        val vad = SileroVad(models)
        val embedder = Embedder(models)
        val denoiser = if (useDenoiser) Denoiser(models) else null
        val input = try {
            openInput()
        } catch (e: Exception) {
            Log.e(TAG, "audio input failed", e)
            asr.close(); vad.close(); embedder.close(); denoiser?.close()
            emit(TranscriptEvent.Error("無法開啟麥克風", recoverable = false))
            return@flow
        }
        try {
            val pipeline = StreamingPipeline(asr, vad, embedder, SpeakerClusterer(speakerThreshold), enhancer = denoiser)
            val chunk = FloatArray(SAMPLE_RATE / 10) // 100 ms
            while (currentCoroutineContext().isActive) {
                val n = input.read(chunk)
                if (n < 0) break
                val events = pipeline.feed(if (n == chunk.size) chunk else chunk.copyOf(n))
                for (e in events) {
                    if (e is TranscriptEvent.Final) Log.i(TAG, "final [${e.speaker?.label}] ${e.text}")
                    emit(e)
                }
            }
            pipeline.flush().forEach { emit(it) }
        } finally {
            input.close()
            asr.close()
            vad.close()
            embedder.close()
            denoiser?.close()
        }
    }.flowOn(Dispatchers.Default)

    private class Asr(models: ModelStore, prefix: String) : StreamingAsr, AutoCloseable {
        private val recognizer = OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = models.file("$prefix-encoder.onnx").path,
                        decoder = models.file("$prefix-decoder.onnx").path,
                        joiner = models.file("$prefix-joiner.onnx").path,
                    ),
                    tokens = models.file("$prefix-tokens.txt").path,
                    numThreads = 2,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 0.8f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
        private val stream = recognizer.createStream("")

        override fun accept(samples: FloatArray) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        override fun text(): String = recognizer.getResult(stream).text
        override fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)
        override fun reset() = recognizer.reset(stream)

        override fun close() {
            stream.release()
            recognizer.release()
        }
    }

    /** Frame-level Silero speech probability; the pipeline does its own smoothing. */
    private class SileroVad(models: ModelStore, private val threshold: Float = 0.65f) : VoiceActivityDetector, AutoCloseable {
        private val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.file("silero_vad.onnx").path,
                    threshold = threshold,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        private var pending = FloatArray(0)
        private var speech = false

        override fun accept(samples: FloatArray) {
            pending += samples
            speech = false
            var off = 0
            while (pending.size - off >= 512) {
                if (vad.compute(pending.copyOfRange(off, off + 512)) >= threshold) speech = true
                off += 512
            }
            pending = pending.copyOfRange(off, pending.size)
        }

        override fun isSpeech() = speech
        override fun reset() {
            vad.reset()
            pending = FloatArray(0)
            speech = false
        }

        override fun close() = vad.release()
    }

    private class Denoiser(models: ModelStore) : AudioEnhancer, AutoCloseable {
        private val denoiser = OnlineSpeechDenoiser(
            assetManager = null,
            config = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = models.file("denoiser.onnx").path),
                    numThreads = 1,
                ),
            ),
        )

        override fun process(samples: FloatArray): FloatArray = denoiser.run(samples, SAMPLE_RATE).samples
        override fun reset() = denoiser.reset()
        override fun close() = denoiser.release()
    }

    private class Embedder(models: ModelStore) : SpeakerEmbedder, AutoCloseable {
        private val extractor = SpeakerEmbeddingExtractor(
            assetManager = null,
            config = SpeakerEmbeddingExtractorConfig(model = models.file("speaker.onnx").path, numThreads = 1),
        )

        override fun embed(samples: FloatArray): FloatArray {
            val s = extractor.createStream()
            try {
                s.acceptWaveform(samples, SAMPLE_RATE)
                s.inputFinished()
                return extractor.compute(s)
            } finally {
                s.release()
            }
        }

        override fun close() = extractor.release()
    }
}
