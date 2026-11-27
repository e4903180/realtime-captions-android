@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package tw.captions.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/*
 * Small helpers for the Swift side of the iPhone app. Swift sees Kotlin default arguments only as
 * required arguments, and a Kotlin FloatArray only as an object with get/set, so these keep the
 * Swift code short and avoid copying audio one sample at a time.
 */

/** Copies [count] samples from a Swift buffer into a new Kotlin FloatArray. */
fun floatArrayFromPointer(src: CPointer<FloatVar>, count: Int): FloatArray {
    val out = FloatArray(count)
    if (count == 0) return out
    out.usePinned { memcpy(it.addressOf(0), src, (count * Float.SIZE_BYTES).toULong()) }
    return out
}

/** Copies all of [array] into a Swift buffer (not named copy…: Objective-C reserves that prefix) that has room for at least [array].size samples. */
fun writeFloatArrayToPointer(array: FloatArray, dst: CPointer<FloatVar>) {
    if (array.isEmpty()) return
    array.usePinned { memcpy(dst, it.addressOf(0), (array.size * Float.SIZE_BYTES).toULong()) }
}

/** Reads one sample; used by tests on the Swift side. */
fun floatAt(src: CPointer<FloatVar>, index: Int): Float = src[index]

/**
 * The offline pipeline. Differences from the Android defaults, from iPhone testing (ADR-024):
 * a line ends after 0.6 s of silence instead of 0.4 s, and the volume-based background filter is
 * off (the iPhone has no switch for it, and it left quieter speakers without a speaker label).
 * With [keepAudio], each line's audio is kept for the second pass (ADR-025).
 */
fun createOfflinePipeline(
    asr: StreamingAsr,
    vad: VoiceActivityDetector?,
    embedder: SpeakerEmbedder?,
    speakerThreshold: Float,
    keepAudio: Boolean,
): StreamingPipeline = StreamingPipeline(
    asr, vad, embedder, SpeakerClusterer(speakerThreshold),
    PipelineConfig(vadEndpointSilenceSec = 0.6f, focusMarginDb = null, keepUtteranceAudioSec = if (keepAudio) 20f else 0f),
)

/**
 * Caption assembler with the default clean-up steps and the given Chinese converter. One person's
 * utterances less than 1.5 s apart are joined into one line (ADR-024).
 */
fun createCaptionAssembler(converter: ChineseScriptConverter): CaptionAssembler =
    CaptionAssembler(TextProcessor.default(converter = converter), mergeGapSec = 1.5)

/** Speaker letter of a caption line ("A", "B", ...), or null. Value classes reach Swift boxed, so read it here. */
fun speakerLabelOf(line: CaptionLine): String? = line.speaker?.label

/** Speaker letter of the line in progress, or null. */
fun currentSpeakerLabelOf(state: CaptionState): String? = state.currentSpeaker?.label

/*
 * Event constructors for engines written in Swift (e.g. Azure). Swift has no Kotlin default
 * arguments and sees SpeakerId (a value class) only boxed, so speakers are passed as an index:
 * -1 means unknown.
 */
private fun speakerOf(index: Int): SpeakerId? = if (index >= 0) SpeakerId(index) else null

fun partialEvent(text: String, speakerIndex: Int): TranscriptEvent = TranscriptEvent.Partial(text, speakerOf(speakerIndex))
fun finalEvent(text: String, speakerIndex: Int): TranscriptEvent = TranscriptEvent.Final(text, speakerOf(speakerIndex))
fun errorEvent(message: String, recoverable: Boolean): TranscriptEvent = TranscriptEvent.Error(message, recoverable)
fun levelEvent(level: Float): TranscriptEvent = TranscriptEvent.AudioLevel(level)
fun listeningEvent(): TranscriptEvent = TranscriptEvent.Listening

/** Same 0..1 level scale as the offline pipeline (-60 dBFS → 0, -10 dBFS → 1). */
fun levelOfSamples(src: CPointer<FloatVar>, count: Int): Float {
    if (count <= 0) return 0f
    var s = 0.0
    for (i in 0 until count) { val x = src[i]; s += x * x }
    val db = 20 * kotlin.math.log10(kotlin.math.sqrt(s / count) + 1e-9)
    return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
}
