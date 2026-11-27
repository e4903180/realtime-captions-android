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

/** The offline pipeline with the same defaults as the Android app (see SherpaOnnxEngine). */
fun createOfflinePipeline(
    asr: StreamingAsr,
    vad: VoiceActivityDetector?,
    embedder: SpeakerEmbedder?,
    speakerThreshold: Float,
): StreamingPipeline = StreamingPipeline(asr, vad, embedder, SpeakerClusterer(speakerThreshold))

/** Caption assembler with the default clean-up steps and the given Chinese converter. */
fun createCaptionAssembler(converter: ChineseScriptConverter): CaptionAssembler =
    CaptionAssembler(TextProcessor.default(converter = converter))

/** Speaker letter of a caption line ("A", "B", ...), or null. Value classes reach Swift boxed, so read it here. */
fun speakerLabelOf(line: CaptionLine): String? = line.speaker?.label

/** Speaker letter of the line in progress, or null. */
fun currentSpeakerLabelOf(state: CaptionState): String? = state.currentSpeaker?.label
