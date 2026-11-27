package tw.captions.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Streaming speech recognizer for one utterance at a time (e.g. a sherpa-onnx online recognizer).
 * Implementations decode as much as they can inside [accept].
 */
interface StreamingAsr {
    /** Feeds 16 kHz mono samples in [-1, 1] and decodes. */
    fun accept(samples: FloatArray)

    /** Current hypothesis for the utterance in progress. */
    fun text(): String

    /** True when the recognizer's own endpoint rules say the utterance has ended. */
    fun isEndpoint(): Boolean

    /** Starts a new utterance. */
    fun reset()
}

/** Voice activity detector (e.g. Silero VAD). */
interface VoiceActivityDetector {
    fun accept(samples: FloatArray)
    fun isSpeech(): Boolean
    fun reset()
}

/**
 * Speech enhancement / noise suppression applied before VAD, recognition and speaker embeddings
 * (e.g. a sherpa-onnx GTCRN denoiser). Streaming: output may lag the input by a few frames.
 */
interface AudioEnhancer {
    fun process(samples: FloatArray): FloatArray
    fun reset()
}

/** Turns a stretch of speech into a speaker embedding (e.g. a 3D-Speaker CAM++ model). */
fun interface SpeakerEmbedder {
    fun embed(samples: FloatArray): FloatArray
}

data class PipelineConfig(
    val sampleRate: Int = 16_000,
    /** After this much speech in an utterance, partials get a provisional speaker label. */
    val provisionalSpeakerSec: Float = 1.0f,
    /** Shorter utterances keep their provisional label instead of updating speaker profiles. */
    val minSpeakerSec: Float = 0.6f,
    /** Only the last this-many seconds of an utterance are used for its speaker embedding. */
    val maxSpeakerSec: Float = 8f,
    /**
     * End the utterance once the VAD has heard this much silence after speech, even if the
     * recognizer's own endpoint rules have not fired yet. Null leaves endpointing to the recognizer.
     */
    val vadEndpointSilenceSec: Float? = 0.4f,
    /**
     * ...and only if the recognized text has not changed for this long. The VAD needs a moment to
     * notice that speech has started again, and the recognizer lags the audio a little, so this
     * stops the VAD from cutting an utterance in the middle of a word.
     */
    val vadEndpointStableSec: Float = 0.2f,
    /**
     * Silence fed to the recognizer before a VAD endpoint, so it decodes the last few hundred
     * milliseconds it is still holding back for look-ahead instead of dropping them.
     */
    val tailPaddingSec: Float = 0.4f,
    /**
     * Lines with less speech than this never start a new speaker: short embeddings are noisy and
     * would otherwise split one person into several.
     */
    val minNewSpeakerSec: Float = 1.0f,
    /**
     * People who talk without real pauses would get one endless line. After this long, a short
     * breath ([shortPauseSec]) is enough to end the line.
     */
    val maxLineSec: Float = 8f,
    val shortPauseSec: Float = 0.15f,
    /** Emit [TranscriptEvent.NoSpeechDetected] after this much silence in a row. */
    val noSpeechHintSec: Float = 6f,
    /**
     * Lines this much quieter than the conversation are marked as background (TV, other people
     * nearby). Null turns the focus filter off. See [FocusTracker].
     */
    val focusMarginDb: Float? = 8f,
)

/**
 * Glue between a streaming recognizer, a VAD and speaker clustering. It is pure Kotlin and
 * synchronous: callers push audio chunks (e.g. 100 ms) and get [TranscriptEvent]s back, which
 * keeps it easy to drive from Android's AudioRecord, from a wav file, or from a test.
 */
