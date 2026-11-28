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
    /** People the user taught the app to recognise (認聲音). */
    private val registry: tw.captions.core.VoiceRegistry? = null,
    /** 說話者辨識 on/off (ADR-033): off skips the speaker model, so no labels and less battery. */
    private val speakersOn: () -> Boolean = { true },
    /**
     * Live speaker groups. Shared with the caption screen, which corrects them after it regroups
     * finished lines (ADR-027), and kept between stop and start so letters stay the same.
     */
    private val clusterer: SpeakerClusterer = SpeakerClusterer(speakerThreshold),
    /** Loudness-based background filter (ADR-020): only while the user chose 淡化／隱藏. */
    private val focusOn: () -> Boolean = { false },
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
        val asr = Asr(models, variant.filePrefix, config.hotwords)
        val vad = SileroVad(models)
        val withSpeakers = speakersOn()
        val embedder = if (withSpeakers) Embedder(models) else null
        val denoiser = if (useDenoiser) Denoiser(models) else null
        val input = try {
            // Read on its own thread so a slow moment in recognition does not lose microphone audio.
            BufferedAudioInput(openInput())
        } catch (e: Exception) {
            Log.e(TAG, "audio input failed", e)
            asr.close(); vad.close(); embedder?.close(); denoiser?.close()
            emit(TranscriptEvent.Error("無法開啟麥克風", recoverable = false))
            return@flow
        }
        try {
            // Lines end after 0.6 s of silence (not 0.4) so one person's sentence is not chopped up;
            // the assembler also joins a person's utterances less than 1.5 s apart (ADR-024).
            val pipeline = StreamingPipeline(
                asr, vad, embedder, clusterer,
                config = tw.captions.core.PipelineConfig(
                    vadEndpointSilenceSec = 0.6f,
                    focusMarginDb = if (focusOn()) 8f else null,
                    // Voices nobody named count as background only when the user turned the filter on (like iPhone).
                    unknownIsBackground = focusOn(),
                ),
                enhancer = denoiser,
                registry = if (withSpeakers) registry else null,
            )
            // 20 ms chunks: the first words appear ~0.04 s sooner than with 100 ms (docs/latency.md).
            val chunk = FloatArray(SAMPLE_RATE / 50)
            while (currentCoroutineContext().isActive) {
                val n = input.read(chunk)
                if (n < 0) break
                val events = pipeline.feed(if (n == chunk.size) chunk else chunk.copyOf(n))
                for (e in events) {
                    // Never write what people said to the system log in release builds.
                    if (e is TranscriptEvent.Final && tw.captions.app.BuildConfig.DEBUG) Log.i(TAG, "final [${e.speaker?.label}] ${e.text}")
                    emit(e)
                }
            }
            pipeline.flush().forEach { emit(it) }
        } finally {
            input.close()
            asr.close()
            vad.close()
            embedder?.close()
            denoiser?.close()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * [hotwords]: the user's 自訂詞彙. With any, decoding switches to modified beam search with the
     * words boosted (docs/benchmark-hotwords.md, ADR-038): names right 43% → 74%, no false insertions.
     */
    private class Asr(models: ModelStore, prefix: String, hotwords: List<String> = emptyList()) : StreamingAsr, AutoCloseable {
        private val boost = Hotwords.prepare(models, prefix, hotwords)
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
                    // 4 threads: phones are 3-5x slower than the benchmark PC, so compute matters more there.
                    numThreads = 4,
                    modelingUnit = if (boost != null) "bpe" else "",
                    bpeVocab = boost?.vocab?.path ?: "",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 0.8f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = if (boost != null) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = boost?.file?.path ?: "",
                hotwordsScore = Hotwords.SCORE,
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
            // With chunks shorter than one 512-sample window, keep the last decision until a window is complete.
            var off = 0
            var voiced = false
            while (pending.size - off >= 512) {
                if (vad.compute(pending.copyOfRange(off, off + 512)) >= threshold) voiced = true
                off += 512
            }
            if (off > 0) speech = voiced
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
