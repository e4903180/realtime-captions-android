package tw.captions.desktop

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import tw.captions.core.SpeakerEmbedder
import tw.captions.core.StreamingAsr
import tw.captions.core.VoiceActivityDetector
import java.io.File

/** A streaming transducer model unpacked from a sherpa-onnx release archive. */
data class AsrModel(
    val name: String,
    val dir: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String = "tokens.txt",
) {
    companion object {
        /** X-ASR zh-en with punctuation, 160 ms chunks (lowest latency). */
        val XASR_160 = AsrModel(
            "x-asr-160ms-int8",
            "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
            "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
        )
        val XASR_480 = XASR_160.copy(
            name = "x-asr-480ms-int8",
            dir = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
        )
        /** The classic k2 bilingual zh-en streaming zipformer (no punctuation). */
        val BILINGUAL_2023 = AsrModel(
            "zipformer-bilingual-2023-int8",
            "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx",
        )
        val ALL = listOf(XASR_160, XASR_480, BILINGUAL_2023)
    }
}

data class SpeakerModel(val name: String, val file: String) {
    companion object {
        val CAMPPLUS_ZH_EN = SpeakerModel("campplus-zh-en", "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")
        val ERES2NET_BASE = SpeakerModel("eres2net-base-zh", "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx")
        val ALL = listOf(CAMPPLUS_ZH_EN, ERES2NET_BASE)
    }
}

const val SILERO_VAD = "silero_vad.onnx"

class SherpaAsr(
    model: AsrModel,
    modelsDir: File,
    numThreads: Int = 2,
    trailingSilenceSec: Float = 0.8f,
    /** Words to favour (one per line); switches to modified beam search. Needs bpe.vocab next to the model. */
    hotwords: File? = null,
    beam: Boolean = false,
    /** Bonus per hotword token (sherpa-onnx default 1.5). */
    hotwordsScore: Float = 1.5f,
    /** How hotwords are split into model tokens: "bpe" or "cjkchar+bpe". */
    hotwordsUnit: String = "bpe",
    /** The model writes Simplified Chinese: convert Traditional hotwords before handing them over. */
    hotwordsSimplified: Boolean = false,
    /** Space out Chinese characters so each maps to its own BPE token (see [spaceCjk]). */
    hotwordsSpaced: Boolean = false,
) : StreamingAsr, AutoCloseable {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    init {
        val dir = File(modelsDir, model.dir)
        fun path(name: String) = File(dir, name).also { require(it.isFile) { "missing model file $it" } }.path
        val config = OnlineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
            .setOnlineModelConfig(
                OnlineModelConfig.builder()
                    .setTransducer(
                        OnlineTransducerModelConfig.builder()
                            .setEncoder(path(model.encoder))
                            .setDecoder(path(model.decoder))
                            .setJoiner(path(model.joiner))
                            .build(),
                    )
                    .setTokens(path(model.tokens))
                    .setNumThreads(numThreads)
                    .apply { if (hotwords != null) setModelingUnit(hotwordsUnit).setBpeVocab(bpeVocabFile(dir).path) }
                    .build(),
            )
            .setEnableEndpoint(true)
            .setEndpointConfig(
                EndpointConfig.builder()
                    .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(2.4f).setMinUtteranceLength(0f).build())
                    .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(trailingSilenceSec).setMinUtteranceLength(0f).build())
                    .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(0f).setMinUtteranceLength(20f).build())
                    .build(),
            )
            .setDecodingMethod(if (hotwords != null || beam) "modified_beam_search" else "greedy_search")
            .setMaxActivePaths(4)
            .apply { if (hotwords != null) setHotwordsFile(hotwordsFile(hotwords, hotwordsSimplified, hotwordsSpaced).path).setHotwordsScore(hotwordsScore) }
            .build()
        recognizer = OnlineRecognizer(config)
        stream = recognizer.createStream()
    }

    override fun accept(samples: FloatArray) {
        stream.acceptWaveform(samples, 16000)
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

class SherpaVad(modelsDir: File, minSilenceSec: Float = 0.25f, private val threshold: Float = 0.5f) : VoiceActivityDetector, AutoCloseable {
    private val vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(File(modelsDir, SILERO_VAD).path)
                    .setThreshold(threshold)
                    .setMinSilenceDuration(minSilenceSec)
                    .setMinSpeechDuration(0.25f)
                    .setWindowSize(512)
                    .setMaxSpeechDuration(20f)
                    .build(),
            )
            .setSampleRate(16000)
            .setNumThreads(1)
            .build(),
    )
    private val pending = FloatArrayBuilder()
    private var speech = false

    override fun accept(samples: FloatArray) {
        // Frame-level Silero probabilities (512-sample windows), without the VAD's own
        // min-speech / min-silence smoothing: the pipeline does its own hangover, and reacting
        // to speech onsets right away keeps it from ending an utterance as the next one starts.
        pending.add(samples)
        // With chunks shorter than one 512-sample window, keep the last decision until a window is complete.
        var any = false
        var voiced = false
        while (pending.size >= 512) {
            any = true
            if (vad.compute(pending.take(512)) >= threshold) voiced = true
        }
        if (any) speech = voiced
    }

    override fun isSpeech(): Boolean = speech

    override fun reset() {
        vad.reset()
        pending.clear()
        speech = false
    }

    override fun close() = vad.release()
}

