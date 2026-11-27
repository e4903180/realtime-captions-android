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
    /** Emit [TranscriptEvent.NoSpeechDetected] after this much silence in a row. */
    val noSpeechHintSec: Float = 6f,
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
) {
    private val speech = SampleBuffer((config.maxSpeakerSec * config.sampleRate).toInt())
    private var lastText = ""
    private var provisional: SpeakerId? = null
    private var lastSpeaker: SpeakerId? = null
    private var silenceSec = 0f
    private var quietSec = 0f
    private var unchangedSec = 0f
    private var started = false

    /** Seconds of audio consumed so far. */
    var audioTimeSec = 0.0
        private set

    fun reset() {
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
        started = false
        audioTimeSec = 0.0
    }

    fun feed(chunk: FloatArray): List<TranscriptEvent> {
        val out = ArrayList<TranscriptEvent>(3)
        if (!started) {
            started = true
            out += TranscriptEvent.Listening
        }
        val chunkSec = chunk.size.toFloat() / config.sampleRate
        audioTimeSec += chunkSec
        out += TranscriptEvent.AudioLevel(levelOf(chunk))

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
            out += TranscriptEvent.Partial(text, provisional)
        }

        val vadEndpoint = config.vadEndpointSilenceSec?.let {
            vad != null && text.isNotEmpty() && silenceSec >= it && unchangedSec >= config.vadEndpointStableSec
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
            val speaker = when {
                embedder == null -> null
                speech.size >= config.minSpeakerSec * config.sampleRate -> clusterer.assign(embedder.embed(speech.toArray()))
                else -> provisional ?: lastSpeaker
            }
            if (speaker != null) lastSpeaker = speaker
            TranscriptEvent.Final(text, speaker)
        } else {
            null
        }
        asr.reset()
        speech.clear()
        lastText = ""
        unchangedSec = 0f
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