class StreamingPipeline(
    private val asr: StreamingAsr,
    private val vad: VoiceActivityDetector? = null,
    private val embedder: SpeakerEmbedder? = null,
    private val clusterer: SpeakerClusterer = SpeakerClusterer(),
    private val config: PipelineConfig = PipelineConfig(),
    private val enhancer: AudioEnhancer? = null,
) {
    private val speech = SampleBuffer((config.maxSpeakerSec * config.sampleRate).toInt())
    private var lastText = ""
    private var provisional: SpeakerId? = null
    private var lastSpeaker: SpeakerId? = null
    private var silenceSec = 0f
    private var quietSec = 0f
    private var unchangedSec = 0f
    private var lineSec = 0f
    private val focus = config.focusMarginDb?.let { FocusTracker(marginDb = it) }
    private var started = false

    /** Seconds of audio consumed so far. */
    var audioTimeSec = 0.0
        private set

    fun reset() {
        focus?.reset()
        enhancer?.reset()
        asr.reset()
        vad?.reset()
        clusterer.reset()
        speech.clear()
        lastText = ""
        provisional = null
        lastSpeaker = null
        silenceSec = 0f
        quietSec = 0f
        unchangedSec = 0f
        lineSec = 0f
        started = false
        audioTimeSec = 0.0
    }

    fun feed(input: FloatArray): List<TranscriptEvent> {
        val out = ArrayList<TranscriptEvent>(3)
        if (!started) {
            started = true
            out += TranscriptEvent.Listening
        }
        audioTimeSec += input.size.toDouble() / config.sampleRate
        // The level meter shows what the microphone hears, before any noise suppression.
        out += TranscriptEvent.AudioLevel(levelOf(input))
        val chunk = enhancer?.process(input) ?: input
        if (chunk.isEmpty()) return out
        val chunkSec = chunk.size.toFloat() / config.sampleRate

        val isSpeech = if (vad != null) {
            vad.accept(chunk)
            vad.isSpeech()
        } else {
            true
        }
        if (isSpeech) {
            speech.append(chunk)
            silenceSec = 0f
            quietSec = 0f
        } else {
            silenceSec += chunkSec
            quietSec += chunkSec
            if (vad != null && quietSec >= config.noSpeechHintSec && lastText.isEmpty()) {
                quietSec = 0f
                out += TranscriptEvent.NoSpeechDetected
            }
        }

        asr.accept(chunk)
        val text = asr.text().trim()
        if (text == lastText) unchangedSec += chunkSec
        if (text.isNotEmpty() && text != lastText) {
            lastText = text
            unchangedSec = 0f
            if (provisional == null && embedder != null &&
                speech.size >= config.provisionalSpeakerSec * config.sampleRate
            ) {
                provisional = clusterer.peek(embedder.embed(speech.toArray()))
            }
            val bg = focus != null && speech.size >= config.sampleRate / 2 &&
                focus.isBackground(FocusTracker.levelDb(speech.toArray()))
            out += TranscriptEvent.Partial(text, if (bg) null else provisional, background = bg)
        }

        if (text.isNotEmpty()) lineSec += chunkSec
        val vadEndpoint = config.vadEndpointSilenceSec?.let {
            val pause = if (lineSec >= config.maxLineSec) minOf(it, config.shortPauseSec) else it
            vad != null && text.isNotEmpty() && silenceSec >= pause && unchangedSec >= config.vadEndpointStableSec
        } ?: false
        if (vadEndpoint) {
            asr.accept(FloatArray((config.tailPaddingSec * config.sampleRate).toInt()))
            finishUtterance(asr.text().trim())?.let { out += it }
        } else if (asr.isEndpoint()) {
            finishUtterance(text)?.let { out += it }
        }
        return out
    }

    /** Ends the current utterance, e.g. when the audio stream ends. */
    fun flush(): List<TranscriptEvent> = listOfNotNull(finishUtterance(asr.text().trim()))

    private fun finishUtterance(text: String): TranscriptEvent.Final? {
        val final = if (text.isNotEmpty()) {
            val samples = speech.toArray()
            val background = focus != null && samples.isNotEmpty() && focus.observe(FocusTracker.levelDb(samples))
            val speaker = when {
                background || embedder == null -> null
                speech.size >= config.minSpeakerSec * config.sampleRate ->
                    clusterer.assign(embedder.embed(samples), allowNew = speech.size >= config.minNewSpeakerSec * config.sampleRate)
                else -> provisional ?: lastSpeaker
            }
            if (speaker != null) lastSpeaker = speaker
            TranscriptEvent.Final(text, speaker, background)
        } else {
            null
        }
        asr.reset()
        speech.clear()
        lastText = ""
        unchangedSec = 0f
        lineSec = 0f
        provisional = null
        return final
    }

    private fun levelOf(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var s = 0.0
        for (x in chunk) s += x * x
        val rms = sqrt(s / chunk.size)
        // -60 dBFS (silence) .. -10 dBFS (loud speech) -> 0..1
        val db = 20 * log10(rms + 1e-9)
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }
}

/** Keeps the most recent [capacity] samples. */
internal class SampleBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun append(samples: FloatArray) {
        for (x in samples) {
            val end = (start + size) % capacity
            data[end] = x
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    fun toArray(): FloatArray = FloatArray(size) { data[(start + it) % capacity] }

    fun clear() {
        start = 0
        size = 0
    }
}