class SherpaEmbedder(model: SpeakerModel, modelsDir: File, numThreads: Int = 1) : SpeakerEmbedder, AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        SpeakerEmbeddingExtractorConfig.builder()
            .setModel(File(modelsDir, model.file).path)
            .setNumThreads(numThreads)
            .build(),
    )

    override fun embed(samples: FloatArray): FloatArray {
        val s = extractor.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            s.inputFinished()
            return extractor.compute(s)
        } finally {
            s.release()
        }
    }

    override fun close() = extractor.release()
}

/** Growable float queue (front removal), avoids boxing. */
internal class FloatArrayBuilder {
    private var data = FloatArray(4096)
    private var start = 0
    private var end = 0
    val size get() = end - start

    fun add(x: FloatArray) {
        if (end + x.size > data.size) {
            val n = FloatArray(maxOf(data.size * 2, size + x.size))
            System.arraycopy(data, start, n, 0, size)
            end = size
            start = 0
            data = n
        }
        System.arraycopy(x, 0, data, end, x.size)
        end += x.size
    }

    fun take(n: Int): FloatArray {
        val out = data.copyOfRange(start, start + n)
        start += n
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    fun clear() {
        start = 0
        end = 0
    }
}

/** Streaming speech-enhancement models from sherpa-onnx's speech-enhancement-models release. */
enum class DenoiserModel(val label: String, val file: String) {
    GTCRN("gtcrn", "gtcrn_simple.onnx"),
    DPDFNET2("dpdfnet2", "dpdfnet2.onnx"),
}

class SherpaDenoiser(model: DenoiserModel, modelsDir: File, numThreads: Int = 1) : tw.captions.core.AudioEnhancer, AutoCloseable {
    private val denoiser: com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser

    init {
        val path = File(modelsDir, model.file).path
        val m = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig.builder().setNumThreads(numThreads)
        when (model) {
            DenoiserModel.GTCRN -> m.setGtcrn(com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig.builder().setModel(path).build())
            DenoiserModel.DPDFNET2 -> m.setDpdfnet(com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig.builder().setModel(path).build())
        }
        denoiser = com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser(
            com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig.builder().setModel(m.build()).build(),
        )
    }

    override fun process(samples: FloatArray): FloatArray = denoiser.run(samples, 16000).samples

    override fun reset() = denoiser.reset()

    override fun close() = denoiser.release()
}

/** [list] as sherpa-onnx reads it: one word per line, optionally converted to Simplified Chinese (what X-ASR writes). */
fun hotwordsFile(list: File, simplified: Boolean, spaced: Boolean = false): File {
    if (!simplified && !spaced) return list
    val words = list.readLines(Charsets.UTF_8).map { it.trim().removePrefix(BOM) }.filter { it.isNotEmpty() && !it.startsWith("#") }
    return File.createTempFile("hotwords", ".txt").also {
        it.deleteOnExit()
        it.writeText(
            words.joinToString("\n") { w ->
                val s = if (simplified) com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(w) else w
                if (spaced) spaceCjk(s) else s
            },
            Charsets.UTF_8,
        )
    }
}

private const val BOM = "\uFEFF"

/**
 * X-ASR's BPE has one token per Chinese character ("\u2581\u6797"), so "\u6797\u6021\u541B" would encode as "\u2581\u6797" + unknown:
 * put a space between Chinese characters ("\u6797 \u6021 \u541B") so every character gets its own token.
 */
fun spaceCjk(word: String): String = buildString {
    fun han(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    for ((i, c) in word.withIndex()) {
        if (i > 0 && c != ' ' && last() != ' ' && (han(c) || han(word[i - 1]))) append(' ')
        append(c)
    }
}

/**
 * bpe.vocab for hotwords: the model's own file if present, otherwise built from tokens.txt the same
 * way the Android app does (score 0 up to `<unk>`, then -1, -2, …; see app Hotwords.bpeVocab).
 */
fun bpeVocabFile(modelDir: File): File {
    File(modelDir, "bpe.vocab").takeIf { it.isFile }?.let { return it }
    val tokens = File(modelDir, "tokens.txt").readLines(Charsets.UTF_8).mapNotNull { it.substringBeforeLast(' ').takeIf { p -> p.isNotEmpty() } }
    var after = false
    var k = 0
    val text = tokens.joinToString("\n") { t ->
        val score = if (after) -(++k).toFloat() else 0f
        if (t == "<unk>") after = true
        "$t\t$score"
    }
    return File.createTempFile("bpe", ".vocab").also { it.deleteOnExit(); it.writeText(text, Charsets.UTF_8) }
}
